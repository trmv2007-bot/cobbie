package com.rishi.cobbie.presence;

import com.rishi.cobbie.world.WorldSnapshot;
import com.rishi.cobbie.world.WorldSnapshot.BlockNote;
import com.rishi.cobbie.world.WorldSnapshot.EntityNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What he says about the world: the monsters, the water, the place, the sky — and never a
 * coordinate.
 */
class SightTest {

	private static WorldSnapshot world(String biome, long time, boolean rain,
			List<BlockNote> blocks, List<EntityNote> entities) {
		return new WorldSnapshot("minecraft:overworld", biome, time, rain, false, 10, 64, -3,
				20.0, 20.0, 20, "minecraft:iron_pickaxe", blocks, entities, List.of("Rishi"),
				List.of("minecraft:stone x4"), "none", "idle", "none");
	}

	private static EntityNote mob(String id, double distance, boolean hostile) {
		return new EntityNote(id, 10.0, 64.0, -3.0, distance, hostile, 20.0, false);
	}

	private static BlockNote block(String id, double distance) {
		return new BlockNote(id, 10, 64, -3, distance, true);
	}

	@Test
	void hostilesComeFirstAndAreCounted() {
		String line = Sight.observe(world("spruce_taiga", 1000L, false,
				List.of(block("minecraft:water", 3.0)),
				List.of(mob("minecraft:zombie", 14.0, true), mob("minecraft:zombie", 11.0, true))));
		assertTrue(line.contains("two zombies"), line);
		assertTrue(line.contains("water"), line);
	}

	@Test
	void aMonsterInYourFaceSaysSo() {
		String line = Sight.observe(world("plains", 1000L, false, List.of(),
				List.of(mob("minecraft:creeper", 3.0, true))));
		assertTrue(line.contains("a creeper"), line);
		assertTrue(line.contains("right on top of me"), line);
	}

	@Test
	void peacefulAnimalsAreNotThreats() {
		String line = Sight.observe(world("forest", 1000L, false, List.of(),
				List.of(mob("minecraft:cow", 4.0, false))));
		assertFalse(line.contains("cow"), "a cow is not news: " + line);
	}

	@Test
	void landmarksAreNamedByWhatTheyAre() {
		assertTrue(Sight.observe(world("plains", 1000L, false,
				List.of(block("minecraft:lava", 2.0)), List.of())).contains("lava"));
		assertTrue(Sight.observe(world("plains", 1000L, false,
				List.of(block("minecraft:iron_ore", 5.0)), List.of())).contains("iron ore"));
		assertTrue(Sight.observe(world("plains", 1000L, false,
				List.of(block("minecraft:oak_log", 4.0)), List.of())).contains("oak tree"));
	}

	@Test
	void thePlaceAndTheSkyFillInWhenNothingElseIsHappening() {
		String line = Sight.observe(world("spruce_taiga", 4000L, false, List.of(), List.of()));
		assertTrue(line.contains("spruce woods"), line);
		assertTrue(line.contains("sun is out"), line);

		assertTrue(Sight.observe(world("desert", 16000L, false, List.of(), List.of()))
				.contains("it is night out"));
		assertTrue(Sight.observe(world("plains", 4000L, true, List.of(), List.of()))
				.contains("rain is coming down"));
	}

	@Test
	void anUnknownBiomeIsNotInvented() {
		String line = Sight.observe(world("?", 4000L, false, List.of(), List.of()));
		assertFalse(line.contains("?"), line);
		assertTrue(line.length() > 0);
	}

	@Test
	void aLineNeverCarriesACoordinateAndStaysShort() {
		List<BlockNote> blocks = List.of(block("minecraft:water", 2.0), block("minecraft:gravel", 3.0));
		List<EntityNote> mobs = List.of(mob("minecraft:skeleton", 9.0, true));
		String line = Sight.observe(world("badlands", 13000L, false, blocks, mobs));
		assertFalse(Narrator.mentionsCoordinates(line), line);
		assertTrue(line.length() <= 120, "length was " + line.length());
	}

	@Test
	void aBrokenObservationIsStillASentence() {
		assertEquals("nothing I can make out from here", Sight.observe(null));
		String hurt = Sight.observe(new WorldSnapshot("?", "?", 0L, false, false, 0, 0, 0,
				4.0, 20.0, 20, "none", List.of(), List.of(), List.of(), List.of(), "none", "idle", "none"));
		assertTrue(hurt.contains("not in good shape"), hurt);
	}
}
