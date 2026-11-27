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
import java.util.Set;
import java.util.UUID;

/**
 * What he knows about the world and the owner's wishes, and how much of it to put in front of a small
 * model at once. Stored per owner in {@code config/cobbie_memories.json}.
 *
 * <h2>Five kinds, deliberately not one string bag</h2>
 * A {@link Kind#PLACE} is a name and coordinates, a {@link Kind#CONTAINER} is a container and what is in
 * it, a {@link Kind#BLOCK} is a durable fact about a block or a station (this anvil is chipped), an
 * {@link Kind#OUTCOME} is what a job did and why it stopped, and a {@link Kind#PREFERENCE} is something
 * the owner said he wanted. The kind decides how the entry is checked later: the first three are read
 * back off the world, and the last two are not facts about the world at all — one is history, the other
 * is the owner's word — so no world read can confirm or contradict them.
 *
 * <h2>Retrieval is scored, not dumped</h2>
 * An 8B model with forty irrelevant lines is worse than one with three good ones, so
 * {@link #rank} scores every entry against the instruction and the situation and
 * {@link #render} fits the best of them into {@link #MAX_PROMPT_CHARS}:
 *
 * <pre>
 * score = 3 x shared words (capped at 3)
 *       + 2 x the instruction's own verb points at this kind
 *       + 2 x the entry is in the dimension he is standing in
 *       + 1 x the entry is within 48 blocks of him
 * </pre>
 *
 * A tie goes to the entry learned more recently. An entry with none of those — no shared word, no
 * kind affinity, not nearby — is not shown at all, because remembering something irrelevant is what
 * makes the model worse rather than better.
 *
 * <h2>Bounded, with a stated eviction</h2>
 * {@link #MAX_ENTRIES} per owner. When the next write would pass it, the <b>oldest entry he learned
 * from the world</b> goes first, and what the owner said is dropped only when nothing else is left —
 * a companion should forget where a chest was long before he forgets what you told him you wanted.
 *
 * <p>No Minecraft types here: the whole class is testable against a temp file, and the half that reads
 * blocks lives in {@link WorldMemory}.
 */
public final class OperationalMemory {

	/** The few kinds of thing worth keeping. A kind is also the rule for how it may be checked. */
	public enum Kind {
		/** A name and coordinates: home, the mine mouth, the roof. */
		PLACE("place"),
		/** A container and what it holds. */
		CONTAINER("container"),
		/** A durable fact about a block or a station, e.g. an anvil that is chipped. */
		BLOCK("block"),
		/** What a job did, and why it stopped if it did not finish. */
		OUTCOME("job"),
		/** Something the owner said he wanted. */
		PREFERENCE("wants");

		private final String word;

		Kind(String word) {
			this.word = word;
		}

		public String word() {
			return word;
		}
	}

	/** How he came to know it — the column {@code /cobbie memory} shows and the prompt names. */
	public enum Source {
		OWNER("you told me"),
		SEEN("I saw it"),
		DONE("from my own work");

		private final String phrase;

		Source(String phrase) {
			this.phrase = phrase;
		}

		public String phrase() {
			return phrase;
		}
	}

	/**
	 * One memory. {@code at} says whether x/y/z mean anything: an outcome and a preference are not
	 * situated, and a coordinate of 0 0 0 is a real place, so the flag cannot be inferred from the numbers.
	 */
	public record Entry(Kind kind, String subject, String fact, String dimension,
			int x, int y, int z, boolean at, Source source, long seq) {

		/** Two memories about the same thing: a place or a wish by name, a container or a block by square. */
		public String key() {
			String where = switch (kind) {
				case CONTAINER, BLOCK -> dimension + "@" + x + "," + y + "," + z;
				default -> subject.toLowerCase(Locale.ROOT);
			};
			return kind.name() + "|" + where;
		}
	}

	/** One ranked candidate, with the score that put it there so a test can check the arithmetic. */
	public record Scored(Entry entry, int score) {}

	public static final int MAX_ENTRIES = 24;
	public static final int MAX_SUBJECT_CHARS = 40;
	public static final int MAX_FACT_CHARS = 120;
	/** The prompt budget for remembered lines. About 100 tokens at four characters each. */
	public static final int MAX_PROMPT_CHARS = 400;
	public static final int MAX_PROMPT_LINES = 5;
	public static final int NEAR_BLOCKS = 48;

