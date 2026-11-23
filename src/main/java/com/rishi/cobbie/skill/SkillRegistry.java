package com.rishi.cobbie.skill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cobbie's library of reusable procedures, persisted to config/cobbie_skills.json.
 *
 * The brain asks {@link #match(String, int)} (or {@link #describeForPrompt(int)})
 * before deciding what to do, then reports back with
 * {@link #recordOutcome(String, boolean)} when a run finishes — reliable skills
 * float to the top, flaky ones sink, and the owner can mute or delete any of
 * them without editing a file by hand.
 *
 * Pure file/JSON layer: no Minecraft types, so it runs in unit tests against a
 * temp path via {@link #loadFrom(Path)}. No public method throws.
 */
public final class SkillRegistry {

	// ----- limits: keep the JSON small and the prompt block smaller -----
	public static final int MAX_SKILLS = 256;
	public static final int MAX_STEPS = 32;
	public static final int MAX_NAME = 64;
	public static final int MAX_DESCRIPTION = 400;
	public static final int MAX_TARGET = 120;
	public static final int MAX_DATA = 120;
	public static final int PROMPT_CHAR_CAP = 3000;
	public static final int MAX_PROMPT_SKILLS = 20;
	public static final int DEFAULT_MATCH_LIMIT = 3;

	/** The only step actions the task executor knows how to run. */
	public static final Set<String> ALLOWED_ACTIONS = Set.of(
			"answer", "follow", "stay", "wave", "walk-to", "mine", "place",
			"attack", "collect", "chat", "wait", "explore", "light", "give", "drop", "equip", "craft",
			"smelt", "take", "put", "lookup");

	/** Words too common to be a useful retrieval signal. */
	private static final Set<String> STOPWORDS = Set.of(
			"a", "an", "the", "to", "me", "my", "us", "we", "you", "your", "for", "and", "or",
			"of", "it", "is", "in", "on", "at", "do", "does", "please", "can", "will", "would",
			"then", "some", "cobbie", "with", "now", "just", "get", "go");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();
	private static final Map<String, Skill> skills = new LinkedHashMap<>();

	private static boolean loaded = false;
	private static Path fileOverride = null; // set by loadFrom(), mainly for tests

	private SkillRegistry() {}

	// ================= read =================

	public static List<Skill> all() {
		try {
			synchronized (LOCK) {
				ensureLoaded();
				return new ArrayList<>(skills.values());
			}
		} catch (Throwable t) {
			return fail("all", t, List.of());
		}
	}

	public static Optional<Skill> get(String name) {
		try {
			if (name == null || name.isBlank()) return Optional.empty();
			synchronized (LOCK) {
				ensureLoaded();
				return Optional.ofNullable(skills.get(key(name)));
			}
		} catch (Throwable t) {
			return fail("get", t, Optional.empty());
		}
	}

	/**
	 * Enabled skills whose name/description words show up in what the player just
	 * said, best first (most overlap, then reliability, then raw successes, then
	 * name). Returns nothing rather than guessing.
	 */
	public static List<Skill> match(String userText, int limit) {
		try {
			Set<String> query = tokens(userText);
			if (query.isEmpty()) return List.of();
			int cap = limit <= 0 ? DEFAULT_MATCH_LIMIT : Math.min(limit, MAX_PROMPT_SKILLS);

			List<Ranked> hits = new ArrayList<>();
			for (Skill s : all()) {
				if (!s.enabled()) continue;
				Set<String> own = tokens(s.name().replace('-', ' ').replace('_', ' '));
				own.addAll(tokens(s.description()));
				int overlap = 0;
				for (String w : query) {
					if (own.contains(w)) overlap++;
				}
				if (overlap > 0) hits.add(new Ranked(s, overlap));
			}
			hits.sort(Comparator.comparingInt(Ranked::overlap).reversed()
					.thenComparingDouble(r -> -r.skill().reliability())
					.thenComparingInt(r -> -r.skill().successes())
					.thenComparing(r -> r.skill().name()));

			List<Skill> out = new ArrayList<>();
			for (int i = 0; i < hits.size() && i < cap; i++) out.add(hits.get(i).skill());
			return out;
		} catch (Throwable t) {
			return fail("match", t, List.of());
		}
	}

	/** Compact "things you already know how to do" block for the system prompt. */
	public static String describeForPrompt(int maxSkills) {
		try {
			int cap = maxSkills <= 0 ? DEFAULT_MATCH_LIMIT : Math.min(maxSkills, MAX_PROMPT_SKILLS);
			List<Skill> ranked = new ArrayList<>();
			for (Skill s : all()) {
				if (s.enabled()) ranked.add(s);
			}
			if (ranked.isEmpty()) return "";
			ranked.sort(Comparator.comparingDouble(Skill::reliability).reversed()
					.thenComparing(Skill::name));

			StringBuilder sb = new StringBuilder(256);
			for (int i = 0; i < ranked.size() && i < cap; i++) {
				Skill s = ranked.get(i);
				List<String> actions = new ArrayList<>();
				for (ActionStep st : s.steps()) {
					if (actions.size() >= 6) break;
					actions.add(st.action());
				}
				if (i > 0) sb.append('\n');
				sb.append("- ").append(s.name()).append(": ").append(s.description())
						.append(" [").append(String.join(">", actions)).append("]")
						.append(" ok ").append(s.successes()).append('/').append(s.trials());
			}
			String out = sb.toString();
			return out.length() <= PROMPT_CHAR_CAP ? out : out.substring(0, PROMPT_CHAR_CAP - 1) + "~";
		} catch (Throwable t) {
			return fail("describeForPrompt", t, "");
		}
	}

	/** Human-readable dump for /cobbie skills. */
	public static String inspect() {
		try {
			List<Skill> list = all();
			StringBuilder sb = new StringBuilder(256);
			sb.append(list.size()).append('/').append(MAX_SKILLS).append(" skills (").append(fileOrNone()).append("):\n");
			if (list.isEmpty()) {
				return sb.append("  (none yet — a working plan gets recorded as a skill)").toString();
			}
			for (Skill s : list) {
				sb.append("- ").append(s.name())
						.append(s.enabled() ? " [on] " : " [OFF] ")
						.append("ok ").append(s.successes()).append('/').append(s.trials());
				if (s.trials() > 0) {
					sb.append(" (").append(Math.round(100.0 * s.successes() / s.trials())).append("%)");
				}
				sb.append('\n');
				if (!s.description().isEmpty()) sb.append("    ").append(s.description()).append('\n');
				for (ActionStep st : s.steps()) {
					sb.append("    ").append(st.summary());
					if (st.relative()) sb.append(" (relative)");
					sb.append('\n');
				}
			}
			return sb.toString();
		} catch (Throwable t) {
			return fail("inspect", t, "Cobbie's skill store is unreadable.");
		}
	}

	// ================= write =================

	/** Validate and store a new skill. False = rejected (bad input, dup name, store full). */
	public static boolean saveSkill(String name, String description, List<ActionStep> steps) {
		try {
			String clean = cleanName(name);
			if (clean.isEmpty()) return false;

			List<ActionStep> cleanSteps = new ArrayList<>();
			if (steps != null) {
				for (ActionStep st : steps) {
					if (st == null || !isAllowedAction(st.action())) return false;
					cleanSteps.add(new ActionStep(st.action().trim().toLowerCase(Locale.ROOT),
							fit(st.target(), MAX_TARGET), st.x(), st.y(), st.z(), fit(st.data(), MAX_DATA)));
				}
			}
			if (cleanSteps.isEmpty() || cleanSteps.size() > MAX_STEPS) return false;

			synchronized (LOCK) {
				ensureLoaded();
				if (skills.containsKey(key(clean))) return false;
				if (skills.size() >= MAX_SKILLS) {
					Cobbie.LOGGER.info("Cobbie knows {} skills already, not learning '{}'", MAX_SKILLS, clean);
					return false;
				}
				skills.put(key(clean), new Skill(clean, fit(description, MAX_DESCRIPTION), cleanSteps, 0, 0, true));
				return save();
			}
		} catch (Throwable t) {
			return fail("saveSkill", t, false);
		}
	}

	/** Credit or blame a skill for how a run ended. False if there is no such skill. */
	public static boolean recordOutcome(String name, boolean success) {
		try {
			synchronized (LOCK) {
				ensureLoaded();
				Skill s = skills.get(key(name));
				if (s == null) return false;
				skills.put(key(name), new Skill(s.name(), s.description(), s.steps(),
						Math.min(999_999, s.successes() + (success ? 1 : 0)),
						Math.min(999_999, s.failures() + (success ? 0 : 1)),
						s.enabled()));
				return save();
			}
		} catch (Throwable t) {
			return fail("recordOutcome", t, false);
		}
	}

	/** Owner-level mute switch; keeps the skill and its track record around. */
	public static boolean setEnabled(String name, boolean enabled) {
		try {
			synchronized (LOCK) {
				ensureLoaded();
				Skill s = skills.get(key(name));
				if (s == null) return false;
				if (s.enabled() == enabled) return true; // unchanged: don't touch the file
				skills.put(key(name), new Skill(s.name(), s.description(), s.steps(),
						s.successes(), s.failures(), enabled));
				return save();
			}
		} catch (Throwable t) {
			return fail("setEnabled", t, false);
		}
	}

	public static boolean delete(String name) {
		try {
			synchronized (LOCK) {
				ensureLoaded();
				if (skills.remove(key(name)) == null) return false;
				return save();
			}
		} catch (Throwable t) {
			return fail("delete", t, false);
		}
	}

	/**
	 * Point the registry at a specific file and reload from it. Tests pass a temp
	 * path; null goes back to config/cobbie_skills.json.
	 */
	public static void loadFrom(Path file) {
		try {
			synchronized (LOCK) {
				fileOverride = file;
				loaded = false;
				skills.clear();
				ensureLoaded();
			}
		} catch (Throwable t) {
			fail("loadFrom", t, null);
		}
	}

	public static boolean isAllowedAction(String action) {
		return action != null && ALLOWED_ACTIONS.contains(action.trim().toLowerCase(Locale.ROOT));
	}

	// ================= persistence =================

	private static Path file() {
		if (fileOverride != null) return fileOverride;
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("cobbie_skills.json");
		} catch (Throwable t) {
			return null; // no game (unit test): stay in memory
		}
	}

	private static String fileOrNone() {
		Path p = file();
		return p == null ? "in-memory" : p.toString();
	}

	/** Caller holds LOCK. Loads at most once; a broken file gets set aside, not fatal. */
	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path file = file();
		if (file == null || !Files.exists(file)) {
			// Seeding is an install-time convenience, not a property of an empty store: a test that
			// points the registry at its own file must see exactly what it put there.
			if (fileOverride == null) seedBuiltins();
			return;
		}
		try {
			read(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Cobbie skills file is unreadable ({}); starting clean", t.toString());
			skills.clear();
			badFileAway(file);
		}
	}


	/**
	 * A brand new install starts with a few things he already knows how to do, so {@code cobbie do}
	 * has something to run and the model has examples of well-formed procedures to imitate. They are
	 * ordinary skills: he can run them, they can fail, and the owner can delete or mute them.
	 */
	private static void seedBuiltins() {
		saveSkill("wave hello", "wave at the player as a friendly greeting",
				List.of(new ActionStep("wave", "", 0, 0, 0, "")));
		saveSkill("light the way", "place a torch on the ground a couple of blocks ahead",
				List.of(new ActionStep("light", "minecraft:torch", 0, 1, 2, "rel")));
		saveSkill("gather some wood", "mine two block of wood in front of me, bottom then top",
				List.of(new ActionStep("mine", "minecraft:oak_log", 0, 1, 2, "rel"),
						new ActionStep("mine", "minecraft:oak_log", 0, 2, 2, "rel")));
		Cobbie.LOGGER.info("Cobbie seeded {} built-in skills", skills.size());
	}

	/** Accepts the current array format and the old 0.4.0 name-keyed format. */
	private static void read(JsonObject root) {
		List<Skill> parsed = new ArrayList<>();
		if (root.has("skills") && root.get("skills").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("skills")) {
				if (el.isJsonObject()) parsed.add(readSkill(el.getAsJsonObject()));
			}
		} else {
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				Skill s = readSkill(e.getValue().getAsJsonObject());
				String name = s.name().isEmpty() ? e.getKey() : s.name();
				parsed.add(new Skill(name, s.description(), s.steps(), s.successes(), s.failures(), s.enabled()));
			}
		}
		skills.clear();
		for (Skill s : parsed) {
			if (s.name().isEmpty() || s.steps().isEmpty()) continue; // no steps = nothing to replay
			if (skills.size() >= MAX_SKILLS) break;
			skills.put(key(s.name()), s);
		}
	}

	private static Skill readSkill(JsonObject obj) {
		List<ActionStep> steps = new ArrayList<>();
		if (obj.has("steps") && obj.getAsJsonArray("steps").isJsonArray()) {
			for (JsonElement el : obj.getAsJsonArray("steps")) {
				if (steps.size() >= MAX_STEPS) break;
				if (!el.isJsonObject()) continue;
				JsonObject so = el.getAsJsonObject();
				String action = str(so, "action", "").toLowerCase(Locale.ROOT);
				if (!isAllowedAction(action)) continue; // drop steps the executor cannot run
				steps.add(new ActionStep(action, str(so, "target", ""),
						intOr(so, "x"), intOr(so, "y"), intOr(so, "z"), str(so, "data", "")));
			}
		}
		// "success"/"failure" are the 0.4.0 field names; reading both means a
		// hand-edited or renamed file never loses its track record.
		return new Skill(str(obj, "name", ""),
				str(obj, "description", str(obj, "context", "")),
				steps,
				intOr(obj, "successes") + intOr(obj, "success"),
				intOr(obj, "failures") + intOr(obj, "failure"),
				!obj.has("enabled") || obj.get("enabled").getAsBoolean());
	}

	/** Caller holds LOCK. Writes temp + ATOMIC_MOVE, falling back to a plain replace. */
	private static boolean save() {
		Path file = file();
		if (file == null) return true; // memory only, nothing to persist
		try {
			JsonArray arr = new JsonArray();
			for (Skill s : skills.values()) {
				JsonObject so = new JsonObject();
				so.addProperty("name", s.name());
				so.addProperty("description", s.description());
				so.addProperty("enabled", s.enabled());
				so.addProperty("successes", s.successes());
				so.addProperty("failures", s.failures());
				JsonArray steps = new JsonArray();
				for (ActionStep st : s.steps()) {
					JsonObject to = new JsonObject();
					to.addProperty("action", st.action());
					to.addProperty("target", st.target());
					to.addProperty("x", st.x());
					to.addProperty("y", st.y());
					to.addProperty("z", st.z());
					to.addProperty("data", st.data());
					steps.add(to);
				}
				so.add("steps", steps);
				arr.add(so);
			}
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.add("skills", arr);

			if (file.getParent() != null) Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root));
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException noAtomicMove) {
				// some filesystems/network drives refuse atomic moves; a plain
				// replace is still better than losing the write
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Failed to save Cobbie skills: {}", t.toString());
			return false;
		}
	}

	private static void badFileAway(Path file) {
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Could not move aside unreadable skills file {}: {}", file, t.toString());
		}
	}

	// ================= helpers =================

	private record Ranked(Skill skill, int overlap) {}

	/**
	 * The storage key for a skill name. It must be the same normalisation saving uses, otherwise
	 * "wave hello" is stored as "wave-hello" and can never be found again by the name a player types.
	 */
	private static String key(String name) {
		return cleanName(name == null ? "" : name);
	}

	/** Lower-case, spaces to dashes, only id-safe characters. "" means unusable. */
	private static String cleanName(String name) {
		String n = fit(name, MAX_NAME * 2).toLowerCase(Locale.ROOT).replace(' ', '-');
		StringBuilder sb = new StringBuilder(n.length());
		for (int i = 0; i < n.length(); i++) {
			char c = n.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '_' || c == '.' || c == ':';
			sb.append(ok ? c : '-');
		}
		String out = sb.toString().replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
		return out.length() > MAX_NAME ? out.substring(0, MAX_NAME) : out;
	}

	private static Set<String> tokens(String text) {
		Set<String> out = new LinkedHashSet<>();
		if (text == null) return out;
		for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (raw.length() < 2 || STOPWORDS.contains(raw)) continue;
			out.add(raw.length() > 24 ? raw.substring(0, 24) : raw);
		}
		return out;
	}

	private static String fit(String s, int max) {
		if (s == null) return "";
		String c = s.replaceAll("\\s+", " ").trim();
		return c.length() <= max ? c : c.substring(0, max);
	}

	private static String str(JsonObject obj, String field, String fallback) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static int intOr(JsonObject obj, String field) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsInt() : 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	private static <T> T fail(String what, Throwable t, T fallback) {
		try {
			Cobbie.LOGGER.warn("Cobbie skills {}: {}", what, t.toString());
		} catch (Throwable ignored) {
			// logging must never be the thing that breaks the game
		}
		return fallback;
	}
}
