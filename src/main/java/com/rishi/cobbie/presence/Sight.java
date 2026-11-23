package com.rishi.cobbie.presence;

import com.rishi.cobbie.world.WorldSnapshot;
import com.rishi.cobbie.world.WorldSnapshot.BlockNote;
import com.rishi.cobbie.world.WorldSnapshot.EntityNote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Cobbie can see, in words — the whole of his small talk.
 *
 * <p>He used to narrate his own bookkeeping: which coordinate he was walking to, what number he was
 * standing on. Nobody wants to hear a friend read out a log file. This turns the same
 * {@link WorldSnapshot} the model gets into the thing a person would say: what is around him, what
 * is dangerous, what the weather is doing. No coordinates, ever — a test enforces that.
 *
 * <p>Pure data in, one short line out, so it is unit-testable without a game running.
 */
public final class Sight {

	/** Everything closer than this is "right here" rather than "over there". */
	private static final double CLOSE = 8.0;
	/** A monster this close is worth a different sentence than one at the edge of sight. */
	private static final double IN_YOUR_FACE = 6.0;
	private static final int MAX_CHARS = 120;

	/** Block ids worth remarking on, in the order he would notice them. */
	private static final Map<String, String> LANDMARKS = new LinkedHashMap<>();

	static {
		LANDMARKS.put("lava", "lava, and it is close");
		LANDMARKS.put("water", "water right here");
		LANDMARKS.put("gravel", "loose gravel underfoot");
		LANDMARKS.put("sand", "sand underneath");
		LANDMARKS.put("snow", "snow all around");
		LANDMARKS.put("ice", "ice underfoot");
		LANDMARKS.put("cactus", "a cactus, mind the spines");
		LANDMARKS.put("vine", "vines hanging down");
		LANDMARKS.put("mushroom", "mushrooms in the shade");
		LANDMARKS.put("flowers", "flowers here");
		LANDMARKS.put("torch", "somebody left a torch burning");
		LANDMARKS.put("crafting_table", "a crafting table, if we need one");
		LANDMARKS.put("furnace", "a furnace standing there");
		LANDMARKS.put("chest", "a chest, and it is not mine");
		LANDMARKS.put("cobblestone", "chunks of cobblestone");
		LANDMARKS.put("moss", "mossy stone");
		LANDMARKS.put("clay", "a patch of clay");
		LANDMARKS.put("sugar_cane", "sugar cane by the bank");
		LANDMARKS.put("pumpkin", "a pumpkin");
	}

	/** Monster ids get a human word; anything unrecognised falls back to the id, cleaned up. */
	private static final Map<String, String> CREATURES = new LinkedHashMap<>();

	static {
		CREATURES.put("zombie", "zombie");
		CREATURES.put("skeleton", "skeleton");
		CREATURES.put("creeper", "creeper");
		CREATURES.put("spider", "spider");
		CREATURES.put("enderman", "enderman");
		CREATURES.put("witch", "witch");
		CREATURES.put("slime", "slime");
		CREATURES.put("drowned", "drowned");
		CREATURES.put("husk", "husk");
		CREATURES.put("stray", "stray");
		CREATURES.put("phantom", "phantom");
		CREATURES.put("pillager", "pillager");
		CREATURES.put("zombified_piglin", "zombified piglin");
		CREATURES.put("blaze", "blaze");
		CREATURES.put("ghast", "ghast");
		CREATURES.put("magma_cube", "magma cube");
		CREATURES.put("hoglin", "hoglin");
		CREATURES.put("piglin", "piglin");
		CREATURES.put("silverfish", "silverfish");
		CREATURES.put("cow", "cow");
		CREATURES.put("pig", "pig");
		CREATURES.put("sheep", "sheep");
		CREATURES.put("chicken", "chicken");
		CREATURES.put("horse", "horse");
		CREATURES.put("wolf", "wolf");
		CREATURES.put("cat", "cat");
		CREATURES.put("rabbit", "rabbit");
		CREATURES.put("fox", "fox");
		CREATURES.put("bee", "bee");
		CREATURES.put("goat", "goat");
		CREATURES.put("frog", "frog");
		CREATURES.put("axolotl", "axolotl");
	}

	/** Biome family -> how he would name the place. */
	private static final Map<String, String> PLACES = new LinkedHashMap<>();

	static {
		PLACES.put("spruce", "spruce woods");
		PLACES.put("taiga", "the taiga");
		PLACES.put("birch", "birch woods");
		PLACES.put("dark_forest", "a dark wood");
		PLACES.put("cherry", "cherry blossom");
		PLACES.put("jungle", "the jungle");
		PLACES.put("swamp", "a swamp");
		PLACES.put("mangrove", "the mangroves");
		PLACES.put("desert", "the desert");
		PLACES.put("badlands", "the badlands");
		PLACES.put("savanna", "the savanna");
		PLACES.put("meadow", "open meadow");
		PLACES.put("grove", "a snowy grove");
		PLACES.put("snowy", "somewhere cold and white");
		PLACES.put("frozen", "frozen ground");
		PLACES.put("ice_spikes", "ice spikes");
		PLACES.put("beach", "the shoreline");
		PLACES.put("ocean", "open water");
		PLACES.put("river", "a riverbank");
		PLACES.put("forest", "the woods");
		PLACES.put("plains", "open plains");
		PLACES.put("stony", "bare stony ground");
		PLACES.put("mountain", "the mountains");
		PLACES.put("peaks", "up in the peaks");
		PLACES.put("dripstone", "a dripstone cave");
		PLACES.put("lush", "a lush cave");
		PLACES.put("deep_dark", "the deep dark, and it is too quiet here");
	}

