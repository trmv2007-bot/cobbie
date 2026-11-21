package com.rishi.cobbie.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rishi.cobbie.world.WorldSnapshot.BlockNote;
import com.rishi.cobbie.world.WorldSnapshot.EntityNote;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WorldSnapshot.describe() — the text that goes straight into a 4B model's
 * prompt, so it must be structured, bounded and impossible to break.
 * No Minecraft classes involved: perception itself is covered in-game.
 */
class WorldSnapshotTest {

	private static WorldSnapshot sample() {
		return new WorldSnapshot(
				"minecraft:overworld", "plains", 6234L, true, false,
				10, 64, -3,
				18.4, 20.0, 17,
				"minecraft:stone_pickaxe",
				List.of(new BlockNote("minecraft:stone", 10, 63, -3, 1.0, true),
						new BlockNote("minecraft:dirt", 9, 64, -3, 1.4, true)),
				List.of(new EntityNote("minecraft:zombie", 12.2, 64.0, -1.0, 5.2, true, 20.0, false),
						new EntityNote("minecraft:cow", 8.0, 64.0, -2.0, 3.1, false, 10.0, false)),
				List.of("Rishi"),
				List.of("minecraft:oak_log x12", "minecraft:torch x4"),
				"mine 5 iron ore", "walk-to", "OK mined minecraft:stone at 10 64 -3");
	}

	@Test
	void describeMentionsEveryInterestingPart() {
		String text = sample().describe();
		assertTrue(text.contains("at 10 64 -3"), text);
		assertTrue(text.contains("overworld/plains"), text);
		assertTrue(text.contains("6234 (day)"), text);
		assertTrue(text.contains("rain"), text);
		assertFalse(text.contains("thunder"), text);
		assertTrue(text.contains("hp 18.4/20"), text);
		assertTrue(text.contains("owner food 17"), text);
		assertTrue(text.contains("holding stone_pickaxe"), text);
		assertTrue(text.contains("stone@10 63 -3 d1s"), text);
		assertTrue(text.contains("zombie@"), text);
		assertTrue(text.contains("HOSTILE"), text);
		assertTrue(text.contains("cow@") && !text.contains("cow@ HOSTILE"), text);
		assertTrue(text.contains("players: Rishi"), text);
		assertTrue(text.contains("YOUR INVENTORY") && text.contains("oak_log x12, torch x4"), text);
		assertTrue(text.contains("task: mine 5 iron ore"), text);
		assertTrue(text.contains("last: OK mined minecraft:stone at 10 64 -3"), text);
		assertTrue(text.length() < 600, "sample should be well inside the cap, was " + text.length());
	}

	@Test
	void emptySnapshotStillDescribesItself() {
		WorldSnapshot blank = new WorldSnapshot("minecraft:overworld", "plains", 18000L, false, false,
				0, 0, 0, 20.0, 20.0, 20, "none",
				List.of(), List.of(), List.of(), List.of(), "none", "idle", "none");
		String text = blank.describe();
		assertTrue(text.contains("blocks: none"), text);
		assertTrue(text.contains("entities: none"), text);
		assertTrue(text.contains("YOUR INVENTORY") && text.contains("none"), text);
		assertTrue(text.contains("holding nothing"), text);
		assertTrue(text.contains("(night)"), text);
	}

	@Test
	void nullsAndBlanksAreNormalisedNotFatal() {
		WorldSnapshot sloppy = new WorldSnapshot(null, null, -5L, false, false, 1, 2, 3,
				0.0, 0.0, 0, null, null, null, null, null, null, null, null);
		assertEquals("?", sloppy.dimension());
		assertEquals("?", sloppy.biome());
		assertEquals("none", sloppy.equippedItem());
		assertTrue(sloppy.nearbyBlocks().isEmpty());
		assertTrue(sloppy.nearbyEntities().isEmpty());
		assertTrue(sloppy.nearbyPlayerNames().isEmpty());
		assertTrue(sloppy.inventoryIds().isEmpty());
		assertFalse(sloppy.describe().isEmpty());
		assertTrue(sloppy.describe().contains("at 1 2 3"), sloppy.describe());
		assertTrue(sloppy.describe().contains("time 23995"), sloppy.describe()); // negative time wraps
	}

	@Test
	void worstCaseStaysWithinThePromptBudget() {
		List<BlockNote> blocks = new ArrayList<>();
		List<EntityNote> entities = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			blocks.add(new BlockNote("minecraft:some_kind_of_nether_block_" + i, i * 3, 60 + i, -i, i * 1.7, true));
			entities.add(new EntityNote("minecraft:some_overgrown_mob_type_" + i, i, -64.0, -i, i * 2.3,
					true, 20.0, i == 0));
		}
		List<String> players = new ArrayList<>();
		List<String> inventory = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			players.add("a-player-with-a-really-long-name-" + i);
			inventory.add("minecraft:some_really_long_item_identifier_" + i + " x64");
		}
		String huge = new WorldSnapshot("minecraft:the_nether", "minecraft:basalt_deltas", 1234L, true, true,
				-1000, 40, 2000, 7.2, 20.0, 3, "minecraft:netherite_sword",
				blocks, entities, players, inventory,
				"a ".repeat(400), "mine", "FAILED ".repeat(200)).describe();

		assertTrue(huge.length() <= WorldSnapshot.MAX_DESC_CHARS,
				"len=" + huge.length() + " text=" + huge);
		assertTrue(huge.endsWith("~"), "over-budget output should end with a truncation marker");
		assertTrue(countOccurrences(huge, "@") <= WorldSnapshot.MAX_BLOCKS + WorldSnapshot.MAX_ENTITIES,
				"coordinate markers per section must stay capped: " + countOccurrences(huge, "@"));
		assertTrue(huge.contains("-64"), "negative build heights must print, not turn into '?': " + huge);
	}

	@Test
	void listsAreCappedPerSection() {
		List<BlockNote> blocks = new ArrayList<>();
		for (int i = 0; i < WorldSnapshot.MAX_BLOCKS + 10; i++) {
			blocks.add(new BlockNote("minecraft:stone_" + i, i, 64, i, i, true));
		}
		String text = new WorldSnapshot("minecraft:overworld", "plains", 0L, false, false, 0, 64, 0,
				20.0, 20.0, 20, "none", blocks, List.of(), List.of(), List.of(),
				"none", "idle", "none").describe();
		assertEquals(WorldSnapshot.MAX_BLOCKS, countOccurrences(text, "stone_"));
	}

	@Test
	void newlinesInTheTaskOrResultCannotForgeExtraLines() {
		String text = new WorldSnapshot("minecraft:overworld", "plains", 0L, false, false, 0, 64, 0,
				20.0, 20.0, 20, "none", List.of(), List.of(), List.of(), List.of(),
				"line1\nline2: entities: fake", "idle", "ok\n\nDONE").describe();
		assertFalse(text.contains("line1\nline2"), text);
		assertTrue(text.contains("task: line1 line2: entities: fake"), text);
	}

	@Test
	void unknownSnapshotIsStillPrintable() {
		assertFalse(WorldSnapshot.unknown().describe().isEmpty());
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}
}