	private static final int LEXICAL_POINTS = 3;
	private static final int AFFINITY_POINTS = 2;
	private static final int DIMENSION_POINTS = 2;
	private static final int NEAR_POINTS = 1;
	private static final int LEXICAL_CAP = 3;

	/** Words too common to mean anything about relevance. Deliberately about grammar, not about topics. */
	private static final Set<String> STOP = Set.of(
			"the", "and", "for", "with", "you", "your", "yours", "that", "this", "these", "those", "there",
			"here", "what", "where", "when", "which", "from", "into", "onto", "out", "are", "was", "were",
			"can", "could", "would", "should", "will", "shall", "have", "has", "had", "not", "but", "all",
			"any", "some", "one", "two", "its", "his", "her", "him", "she", "they", "them", "our", "ours",
			"about", "than", "then", "just", "now", "please", "cobbie", "get", "got", "let", "make");

	/**
	 * What each kind of instruction is usually about. One point per kind at most, so a sentence that
	 * mentions a chest and a house does not bury everything else.
	 */
	private static final Map<Kind, List<String>> AFFINITY = Map.of(
			Kind.PLACE, List.of("go", "walk", "home", "back", "return", "place", "meet", "roof", "base"),
			Kind.CONTAINER, List.of("put", "take", "store", "stow", "fetch", "stash", "chest", "barrel", "shulker",
					"hopper", "withdraw", "deposit", "container", "furnace"),
			Kind.BLOCK, List.of("anvil", "station", "enchant", "smith", "grind", "brew", "chipped",
					"damaged", "broken", "repair"),
			Kind.OUTCOME, List.of("why", "failed", "last", "again", "worked", "try", "tried", "before",
					"earlier", "wrong"),
			Kind.PREFERENCE, List.of("want", "like", "always", "never", "prefer", "should"));

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();

	/** One owner's page: his memories in the order they were learned, and the next sequence number. */
	private static final class Page {
		final List<Entry> entries = new ArrayList<>();
		long next = 1;
	}

	private static final Map<UUID, Page> PAGES = new LinkedHashMap<>();
	private static boolean loaded = false;
	private static boolean dirty = false;
	private static Path fileOverride = null;

	private OperationalMemory() {}

	// ================= reading and writing =================

	/** Every memory for this owner, oldest first. The order is what {@code /cobbie memory} numbers. */
	public static List<Entry> entries(UUID owner) {
		try {
			if (owner == null) return List.of();
			synchronized (LOCK) {
				ensureLoaded();
				Page page = PAGES.get(owner);
				return page == null ? List.of() : List.copyOf(page.entries);
			}
		} catch (Throwable t) {
			return fail("entries", t, List.of());
		}
	}

	public static int size(UUID owner) {
		return entries(owner).size();
	}

	/**
	 * Record or overwrite one memory. A second statement about the same thing replaces the first: a
	 * correction must not leave the wrong version behind it in the list.
	 */
	public static Entry remember(UUID owner, Kind kind, String subject, String fact, String dimension,
			int x, int y, int z, boolean at, Source source) {
		if (owner == null || kind == null || source == null) return null;
		String subj = normaliseSubject(subject);
		if (subj.isEmpty()) return null;
		String f = fit(fact, MAX_FACT_CHARS);
		String dim = dimension == null ? "" : dimension;
		Entry fresh = new Entry(kind, subj, f, dim, x, y, z, at, source, 0);
		try {
			synchronized (LOCK) {
				ensureLoaded();
				Page page = PAGES.computeIfAbsent(owner, id -> new Page());
				Entry existing = null;
				for (Entry e : page.entries) {
					if (e.key().equals(fresh.key())) {
						existing = e;
						break;
					}
				}
				if (existing != null && sameThing(existing, fresh)) return existing; // no rewrite
				if (existing != null) page.entries.remove(existing);
				Entry stored = new Entry(kind, subj, f, dim, x, y, z, at, source, page.next++);
				page.entries.add(stored);
				evict(page);
				touch();
				// The cap can take the entry that was just written when there is nothing older to spend,
				// and a caller told "stored" about a row that is not there would be a small lie.
				return page.entries.contains(stored) ? stored : null;
			}
		} catch (Throwable t) {
			return fail("remember", t, null);
		}
	}