	private Sight() {}

	/** One short line: what is worth knowing about right now. Never coordinates. */
	public static String observe(WorldSnapshot world) {
		if (world == null) return "nothing I can make out from here";
		try {
			String threat = threat(world.nearbyEntities());
			String landmark = landmark(world.nearbyBlocks());
			String place = place(world.biome());
			String sky = sky(world);

			String first = threat != null ? threat : landmark != null ? landmark : place;
			String second = null;
			if (first == null) {
				second = sky;
			} else if (threat != null) {
				second = landmark != null ? landmark : sky != null ? sky : place;
			} else if (landmark != null) {
				second = sky != null ? sky : place;
			} else {
				second = sky;
			}

			String line = join(first, second);
			if (world.health() > 0 && world.maxHealth() > 0
					&& world.health() / world.maxHealth() <= 0.5) {
				line = join(line, "and I am not in good shape");
			}
			return clamp(line == null ? "nothing much around here" : line);
		} catch (Throwable t) {
			return "nothing I can make out from here";
		}
	}

	// ---- the four things he notices ----

	private static String threat(List<EntityNote> entities) {
		if (entities == null) return null;
		Map<String, Integer> counts = new LinkedHashMap<>();
		double nearest = Double.MAX_VALUE;
		for (EntityNote e : entities) {
			if (e == null || !e.hostile() || e.distance() > 24.0) continue;
			String word = creature(e.id());
			if (word == null) continue;
			counts.merge(word, 1, Integer::sum);
			nearest = Math.min(nearest, e.distance());
		}
		if (counts.isEmpty()) return null;
		StringBuilder sb = new StringBuilder();
		int listed = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (listed++ == 2) break;
			if (sb.length() > 0) sb.append(" and ");
			int n = entry.getValue();
			sb.append(n == 1 ? "a " : sizeWord(n) + " ").append(entry.getKey());
			if (n > 1) sb.append('s');
		}
		if (nearest <= IN_YOUR_FACE) return sb.append(" right on top of me").toString();
		if (nearest <= CLOSE) return sb.append(" close by").toString();
		return sb.append(" moving about nearby").toString();
	}

	private static String landmark(List<BlockNote> blocks) {
		if (blocks == null) return null;
		for (Map.Entry<String, String> entry : LANDMARKS.entrySet()) {
			for (BlockNote b : blocks) {
				if (b == null || b.distance() > CLOSE) continue;
				String id = strip(b.id());
				if (id.equals(entry.getKey()) || id.endsWith("_" + entry.getKey())) return entry.getValue();
			}
		}
		// An ore is worth saying out loud; it is the thing a player actually wants to know about.
		for (BlockNote b : blocks) {
			if (b == null || b.distance() > CLOSE) continue;
			String id = strip(b.id());
			if (id.endsWith("_ore")) return "some " + id.replace("_ore", "").replace('_', ' ') + " ore in the rock";
			if (id.endsWith("_log") || id.endsWith("_wood")) {
				return "a " + id.substring(0, id.indexOf('_')) + " tree";
			}
		}
		return null;
	}

	private static String place(String biome) {
		if (biome == null || biome.isBlank() || "?".equals(biome)) return null;
		String id = strip(biome);
		for (Map.Entry<String, String> entry : PLACES.entrySet()) {
			if (id.equals(entry.getKey()) || id.contains(entry.getKey())) return entry.getValue();
		}
		return null;
	}

	private static String sky(WorldSnapshot world) {
		if (world.thundering()) return "there is a storm coming down";
		if (world.raining()) return "rain is coming down";
		long time = Math.floorMod(world.timeOfDay(), 24000L);
		if (time < 11000) return "sun is out";
		if (time < 13500) return "light is going";
		if (time < 22500) return "it is night out";
		return "sun is coming up";
	}

	// ---- words ----

	/** "a zombie" needs a different word from "three zombies". */
	private static String sizeWord(int n) {
		return switch (n) {
			case 2 -> "two";
			case 3 -> "three";
			case 4 -> "four";
			case 5 -> "five";
			default -> n + "";
		};
	}

	private static String creature(String id) {
		String name = strip(id);
		for (Map.Entry<String, String> entry : CREATURES.entrySet()) {
			if (name.equals(entry.getKey())) return entry.getValue();
		}
		return null;
	}

	private static String strip(String id) {
		if (id == null) return "";
		String v = id.toLowerCase(Locale.ROOT);
		return v.startsWith("minecraft:") ? v.substring("minecraft:".length()) : v;
	}

	private static String join(String first, String second) {
		if (first == null || first.isBlank()) return second;
		if (second == null || second.isBlank()) return first;
		return first + ", " + second;
	}

	private static String clamp(String line) {
		String v = line.replaceAll("\\s+", " ").trim();
		if (v.isEmpty()) return "nothing much around here";
		if (v.length() <= MAX_CHARS) return v;
		int cut = v.lastIndexOf(',', MAX_CHARS);
		return v.substring(0, cut > 20 ? cut : MAX_CHARS);
	}

	/** Kept for the tests: the words he is allowed to use about a monster. */
	static List<String> creatureWords() {
		return new ArrayList<>(CREATURES.values());
	}
}
