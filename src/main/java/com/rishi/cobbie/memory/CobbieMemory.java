package com.rishi.cobbie.memory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent memory of the people Cobbie lives with, in config/cobbie_memory.json.
 *
 * Two layers:
 *  - a short rolling conversation history holding BOTH sides of every exchange
 *    as {@code user}/{@code assistant} messages, so the prompt can be replayed
 *    faithfully and {@code max_chat_history} really does bound the context;
 *  - a few small long-term fields (nickname, preferences, current objective,
 *    discoveries, skills learned) that survive the history rolling over.
 *
 * Storage rules learned the hard way: writes go to a temp file and are moved
 * into place atomically; an unreadable file is moved aside instead of throwing;
 * unchanged values never rewrite the file; a failed LLM call records no answer.
 *
 * No Minecraft types here — the whole class is testable against a temp file.
 */
public final class CobbieMemory {

	public record Message(String role, String content) {}

	public record Preference(String key, String value) {}

	/** Everything stored for one player. Lists are immutable snapshots. */
	public record PlayerMemory(String nickname, List<Message> history, List<Preference> preferences,
			List<String> discoveries, String objective, List<String> skills) {

		public PlayerMemory {
			if (history == null) history = List.of();
			if (preferences == null) preferences = List.of();
			if (discoveries == null) discoveries = List.of();
			if (skills == null) skills = List.of();
			nickname = nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname;
			objective = objective == null ? "" : objective;
		}
	}

	public static final String DEFAULT_NICKNAME = "Player";
	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";

	// ----- caps: this all goes near a 4B model's small context window -----
	public static final int MAX_MESSAGES = 40;              // 20 exchanges kept on disk
	public static final int DEFAULT_HISTORY_MESSAGES = 12;  // used when the caller passes <= 0
	public static final int MAX_MESSAGE_CHARS = 400;
	public static final int MAX_NICKNAME = 24;
	public static final int MAX_PREFERENCES = 16;
	public static final int MAX_PREFERENCE_KEY = 32;
	public static final int MAX_PREFERENCE_VALUE = 80;
	public static final int MAX_DISCOVERIES = 12;
	public static final int MAX_DISCOVERY_CHARS = 140;
	public static final int MAX_OBJECTIVE_CHARS = 140;
	public static final int MAX_SKILL_NAMES = 12;
	public static final int SUMMARY_CHAR_CAP = 400;
	public static final int INSPECT_HISTORY_LINES = 6;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();
	private static final Map<UUID, PlayerMemory> memories = new LinkedHashMap<>();
	private static final PlayerMemory EMPTY =
			new PlayerMemory(DEFAULT_NICKNAME, List.of(), List.of(), List.of(), "", List.of());

	private static boolean loaded = false;
	private static boolean dirty = false;
	private static Path fileOverride = null; // set by setFileForTesting()

	private CobbieMemory() {}

	// ================= conversation =================

	/** Never null. Does not create a record just by looking. */
	public static PlayerMemory get(UUID player) {
		try {
			if (player == null) return EMPTY;
			synchronized (LOCK) {
				ensureLoaded();
				return memories.getOrDefault(player, EMPTY);
			}
		} catch (Throwable t) {
			return fail("get", t, EMPTY);
		}
	}