	/** Drop the memory at this 1-based position in {@link #entries}, as {@code /cobbie forget <n>} counts. */
	public static boolean forget(UUID owner, int index) {
		try {
			if (owner == null) return false;
			synchronized (LOCK) {
				ensureLoaded();
				Page page = PAGES.get(owner);
				if (page == null || index < 1 || index > page.entries.size()) return false;
				page.entries.remove(index - 1);
				touch();
				return true;
			}
		} catch (Throwable t) {
			return fail("forget", t, false);
		}
	}

	/** Drop exactly this entry — how the world retires something it has contradicted. */
	static boolean forget(UUID owner, Entry entry) {
		if (owner == null || entry == null) return false;
		try {
			synchronized (LOCK) {
				ensureLoaded();
				Page page = PAGES.get(owner);
				if (page == null) return false;
				boolean removed = page.entries.removeIf(e -> e.key().equals(entry.key()));
				if (removed) touch();
				return removed;
			}
		} catch (Throwable t) {
			return fail("forget", t, false);
		}
	}

	public static void clear(UUID owner) {
		try {
			if (owner == null) return;
			synchronized (LOCK) {
				ensureLoaded();
				Page page = PAGES.get(owner);
				if (page == null || page.entries.isEmpty()) return;
				page.entries.clear();
				touch();
			}
		} catch (Throwable t) {
			fail("clear", t, null);
		}
	}

	// ================= retrieval =================

	/**
	 * The entries that bear on this instruction and this situation, best first. {@code dimension} and
	 * the position are the situation: a memory from the nether is not much use while he is in the
	 * overworld, and one at his feet is worth more than one across the map.
	 *
	 * @param query      the owner's words, plus whatever he is already doing
	 * @param dimension  where he is standing, or null when unknown
	 */
	public static List<Scored> rank(UUID owner, String query, String dimension, int x, int y, int z, int limit) {
		try {
			Set<String> asked = terms(query);
			List<Scored> scored = new ArrayList<>();
			for (Entry e : entries(owner)) {
				int shared = Math.min(LEXICAL_CAP, overlap(asked, terms(e.subject() + " " + e.fact())));
				int affinity = affinity(query, e.kind()) ? 1 : 0;
				boolean same = dimension != null && dimension.equals(e.dimension());
				boolean near = same && e.at() && within(e, x, y, z, NEAR_BLOCKS);
				if (shared == 0 && affinity == 0 && !near) continue;   // nothing about this bears on it
				int score = LEXICAL_POINTS * shared + AFFINITY_POINTS * affinity
						+ (same ? DIMENSION_POINTS : 0) + (near ? NEAR_POINTS : 0);
				scored.add(new Scored(e, score));
			}
			scored.sort((a, b) -> {
				if (a.score() != b.score()) return Integer.compare(b.score(), a.score());
				return Long.compare(b.entry().seq(), a.entry().seq());   // newest first on a tie
			});
			return limit > 0 && scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
		} catch (Throwable t) {
			return fail("rank", t, List.of());
		}
	}

