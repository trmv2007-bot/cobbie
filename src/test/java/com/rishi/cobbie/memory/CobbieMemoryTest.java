package com.rishi.cobbie.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rishi.cobbie.memory.CobbieMemory.Message;
import com.rishi.cobbie.memory.CobbieMemory.PlayerMemory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CobbieMemory against a temp file — no FabricLoader, no Minecraft runtime.
 * setFileForTesting() points the store at the temp dir and clears cached state,
 * so each test starts from an empty memory file.
 */
class CobbieMemoryTest {

	@TempDir
	Path dir;

	private Path file;
	private UUID player;

	@BeforeEach
	void setUp() {
		file = dir.resolve("cobbie_memory.json");
		CobbieMemory.setFileForTesting(file);
		player = UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		CobbieMemory.setFileForTesting(null); // back to the (absent) game default
	}

	@Test
	void unknownPlayerGetsDefaults() {
		PlayerMemory mem = CobbieMemory.get(player);
		assertEquals("Player", mem.nickname());
		assertTrue(mem.history().isEmpty());
		assertTrue(mem.preferences().isEmpty());
		assertTrue(mem.discoveries().isEmpty());
		assertTrue(mem.skills().isEmpty());
		assertEquals("", CobbieMemory.currentObjective(player));
	}

	@Test
	void bothRolesAreKeptInChronologicalOrder() {
		CobbieMemory.addTurn(player, "hello cobbie", "hi there!");
		CobbieMemory.addTurn(player, "how are you", "i'm good!");

		assertEquals(List.of(
				new Message("user", "hello cobbie"),
				new Message("assistant", "hi there!"),
				new Message("user", "how are you"),
				new Message("assistant", "i'm good!")),
				CobbieMemory.history(player, 10));
	}

	@Test
	void historyIsBoundedAndTakesTheNewestMessages() {
		for (int i = 0; i < 30; i++) {
			CobbieMemory.addTurn(player, "q" + i, "a" + i);
		}
		PlayerMemory mem = CobbieMemory.get(player);
		assertEquals(CobbieMemory.MAX_MESSAGES, mem.history().size());

		List<Message> lastFour = CobbieMemory.history(player, 4);
		assertEquals(4, lastFour.size());
		assertEquals("q28", lastFour.get(0).content());
		assertEquals("a29", lastFour.get(3).content());

		// asking for more than we keep is clamped, not fatal
		assertEquals(CobbieMemory.MAX_MESSAGES, CobbieMemory.history(player, 10_000).size());
		// zero/negative falls back to a sensible window
		assertFalse(CobbieMemory.history(player, 0).isEmpty());
	}

	@Test
	void identicalTurnIsNotRecordedTwice() {
		CobbieMemory.addTurn(player, "same", "same answer");
		CobbieMemory.addTurn(player, "same", "same answer");
		assertEquals(2, CobbieMemory.get(player).history().size());
	}

	@Test
	void failedRequestNeverBecomesAnAssistantTurn() {
		// LLM call blew up: the brain has no answer to hand over
		CobbieMemory.addTurn(player, "what is that block?", "");
		List<Message> afterFailure = CobbieMemory.get(player).history();
		assertEquals(1, afterFailure.size());
		assertEquals("user", afterFailure.get(0).role());

		// retry succeeds with the same question -> answer completes the turn, no duplicate
		CobbieMemory.addTurn(player, "what is that block?", "that's diamond!");
		assertEquals(List.of(new Message("user", "what is that block?"), new Message("assistant", "that's diamond!")),
				CobbieMemory.get(player).history());
	}

	@Test
	void nicknameIsStoredAndUnchangedValuesDoNotRewriteTheFile() throws Exception {
		CobbieMemory.addTurn(player, "hi", "hello");
		assertTrue(Files.exists(file));

		// canary: if setNickname writes, this text disappears
		CobbieMemory.setNickname(player, "Rishi");
		Files.writeString(file, "CANARY");
		CobbieMemory.setNickname(player, "Rishi");
		assertEquals("CANARY", Files.readString(file), "unchanged nickname must not rewrite the file");

		CobbieMemory.setNickname(player, "Rishi the Second");
		assertEquals("Rishi the Second", CobbieMemory.get(player).nickname());
		assertFalse(Files.readString(file).equals("CANARY"));
	}

	@Test
	void longTermFieldsRoundTripThroughDisk() {
		CobbieMemory.addTurn(player, "i love caves", "caves are great!");
		CobbieMemory.setNickname(player, "Rishi");
		CobbieMemory.notePreference(player, "favorite-block", "glow stone");
		CobbieMemory.noteDiscovery(player, "village at 200 64 -50");
		CobbieMemory.setCurrentObjective(player, "mine 5 iron");
		CobbieMemory.addSkill(player, "torch-a-dark-room");
		CobbieMemory.save();

		CobbieMemory.setFileForTesting(file); // drop the cache, force a reload
		PlayerMemory mem = CobbieMemory.get(player);
		assertEquals("Rishi", mem.nickname());
		assertEquals("mine 5 iron", CobbieMemory.currentObjective(player));
		assertEquals(List.of("village at 200 64 -50"), mem.discoveries());
		assertEquals(1, mem.preferences().size());
		assertEquals("glow stone", mem.preferences().get(0).value());
		assertEquals(List.of("torch-a-dark-room"), mem.skills());
		assertEquals(2, mem.history().size());
	}