	/**
	 * Record one exchange. The blank side is skipped, so a failed request that
	 * produced no answer leaves no fake assistant turn behind — and when the
	 * retry succeeds with the same question, the question is not duplicated.
	 */
	public static void addTurn(UUID player, String userText, String assistantText) {
		try {
			if (player == null) return;
			String user = fit(userText, MAX_MESSAGE_CHARS);
			String answer = fit(assistantText, MAX_MESSAGE_CHARS);
			if (user.isEmpty() && answer.isEmpty()) return;

			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				List<Message> h = new ArrayList<>(m.history());
				int n = h.size();

				if (n >= 2 && user.equals(h.get(n - 2).content()) && answer.equals(h.get(n - 1).content())) {
					return; // identical turn already recorded
				}
				boolean questionAlreadyThere = n >= 1 && ROLE_USER.equals(h.get(n - 1).role())
						&& user.equals(h.get(n - 1).content());
				if (!user.isEmpty() && !questionAlreadyThere) h.add(new Message(ROLE_USER, user));
				boolean answerAlreadyThere = !h.isEmpty() && ROLE_ASSISTANT.equals(h.get(h.size() - 1).role())
						&& answer.equals(h.get(h.size() - 1).content());
				if (!answer.isEmpty() && !answerAlreadyThere) h.add(new Message(ROLE_ASSISTANT, answer));
				while (h.size() > MAX_MESSAGES) {
					h.remove(0); // drop whole turns, oldest first, so user/assistant stay paired
					if (!h.isEmpty() && ROLE_ASSISTANT.equals(h.get(0).role())) h.remove(0);
				}
				store(player, m, h);
			}
		} catch (Throwable t) {
			fail("addTurn", t, null);
		}
	}

	/** The last {@code maxMessages} messages, oldest first — ready for the prompt. */
	public static List<Message> history(UUID player, int maxMessages) {
		try {
			int cap = maxMessages <= 0 ? DEFAULT_HISTORY_MESSAGES : Math.min(maxMessages, MAX_MESSAGES);
			List<Message> h = get(player).history();
			return h.size() <= cap ? h : List.copyOf(h.subList(h.size() - cap, h.size()));
		} catch (Throwable t) {
			return fail("history", t, List.of());
		}
	}

	public static void setNickname(UUID player, String nickname) {
		try {
			if (player == null) return;
			String nick = fit(nickname, MAX_NICKNAME);
			if (nick.isEmpty()) nick = DEFAULT_NICKNAME;
			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				if (m.nickname().equals(nick) && memories.containsKey(player)) return; // no rewrite
				memories.put(player, new PlayerMemory(nick, m.history(), m.preferences(),
						m.discoveries(), m.objective(), m.skills()));
				touch();
			}
		} catch (Throwable t) {
			fail("setNickname", t, null);
		}
	}

	// ================= long term =================

	public static void notePreference(UUID player, String key, String value) {
		try {
			if (player == null) return;
			String k = fit(key, MAX_PREFERENCE_KEY).toLowerCase(Locale.ROOT);
			String v = fit(value, MAX_PREFERENCE_VALUE);
			if (k.isEmpty() || v.isEmpty()) return;
			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				List<Preference> prefs = new ArrayList<>();
				for (Preference p : m.preferences()) {
					if (!p.key().equals(k)) prefs.add(p);
				}
				prefs.add(new Preference(k, v));
				while (prefs.size() > MAX_PREFERENCES) prefs.remove(0);
				memories.put(player, new PlayerMemory(m.nickname(), m.history(), List.copyOf(prefs),
						m.discoveries(), m.objective(), m.skills()));
				touch();
			}
		} catch (Throwable t) {
			fail("notePreference", t, null);
		}
	}

	public static void noteDiscovery(UUID player, String fact) {
		try {
			if (player == null) return;
			String fact2 = fit(fact, MAX_DISCOVERY_CHARS);
			if (fact2.isEmpty()) return;
			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				if (m.discoveries().contains(fact2)) return;
				List<String> out = new ArrayList<>(m.discoveries());
				out.add(fact2);
				while (out.size() > MAX_DISCOVERIES) out.remove(0);
				memories.put(player, new PlayerMemory(m.nickname(), m.history(), m.preferences(),
						List.copyOf(out), m.objective(), m.skills()));
				touch();
			}
		} catch (Throwable t) {
			fail("noteDiscovery", t, null);
		}
	}

	/** Pass blank/"" to forget the current objective. */
	public static void setCurrentObjective(UUID player, String objective) {
		try {
			if (player == null) return;
			String obj = fit(objective, MAX_OBJECTIVE_CHARS);
			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				if (m.objective().equals(obj) && memories.containsKey(player)) return;
				memories.put(player, new PlayerMemory(m.nickname(), m.history(), m.preferences(),
						m.discoveries(), obj, m.skills()));
				touch();
			}
		} catch (Throwable t) {
			fail("setCurrentObjective", t, null);
		}
	}

	public static String currentObjective(UUID player) {
		return get(player).objective();
	}

	/** Kept-from-0.4.0: the names of skills the owner taught Cobbie. */
	public static void addSkill(UUID player, String skill) {
		try {
			if (player == null) return;
			String name = fit(skill, MAX_PREFERENCE_KEY);
			if (name.isEmpty()) return;
			synchronized (LOCK) {
				ensureLoaded();
				PlayerMemory m = memories.getOrDefault(player, EMPTY);
				if (m.skills().contains(name)) return;
				List<String> out = new ArrayList<>(m.skills());
				out.add(name);
				while (out.size() > MAX_SKILL_NAMES) out.remove(0);
				memories.put(player, new PlayerMemory(m.nickname(), m.history(), m.preferences(),
						m.discoveries(), m.objective(), List.copyOf(out)));
				touch();
			}
		} catch (Throwable t) {
			fail("addSkill", t, null);
		}
	}

	/** One-line-ish memory block for the prompt. "" when there is nothing worth saying. */
	public static String longTermSummary(UUID player) {
		try {
			PlayerMemory m = get(player);
			List<String> parts = new ArrayList<>();
			if (!m.preferences().isEmpty()) {
				List<String> kv = new ArrayList<>();
				for (Preference p : m.preferences()) {
					if (kv.size() >= 6) break;
					kv.add(p.key() + "=" + p.value());
				}
				parts.add(m.nickname() + " likes/wants: " + String.join(", ", kv));
			}
			if (!m.objective().isEmpty()) parts.add("current goal: " + m.objective());
			if (!m.discoveries().isEmpty()) {
				int from = Math.max(0, m.discoveries().size() - 4);
				parts.add("discovered: " + String.join("; ", m.discoveries().subList(from, m.discoveries().size())));
			}
			if (!m.skills().isEmpty()) {
				List<String> names = m.skills().size() > 6 ? m.skills().subList(0, 6) : m.skills();
				parts.add("knows how to: " + String.join(", ", names));
			}
			if (parts.isEmpty()) return "";
			String out = String.join(" | ", parts);
			return out.length() <= SUMMARY_CHAR_CAP ? out : out.substring(0, SUMMARY_CHAR_CAP - 1) + "~";
		} catch (Throwable t) {
			return fail("longTermSummary", t, "");
		}
	}

	/** Full dump for the owner, e.g. /cobbie memory. */
	public static String inspect(UUID player) {
		try {
			if (player == null) return "no player selected";
			PlayerMemory m = get(player);
			StringBuilder sb = new StringBuilder(512);
			sb.append("memory for ").append(player).append(" (").append(fileOrNone()).append(")\n");
			sb.append("  nickname:   ").append(m.nickname()).append('\n');
			sb.append("  objective:  ").append(m.objective().isEmpty() ? "(none)" : m.objective()).append('\n');
			sb.append("  preferences:").append(m.preferences().isEmpty() ? " (none)" : "").append('\n');
			for (Preference p : m.preferences()) sb.append("    ").append(p.key()).append(" = ").append(p.value()).append('\n');
			sb.append("  discoveries:").append(m.discoveries().isEmpty() ? " (none)" : "").append('\n');
			for (String d : m.discoveries()) sb.append("    - ").append(d).append('\n');
			sb.append("  skills:     ").append(m.skills().isEmpty() ? "(none)" : String.join(", ", m.skills())).append('\n');
			sb.append("  history:    ").append(m.history().size()).append(" messages kept (of max ")
					.append(MAX_MESSAGES).append(")\n");
			List<Message> h = m.history();
			int from = Math.max(0, h.size() - INSPECT_HISTORY_LINES);
			for (int i = from; i < h.size(); i++) {
				sb.append("    ").append(h.get(i).role()).append(": ").append(h.get(i).content()).append('\n');
			}
			return sb.toString().trim();
		} catch (Throwable t) {
			return fail("inspect", t, "Cobbie's memory file is unreadable.");
		}
	}

	/** Owner-facing "forget everything about me". */
	public static void clear(UUID player) {
		try {
			if (player == null) return;
			synchronized (LOCK) {
				ensureLoaded();
				if (memories.remove(player) == null) return;
				touch();
			}
		} catch (Throwable t) {
			fail("clear", t, null);
		}
	}

	// ================= persistence =================

	/**
	 * Point the store at a specific file (tests use a temp path; null restores
	 * config/cobbie_memory.json and forces a reload on next access).
	 */
	public static void setFileForTesting(Path file) {
		synchronized (LOCK) {
			fileOverride = file;
			loaded = false;
			dirty = false;
			memories.clear();
		}
	}

	/**
	 * Write to disk if anything changed since the last write. Cheap to call often
	 * (that is the debounce), thread-safe, and never throws.
	 */
	public static void save() {
		try {
			synchronized (LOCK) {
				ensureLoaded();
				saveLocked();
			}
		} catch (Throwable t) {
			fail("save", t, null);
		}
	}

	private static void store(UUID player, PlayerMemory old, List<Message> history) {
		memories.put(player, new PlayerMemory(old.nickname(), List.copyOf(history), old.preferences(),
				old.discoveries(), old.objective(), old.skills()));
		touch();
	}

	/** Caller holds LOCK: flag the change and persist it right away. */
	private static void touch() {
		dirty = true;
		saveLocked();
	}

	/** Caller holds LOCK. No-op while clean; temp file + ATOMIC_MOVE when not. */
	private static void saveLocked() {
		if (!dirty) return;
		Path file = file();
		if (file == null) { // no game and no test path: memory only
			dirty = false;
			return;
		}
		try {
			JsonObject players = new JsonObject();
			for (Map.Entry<UUID, PlayerMemory> e : memories.entrySet()) {
				PlayerMemory m = e.getValue();
				JsonObject obj = new JsonObject();
				obj.addProperty("nickname", m.nickname());
				obj.addProperty("objective", m.objective());

				JsonArray history = new JsonArray();
				for (Message msg : m.history()) {
					JsonObject mo = new JsonObject();
					mo.addProperty("role", msg.role());
					mo.addProperty("content", msg.content());
					history.add(mo);
				}
				obj.add("history", history);

				JsonArray prefs = new JsonArray();
				for (Preference p : m.preferences()) {
					JsonObject po = new JsonObject();
					po.addProperty("key", p.key());
					po.addProperty("value", p.value());
					prefs.add(po);
				}
				obj.add("preferences", prefs);

				JsonArray disc = new JsonArray();
				for (String d : m.discoveries()) disc.add(d);
				obj.add("discoveries", disc);

				JsonArray skills = new JsonArray();
				for (String s : m.skills()) skills.add(s);
				obj.add("skills", skills);

				players.add(e.getKey().toString(), obj);
			}
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.add("players", players);

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
			dirty = false; // only once the bytes are actually in place
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Failed to save Cobbie memory: {}", t.toString());
		}
	}

	/** Caller holds LOCK. */
	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path file = file();
		if (file == null || !Files.exists(file)) return;
		try {
			read(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Cobbie memory file is unreadable ({}); starting fresh", t.toString());
			memories.clear();
			badFileAway(file);
		}
	}

	/** Reads the v1 format, and 0.4.0's UUID-keyed root with "turns" arrays. */
	private static void read(JsonObject root) {
		JsonObject players = root.has("players") && root.get("players").isJsonObject()
				? root.getAsJsonObject("players")
				: root;
		memories.clear();
		for (Map.Entry<String, JsonElement> e : players.entrySet()) {
			if (!e.getValue().isJsonObject()) continue; // e.g. the "version" field
			JsonObject obj = e.getValue().getAsJsonObject();
			UUID uuid;
			try {
				uuid = UUID.fromString(e.getKey());
			} catch (Exception badKey) {
				continue;
			}
			List<Message> history = new ArrayList<>();
			if (obj.has("history")) {
				for (JsonElement el : obj.getAsJsonArray("history")) {
					if (history.size() >= MAX_MESSAGES) break;
					if (!el.isJsonObject()) continue;
					JsonObject mo = el.getAsJsonObject();
					String role = str(mo, "role", ROLE_USER).toLowerCase(Locale.ROOT);
					if (!role.equals(ROLE_USER) && !role.equals(ROLE_ASSISTANT)) continue;
					String content = fit(str(mo, "content", ""), MAX_MESSAGE_CHARS);
					if (!content.isEmpty()) history.add(new Message(role, content));
				}
			} else if (obj.has("turns")) {
				// 0.4.0 stored {player, cobbie, ts} pairs — unfold them into roles.
				List<JsonElement> turns = new ArrayList<>();
				for (JsonElement el : obj.getAsJsonArray("turns")) turns.add(el);
				int skip = Math.max(0, turns.size() - MAX_MESSAGES / 2);
				for (int i = skip; i < turns.size(); i++) {
					if (!turns.get(i).isJsonObject()) continue;
					JsonObject to = turns.get(i).getAsJsonObject();
					String q = fit(str(to, "player", ""), MAX_MESSAGE_CHARS);
					String a = fit(str(to, "cobbie", ""), MAX_MESSAGE_CHARS);
					if (!q.isEmpty()) history.add(new Message(ROLE_USER, q));
					if (!a.isEmpty()) history.add(new Message(ROLE_ASSISTANT, a));
				}
			}

			List<Preference> prefs = new ArrayList<>();
			if (obj.has("preferences")) {
				for (JsonElement el : obj.getAsJsonArray("preferences")) {
					if (prefs.size() >= MAX_PREFERENCES) break;
					if (!el.isJsonObject()) continue;
					JsonObject po = el.getAsJsonObject();
					String k = fit(str(po, "key", ""), MAX_PREFERENCE_KEY);
					String v = fit(str(po, "value", ""), MAX_PREFERENCE_VALUE);
					if (!k.isEmpty() && !v.isEmpty()) prefs.add(new Preference(k, v));
				}
			}

			List<String> discoveries = new ArrayList<>();
			if (obj.has("discoveries")) {
				for (JsonElement el : obj.getAsJsonArray("discoveries")) {
					if (discoveries.size() >= MAX_DISCOVERIES) break;
					String d = fit(safeText(el), MAX_DISCOVERY_CHARS);
					if (!d.isEmpty() && !discoveries.contains(d)) discoveries.add(d);
				}
			}

			List<String> skills = new ArrayList<>();
			if (obj.has("skills")) {
				for (JsonElement el : obj.getAsJsonArray("skills")) {
					if (skills.size() >= MAX_SKILL_NAMES) break;
					String s = fit(safeText(el), MAX_PREFERENCE_KEY);
					if (!s.isEmpty() && !skills.contains(s)) skills.add(s);
				}
			}

			memories.put(uuid, new PlayerMemory(str(obj, "nickname", DEFAULT_NICKNAME),
					List.copyOf(history), List.copyOf(prefs), List.copyOf(discoveries),
					fit(str(obj, "objective", ""), MAX_OBJECTIVE_CHARS), List.copyOf(skills)));
		}
	}

	private static Path file() {
		if (fileOverride != null) return fileOverride;
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("cobbie_memory.json");
		} catch (Throwable t) {
			return null; // not running inside the game
		}
	}

	private static String fileOrNone() {
		Path p = file();
		return p == null ? "in-memory" : p.toString();
	}

	private static void badFileAway(Path file) {
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Could not move aside unreadable memory file {}: {}", file, t.toString());
		}
	}

	// ================= helpers =================

	private static String safeText(JsonElement el) {
		try {
			return el.isJsonNull() ? "" : el.getAsString();
		} catch (Throwable t) {
			return "";
		}
	}

	private static String str(JsonObject obj, String field, String fallback) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static String fit(String s, int max) {
		if (s == null) return "";
		String c = s.replaceAll("\\s+", " ").trim();
		return c.length() <= max ? c : c.substring(0, max);
	}

	private static <T> T fail(String what, Throwable t, T fallback) {
		try {
			Cobbie.LOGGER.warn("Cobbie memory {}: {}", what, t.toString());
		} catch (Throwable ignored) {
			// logging must never be the thing that breaks the game
		}
		return fallback;
	}
}