	/** Prompt lines for these entries, within {@link #MAX_PROMPT_CHARS}. "" when there are none. */
	public static String render(List<Entry> shown) {
		if (shown == null || shown.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		int lines = 0;
		for (Entry e : shown) {
			String line = line(e);
			if (lines >= MAX_PROMPT_LINES) break;
			if (sb.length() > 0 && sb.length() + 1 + line.length() > MAX_PROMPT_CHARS) break;
			if (sb.length() > 0) sb.append('\n');
			sb.append(line);
			lines++;
		}
		return sb.toString();
	}

	/** One line as the model reads it: what, where, and how he came to know it. */
	static String line(Entry e) {
		StringBuilder sb = new StringBuilder("- ").append(e.subject());
		if (!e.fact().isBlank()) sb.append(": ").append(e.fact());
		if (e.at()) {
			sb.append(" [").append(e.x()).append(' ').append(e.y()).append(' ').append(e.z())
					.append(' ').append(shortDimension(e.dimension())).append(']');
		}
		return sb.append(" (").append(e.source().phrase()).append(')').toString();
	}

	/** The owner-facing listing: numbered so {@code /cobbie forget <n>} addresses the same rows. "" when empty. */
	public static String listing(UUID owner) {
		List<Entry> all = entries(owner);
		if (all.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < all.size(); i++) {
			Entry e = all.get(i);
			sb.append(i + 1).append(". ").append(e.subject()).append(" (").append(e.kind().word()).append(')');
			if (!e.fact().isBlank()) sb.append(": ").append(e.fact());
			if (e.at()) {
				sb.append(" at ").append(e.x()).append(' ').append(e.y()).append(' ').append(e.z())
						.append(" in ").append(shortDimension(e.dimension()));
			}
			sb.append(" — ").append(e.source().phrase());
			if (i < all.size() - 1) sb.append('\n');
		}
		return sb.toString();
	}

	// ================= scoring helpers =================

	static Set<String> terms(String text) {
		if (text == null || text.isBlank()) return Set.of();
		Set<String> out = new java.util.LinkedHashSet<>();
		for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (raw.length() < 3) continue;
			if (raw.chars().allMatch(Character::isDigit)) continue;   // a coordinate is not a topic
			if (STOP.contains(raw)) continue;
			out.add(raw);
		}
		return out;
	}

	private static int overlap(Set<String> asked, Set<String> stored) {
		int n = 0;
		for (String word : asked) {
			if (stored.contains(word)) n++;
		}
		return n;
	}

	private static boolean affinity(String query, Kind kind) {
		if (query == null || query.isBlank()) return false;
		for (String word : terms(query)) {
			if (AFFINITY.getOrDefault(kind, List.of()).contains(word)) return true;
		}
		return false;
	}

	private static boolean within(Entry e, int x, int y, int z, int blocks) {
		long dx = (long) e.x() - x;
		long dy = (long) e.y() - y;
		long dz = (long) e.z() - z;
		return dx * dx + dy * dy + dz * dz <= (long) blocks * blocks;
	}

	// ================= persistence =================

	/** Point the store at a specific file (tests use a temp path; null restores the game config). */
	public static void setFileForTesting(Path file) {
		synchronized (LOCK) {
			fileOverride = file;
			loaded = false;
			dirty = false;
			PAGES.clear();
		}
	}

	/** Write to disk if anything changed. Cheap to call often, thread-safe, never throws. */
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

	/** Caller holds LOCK. */
	private static void touch() {
		dirty = true;
		saveLocked();
	}

	/** Caller holds LOCK. No-op while clean; temp file + ATOMIC_MOVE when not. */
	private static void saveLocked() {
		if (!dirty) return;
		Path file = file();
		if (file == null) {   // no game and no test path: memory only
			dirty = false;
			return;
		}
		try {
			JsonObject players = new JsonObject();
			for (Map.Entry<UUID, Page> owner : PAGES.entrySet()) {
				JsonObject page = new JsonObject();
				page.addProperty("next", owner.getValue().next);
				JsonArray list = new JsonArray();
				for (Entry e : owner.getValue().entries) {
					JsonObject o = new JsonObject();
					o.addProperty("kind", e.kind().name());
					o.addProperty("subject", e.subject());
					o.addProperty("fact", e.fact());
					o.addProperty("dim", e.dimension());
					o.addProperty("x", e.x());
					o.addProperty("y", e.y());
					o.addProperty("z", e.z());
					o.addProperty("at", e.at());
					o.addProperty("source", e.source().name());
					o.addProperty("seq", e.seq());
					list.add(o);
				}
				page.add("entries", list);
				players.add(owner.getKey().toString(), page);
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
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;   // only once the bytes are actually in place
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Failed to save Cobbie's memories: {}", t.toString());
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
			Cobbie.LOGGER.warn("Cobbie's memory file is unreadable ({}); starting fresh", t.toString());
			PAGES.clear();
			badFileAway(file);
		}
	}

	private static void read(JsonObject root) {
		PAGES.clear();
		if (!root.has("players") || !root.get("players").isJsonObject()) return;
		for (Map.Entry<String, JsonElement> owner : root.getAsJsonObject("players").entrySet()) {
			if (!owner.getValue().isJsonObject()) continue;
			UUID uuid;
			try {
				uuid = UUID.fromString(owner.getKey());
			} catch (Exception badKey) {
				continue;
			}
			JsonObject page = owner.getValue().getAsJsonObject();
			Page loadedPage = new Page();
			loadedPage.next = Math.max(1, num(page, "next", 1));
			long highest = 0;
			if (page.has("entries") && page.get("entries").isJsonArray()) {
				for (JsonElement el : page.getAsJsonArray("entries")) {
					if (!el.isJsonObject()) continue;
					JsonObject o = el.getAsJsonObject();
					Kind kind = kindOf(str(o, "kind", ""));
					Source source = sourceOf(str(o, "source", ""));
					String subject = normaliseSubject(str(o, "subject", ""));
					if (kind == null || source == null || subject.isEmpty()) continue;
					if (loadedPage.entries.size() >= MAX_ENTRIES) break;
					long seq = num(o, "seq", (int) loadedPage.next);
					Entry e = new Entry(kind, subject, fit(str(o, "fact", ""), MAX_FACT_CHARS),
							str(o, "dim", ""), num(o, "x", 0), num(o, "y", 0), num(o, "z", 0),
							flag(o, "at"), source, seq);
					loadedPage.entries.add(e);
					highest = Math.max(highest, seq);
				}
			}
			loadedPage.next = Math.max(loadedPage.next, highest + 1);
			if (!loadedPage.entries.isEmpty()) PAGES.put(uuid, loadedPage);
		}
	}

	private static Path file() {
		if (fileOverride != null) return fileOverride;
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("cobbie_memories.json");
		} catch (Throwable t) {
			return null;   // not running inside the game
		}
	}