	@Test
	void preferencesAreOverwrittenNotStacked() {
		CobbieMemory.notePreference(player, "mood", "chatty");
		CobbieMemory.notePreference(player, "MOOD", "quiet");
		List<com.rishi.cobbie.memory.CobbieMemory.Preference> prefs = CobbieMemory.get(player).preferences();
		assertEquals(1, prefs.size());
		assertEquals("quiet", prefs.get(0).value());
	}

	@Test
	void discoveriesDedupeAndStayBounded() {
		for (int i = 0; i < CobbieMemory.MAX_DISCOVERIES + 6; i++) {
			CobbieMemory.noteDiscovery(player, "fact " + (i % 3));
		}
		assertEquals(3, CobbieMemory.get(player).discoveries().size());
		CobbieMemory.noteDiscovery(player, "fact 0");
		assertEquals(3, CobbieMemory.get(player).discoveries().size());

		for (int i = 0; i < 30; i++) CobbieMemory.noteDiscovery(player, "fact " + i);
		assertEquals(CobbieMemory.MAX_DISCOVERIES, CobbieMemory.get(player).discoveries().size());
	}

	@Test
	void summaryIsEmptyUntilThereIsSomethingToSay() {
		assertEquals("", CobbieMemory.longTermSummary(player));
		CobbieMemory.notePreference(player, "likes", "torch placements");
		String summary = CobbieMemory.longTermSummary(player);
		assertTrue(summary.contains("torch placements"), summary);
		assertTrue(summary.length() <= CobbieMemory.SUMMARY_CHAR_CAP);
	}

	@Test
	void summaryMentionsObjectiveDiscoveriesAndSkills() {
		CobbieMemory.setCurrentObjective(player, "build a nether portal");
		CobbieMemory.noteDiscovery(player, "lava lake at 12 6 -80");
		CobbieMemory.addSkill(player, "smelt-iron");
		String summary = CobbieMemory.longTermSummary(player);
		assertTrue(summary.contains("build a nether portal"), summary);
		assertTrue(summary.contains("lava lake"), summary);
		assertTrue(summary.contains("smelt-iron"), summary);
	}

	@Test
	void inspectShowsTheStoredFields() {
		CobbieMemory.setNickname(player, "Rishi");
		CobbieMemory.setCurrentObjective(player, "find diamonds");
		CobbieMemory.addTurn(player, "come here", "coming");
		String dump = CobbieMemory.inspect(player);
		assertTrue(dump.contains("Rishi"), dump);
		assertTrue(dump.contains("find diamonds"), dump);
		assertTrue(dump.contains("user: come here"), dump);
		assertTrue(dump.contains("assistant: coming"), dump);
	}

	@Test
	void clearWipesEverythingForThatPlayer() {
		UUID other = UUID.randomUUID();
		CobbieMemory.addTurn(player, "hi", "hi");
		CobbieMemory.notePreference(player, "likes", "caves");
		CobbieMemory.addTurn(other, "hi", "hi");
		CobbieMemory.clear(player);

		CobbieMemory.setFileForTesting(file);
		assertTrue(CobbieMemory.get(player).history().isEmpty());
		assertTrue(CobbieMemory.get(player).preferences().isEmpty());
		assertEquals(2, CobbieMemory.get(other).history().size(), "other players are untouched");
	}

	@Test
	void corruptFileIsSetAsideInsteadOfThrowing() throws Exception {
		CobbieMemory.addTurn(player, "hi", "hi");
		Files.writeString(file, "{ this is not json ");

		assertDoesNotThrow(() -> CobbieMemory.setFileForTesting(file));
		assertEquals("Player", CobbieMemory.get(player).nickname()); // fresh start
		try (var siblings = Files.list(dir)) {
			assertTrue(siblings.anyMatch(p -> p.getFileName().toString().contains(".bad-")),
					"the unreadable file should be moved aside, not deleted");
		}
	}

	@Test
	void whitespaceAndOversizedInputAreNormalised() {
		CobbieMemory.addTurn(player, "  many\n\n spaces  ", "x".repeat(CobbieMemory.MAX_MESSAGE_CHARS + 500));
		Message user = CobbieMemory.get(player).history().get(0);
		Message answer = CobbieMemory.get(player).history().get(1);
		assertEquals("many spaces", user.content());
		assertEquals(CobbieMemory.MAX_MESSAGE_CHARS, answer.content().length());
	}
}
