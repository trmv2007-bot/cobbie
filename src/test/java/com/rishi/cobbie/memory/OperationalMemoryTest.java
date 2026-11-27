package com.rishi.cobbie.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rishi.cobbie.memory.OperationalMemory.Entry;
import com.rishi.cobbie.memory.OperationalMemory.Kind;
import com.rishi.cobbie.memory.OperationalMemory.Scored;
import com.rishi.cobbie.memory.OperationalMemory.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operational store against a temp file: what is written, what survives a reload, what comes back
 * for a question, and what goes when the cap is reached. No Minecraft runtime anywhere.
 */
class OperationalMemoryTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@TempDir
	Path dir;

	private Path file;
	private UUID owner;

	@BeforeEach
	void setUp() {
		file = dir.resolve("cobbie_memories.json");
		OperationalMemory.setFileForTesting(file);
		owner = UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		OperationalMemory.setFileForTesting(null);
	}

	private Entry place(String subject, String fact, int x, int y, int z) {
		return OperationalMemory.remember(owner, Kind.PLACE, subject, fact, OVERWORLD, x, y, z, true, Source.OWNER);
	}

	@Test
	void unknownOwnerKnowsNothing() {
		assertTrue(OperationalMemory.entries(owner).isEmpty());
		assertEquals("", OperationalMemory.listing(owner));
		assertEquals("", OperationalMemory.render(OperationalMemory.entries(owner)));
		assertFalse(OperationalMemory.forget(owner, 1));
	}

	@Test
	void everyKindRoundTripsThroughTheFileWithItsCoordinates() {
		OperationalMemory.remember(owner, Kind.PLACE, "home", "my bed is there", OVERWORLD, 12, 64, -7, true, Source.OWNER);
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 24 iron_ingot", OVERWORLD, 20, 64, -3, true, Source.SEEN);
		OperationalMemory.remember(owner, Kind.BLOCK, "anvil", "chipped", OVERWORLD, 25, 64, 0, true, Source.SEEN);
		OperationalMemory.remember(owner, Kind.OUTCOME, "reach the roof", "failed — no route", OVERWORLD, 0, 0, 0, false, Source.DONE);
		OperationalMemory.remember(owner, Kind.PREFERENCE, "torches", "always light the tunnel first", OVERWORLD, 0, 0, 0, false, Source.OWNER);
		assertTrue(Files.exists(file), "the store writes as it goes");

		OperationalMemory.setFileForTesting(file); // drop the cache, read it back
		List<Entry> back = OperationalMemory.entries(owner);
		assertEquals(5, back.size(), back.toString());

		Entry home = back.stream().filter(e -> e.subject().equals("home")).findFirst().orElseThrow();
		assertEquals(Kind.PLACE, home.kind());
		assertEquals(Source.OWNER, home.source());
		assertEquals(12, home.x());
		assertEquals(64, home.y());
		assertEquals(-7, home.z());
		assertTrue(home.at());
		assertEquals(OVERWORLD, home.dimension());

		Entry chest = back.stream().filter(e -> e.subject().equals("chest")).findFirst().orElseThrow();
		assertEquals(Kind.CONTAINER, chest.kind());
		assertEquals("holds 24 iron_ingot", chest.fact());
		assertEquals(20, chest.x());

		Entry outcome = back.stream().filter(e -> e.kind() == Kind.OUTCOME).findFirst().orElseThrow();
		assertFalse(outcome.at(), "a job is not a place");
		assertTrue(outcome.fact().contains("no route"), outcome.fact());
		assertEquals(Source.DONE, outcome.source());
	}

	@Test
	void theOwnersCorrectionOverwritesInsteadOfStacking() {
		place("home", "the first one", 12, 64, -7);
		place("home", "the new one", 3, 70, 9);
		List<Entry> all = OperationalMemory.entries(owner);
		assertEquals(1, all.size(), "a corrected memory is one memory: " + all);
		assertEquals(3, all.get(0).x());
		assertEquals(9, all.get(0).z());
		assertEquals("the new one", all.get(0).fact());
	}

	@Test
	void subjectsAreNormalisedSoTheSameThingIsOneMemory() {
		OperationalMemory.remember(owner, Kind.PREFERENCE, "The Iron", "in the barrel", OVERWORLD, 0, 0, 0, false, Source.OWNER);
		OperationalMemory.remember(owner, Kind.PREFERENCE, "iron", "in the chest now", OVERWORLD, 0, 0, 0, false, Source.OWNER);
		assertEquals(1, OperationalMemory.entries(owner).size());
		assertEquals("iron", OperationalMemory.entries(owner).get(0).subject());
	}

	@Test
	void anUnchangedWriteDoesNotTouchTheFile() throws Exception {
		place("home", "my bed is there", 1, 64, 1);
		Files.writeString(file, "CANARY");
		place("home", "my bed is there", 1, 64, 1);
		assertEquals("CANARY", Files.readString(file), "writing the same thing again must not rewrite the file");
	}

	@Test
	void theCapEvictsTheOldestThingLearnedFromTheWorldFirst() {
		OperationalMemory.remember(owner, Kind.PREFERENCE, "torches", "light the tunnel first", OVERWORLD, 0, 0, 0, false, Source.OWNER);
		for (int i = 0; i < OperationalMemory.MAX_ENTRIES; i++) {
			OperationalMemory.remember(owner, Kind.CONTAINER, "chest " + i, "holds " + i + " stone",
					OVERWORLD, i, 64, 0, true, Source.SEEN);
		}
		List<Entry> all = OperationalMemory.entries(owner);
		assertEquals(OperationalMemory.MAX_ENTRIES, all.size());
		assertTrue(all.stream().anyMatch(e -> e.subject().equals("torches")), "the owner's word is not the first to go");
		assertFalse(all.stream().anyMatch(e -> e.subject().equals("chest 0")), "the oldest world memory went: " + all);
		assertTrue(all.stream().anyMatch(e -> e.subject().equals("chest 1")));
	}

	@Test
	void aBookOfNothingButTheOwnersWordsStillKeepsThem() {
		for (int i = 0; i < OperationalMemory.MAX_ENTRIES; i++) {
			OperationalMemory.remember(owner, Kind.PREFERENCE, "wish " + i, "what I want", OVERWORLD, 0, 0, 0, false, Source.OWNER);
		}
		// Nothing world-learned is left to spend, so the newest arrival is the one that goes.
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 1 stone", OVERWORLD, 4, 64, 4, true, Source.SEEN);
		List<Entry> all = OperationalMemory.entries(owner);
		assertEquals(OperationalMemory.MAX_ENTRIES, all.size());
		assertTrue(all.stream().allMatch(e -> e.source() == Source.OWNER), all.toString());
	}

	@Test
	void retrievalPicksTheThreeThatBearOnTheQuestionOutOfTen() {
		place("home", "my bed is there", 12, 64, -7);                                            // 1
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 24 iron_ingot, 3 torch",
				OVERWORLD, 20, 64, -3, true, Source.SEEN);                                       // 2
		OperationalMemory.remember(owner, Kind.BLOCK, "anvil", "chipped", OVERWORLD,
				25, 64, 0, true, Source.SEEN);                                                   // 3
		place("boat", "by the lake", 300, 63, 300);                                              // 4
		OperationalMemory.remember(owner, Kind.OUTCOME, "reach the roof", "failed — no route: the ladder is missing",
				OVERWORLD, 0, 0, 0, false, Source.DONE);                                         // 5
		OperationalMemory.remember(owner, Kind.PREFERENCE, "torches", "always light the tunnel first",
				OVERWORLD, 0, 0, 0, false, Source.OWNER);                                        // 6
		place("gravel pit", "lots of gravel", -400, 60, 40);                                     // 7
		OperationalMemory.remember(owner, Kind.PLACE, "nether portal", "the obsidian frame",
				NETHER, 5, 64, 5, true, Source.OWNER);                                           // 8
		OperationalMemory.remember(owner, Kind.CONTAINER, "barrel", "holds 5 sand",
				OVERWORLD, -400, 64, 0, true, Source.SEEN);                                      // 9
		OperationalMemory.remember(owner, Kind.OUTCOME, "mine stone", "worked, 12 step(s)",
				OVERWORLD, 0, 0, 0, false, Source.DONE);                                         // 10

		List<Scored> ranked = OperationalMemory.rank(owner, "put the 24 iron from my bag into the chest",
				OVERWORLD, 0, 64, 0, 3);

		StringBuilder table = new StringBuilder("rank for \"put the iron into the chest\":\n");
		for (Scored s : ranked) {
			table.append("  ").append(s.score()).append("  ").append(s.entry().subject()).append('\n');
		}
		System.out.print(table);

		assertEquals(3, ranked.size(), "a budget of three means three");
		assertEquals("chest", ranked.get(0).entry().subject(), "the chest is the answer: " + ranked);
		assertEquals(20, ranked.get(0).entry().x(), "and it is the chest that was stored");
		// 2 shared words (chest, iron) x 3, + 2 for a container instruction, + 2 same dimension, + 1 nearby.
		assertEquals(11, ranked.get(0).score());
		assertEquals("barrel", ranked.get(1).entry().subject(), "the other container comes second on the verb alone");
		// No shared word with the question at all: 2 for a container instruction and 2 for the dimension.
		assertEquals(4, ranked.get(1).score());
		List<String> subjects = ranked.stream().map(s -> s.entry().subject()).toList();
		assertFalse(subjects.contains("boat"), "a boat across the map does not bear on this: " + subjects);
		assertFalse(subjects.contains("reach the roof"), subjects.toString());
		assertFalse(subjects.contains("nether portal"), "a portal in another dimension does not bear on this either");
	}

	@Test
	void aMemoryInTheDimensionHeStandsInOutranksTheSameMemoryElsewhere() {
		// Two containers with the same name and the same contents, in two dimensions: the only thing
		// that can separate them is where he is standing.
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 5 iron_ingot", NETHER, 5, 64, 5, true, Source.SEEN);
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 5 iron_ingot", OVERWORLD, 900, 64, 900, true, Source.SEEN);
		List<Scored> inOverworld = OperationalMemory.rank(owner, "the chest with the iron", OVERWORLD, 0, 64, 0, 1);
		assertEquals(1, inOverworld.size());
		assertEquals(OVERWORLD, inOverworld.get(0).entry().dimension(), inOverworld.toString());
		List<Scored> inNether = OperationalMemory.rank(owner, "the chest with the iron", NETHER, 0, 64, 0, 1);
		assertEquals(NETHER, inNether.get(0).entry().dimension(), inNether.toString());
	}

	@Test
	void nothingIrrelevantIsHandedToTheModel() {
		place("boat", "by the lake", 300, 63, 300);
		OperationalMemory.remember(owner, Kind.PREFERENCE, "torches", "light the tunnel first",
				OVERWORLD, 0, 0, 0, false, Source.OWNER);
		// No shared word, no verb pointing at a kind, and nothing of his is nearby: nothing goes in.
		assertTrue(OperationalMemory.rank(owner, "what is the weather today", OVERWORLD, 800, 64, 800, 5).isEmpty());
	}

	@Test
	void anInstructionAboutContainersSurfacesAContainerItNeverNamed() {
		OperationalMemory.remember(owner, Kind.CONTAINER, "barrel", "holds 5 sand",
				OVERWORLD, 40, 64, 0, true, Source.SEEN);
		OperationalMemory.remember(owner, Kind.PLACE, "boat", "by the lake",
				OVERWORLD, 300, 63, 300, true, Source.OWNER);
		List<Scored> ranked = OperationalMemory.rank(owner, "stow this away somewhere", OVERWORLD, 0, 64, 0, 3);
		assertEquals(1, ranked.size(), "only the container bears on that: " + ranked);
		assertEquals("barrel", ranked.get(0).entry().subject());
		assertEquals(5, ranked.get(0).score(), "2 for the verb, 2 for the dimension, 1 for being nearby");
	}

	@Test
	void thePromptFitsItsStatedBudget() {
		for (int i = 0; i < OperationalMemory.MAX_ENTRIES; i++) {
			OperationalMemory.remember(owner, Kind.CONTAINER, "chest number " + i,
					"holds 64 stone, 32 cobblestone, 16 torch and 4 more things of one kind or another",
					OVERWORLD, i, 64, 0, true, Source.SEEN);
		}
		List<Scored> ranked = OperationalMemory.rank(owner, "chest stone", OVERWORLD, 0, 64, 0, OperationalMemory.MAX_PROMPT_LINES * 3);
		String block = OperationalMemory.render(ranked.stream().map(Scored::entry).toList());
		assertTrue(block.length() <= OperationalMemory.MAX_PROMPT_CHARS, "over the char budget: " + block.length());
		assertTrue(block.lines().toList().size() <= OperationalMemory.MAX_PROMPT_LINES, block);
		assertTrue(block.lines().allMatch(l -> l.contains("overworld]") && l.contains("(I saw it)")),
				"every prompt line says where and how he knows it: " + block);
	}

	@Test
	void theListingIsNumberedAndNamesTheSource() {
		place("home", "my bed is there", 12, 64, -7);
		OperationalMemory.remember(owner, Kind.CONTAINER, "chest", "holds 24 iron_ingot",
				OVERWORLD, 20, 64, -3, true, Source.SEEN);
		OperationalMemory.remember(owner, Kind.OUTCOME, "reach the roof", "failed — no route",
				OVERWORLD, 0, 0, 0, false, Source.DONE);
		String listing = OperationalMemory.listing(owner);
		System.out.println(listing);
		assertTrue(listing.startsWith("1. home (place): my bed is there at 12 64 -7 in overworld"), listing);
		assertTrue(listing.contains("2. chest (container): holds 24 iron_ingot at 20 64 -3 in overworld — I saw it"), listing);
		assertTrue(listing.contains("3. reach the roof (job): failed — no route — from my own work"), listing);
		assertFalse(listing.lines().toList().get(2).contains(" at "), "a job has no coordinates to show: " + listing);
	}

	@Test
	void forgetDropsExactlyTheNumberedRowAndRenumbersTheRest() {
		place("home", "", 12, 64, -7);
		place("camp", "", 0, 70, 0);
		place("mine mouth", "", -9, 62, 4);
		assertTrue(OperationalMemory.forget(owner, 2));
		List<Entry> left = OperationalMemory.entries(owner);
		assertEquals(2, left.size());
		assertEquals("home", left.get(0).subject());
		assertEquals("mine mouth", left.get(1).subject());
		assertFalse(OperationalMemory.forget(owner, 5), "a number that is not in the list is not a deletion");
		assertEquals(2, OperationalMemory.entries(owner).size());
	}

	@Test
	void aBlankSubjectIsRefusedAndANullKindIsNotAFact() {
		assertNull(OperationalMemory.remember(owner, Kind.PLACE, "   ", "somewhere", OVERWORLD, 1, 2, 3, true, Source.OWNER));
		assertNull(OperationalMemory.remember(owner, null, "home", "x", OVERWORLD, 1, 2, 3, true, Source.OWNER));
		assertTrue(OperationalMemory.entries(owner).isEmpty());
	}

	@Test
	void clearRemovesOneOwnerAndLeavesTheOther() {
		UUID other = UUID.randomUUID();
		place("home", "", 1, 64, 1);
		OperationalMemory.remember(other, Kind.PLACE, "their home", "", OVERWORLD, 2, 64, 2, true, Source.OWNER);
		OperationalMemory.clear(owner);
		assertTrue(OperationalMemory.entries(owner).isEmpty());
		assertEquals(1, OperationalMemory.entries(other).size());
	}

	@Test
	void anUnreadableFileIsSetAsideAndTheStoreStartsEmpty() throws Exception {
		place("home", "", 1, 64, 1);
		Files.writeString(file, "{ not json at all");
		assertDoesNotThrow(() -> OperationalMemory.setFileForTesting(file));
		assertTrue(OperationalMemory.entries(owner).isEmpty());
		try (var siblings = Files.list(dir)) {
			assertTrue(siblings.anyMatch(p -> p.getFileName().toString().contains(".bad-")),
					"the unreadable file is moved aside, not deleted");
		}
	}

	@Test
	void sequencingSurvivesAReloadSoTheTieBreakStaysNewestFirst() {
		place("one", "", 0, 64, 0);
		place("two", "", 0, 64, 0);
		OperationalMemory.setFileForTesting(file);
		place("three", "", 0, 64, 0);
		List<Entry> all = OperationalMemory.entries(owner);
		assertEquals(List.of("one", "two", "three"), all.stream().map(Entry::subject).toList());
		assertTrue(all.get(2).seq() > all.get(1).seq() && all.get(1).seq() > all.get(0).seq(), all.toString());
		assertNotNull(all.get(2).key());
	}
}