	private static void badFileAway(Path file) {
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Could not move aside unreadable memory file {}: {}", file, t.toString());
		}
	}

	// ================= small helpers =================

	/**
	 * The cap, and the eviction rule with it. The oldest thing he learned from the world goes first; an
	 * owner's words are the last thing to go, because a companion forgetting what you asked for is the
	 * failure this store exists to prevent.
	 */
	private static void evict(Page page) {
		while (page.entries.size() > MAX_ENTRIES) {
			Entry victim = null;
			for (Entry e : page.entries) {
				if (e.source() != Source.OWNER) {
					victim = e;
					break;
				}
			}
			page.entries.remove(victim != null ? victim : page.entries.get(0));
		}
	}

	private static boolean sameThing(Entry a, Entry b) {
		return a.kind() == b.kind() && a.subject().equals(b.subject()) && a.fact().equals(b.fact())
				&& a.dimension().equals(b.dimension()) && a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
				&& a.at() == b.at() && a.source() == b.source();
	}

	/** Lowercase, without a leading article, so "the iron" and "iron" are one memory. */
	static String normaliseSubject(String subject) {
		String s = subject == null ? "" : subject.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
		for (String article : List.of("the ", "my ", "our ", "a ", "an ")) {
			if (s.startsWith(article)) {
				s = s.substring(article.length()).trim();
				break;
			}
		}
		return fit(s, MAX_SUBJECT_CHARS);
	}

	private static String fit(String s, int max) {
		if (s == null) return "";
		String c = s.replaceAll("\\s+", " ").trim();
		return c.length() <= max ? c : c.substring(0, max);
	}

	private static String shortDimension(String dimension) {
		if (dimension == null || dimension.isBlank()) return "?";
		int colon = dimension.indexOf(':');
		return colon < 0 ? dimension : dimension.substring(colon + 1);
	}

	private static Kind kindOf(String name) {
		for (Kind k : Kind.values()) {
			if (k.name().equalsIgnoreCase(name)) return k;
		}
		return null;
	}

	private static Source sourceOf(String name) {
		for (Source s : Source.values()) {
			if (s.name().equalsIgnoreCase(name)) return s;
		}
		return null;
	}

	private static String str(JsonObject obj, String field, String fallback) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static int num(JsonObject obj, String field, int fallback) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsInt() : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static boolean flag(JsonObject obj, String field) {
		try {
			return obj.has(field) && !obj.get(field).isJsonNull() && obj.get(field).getAsBoolean();
		} catch (Throwable t) {
			return false;
		}
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
