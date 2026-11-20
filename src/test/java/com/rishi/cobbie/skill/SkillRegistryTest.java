package com.rishi.cobbie.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillRegistry against a temp file — loadFrom() bypasses FabricLoader and
 * clears cached state, so every test starts with an empty store.
 */
class SkillRegistryTest {

	@TempDir
	Path dir;

	private Path file;

	@BeforeEach
	void setUp() {
		file = dir.resolve("cobbie_skills.json");
		SkillRegistry.loadFrom(file);
	}

	@AfterEach
	void tearDown() {
		SkillRegistry.loadFrom(null);
	}

	private static List<ActionStep> steps(ActionStep... in) {
		return List.of(in);
	}

	@Test
	void actionStepCarriesCoordinatesAndRelativeFlag() {
		ActionStep absolute = new ActionStep("mine", "minecraft:coal_ore", 10, 63, -5, "");
		ActionStep relative = new ActionStep("mine", "minecraft:coal_ore", 0, -1, 2, "rel");
		assertFalse(absolute.relative());
		assertTrue(relative.relative());
		assertEquals("mine minecraft:coal_ore @10 63 -5", absolute.summary());
		assertTrue(relative.summary().contains("~0 -1 2"), relative.summary());
	}

	@Test
	void skillRecordsReliability() {
		Skill proven = new Skill("a", "d", steps(new ActionStep("wave", "", 0, 0, 0, "")), 9, 1, true);
		Skill newOne = new Skill("b", "d", steps(new ActionStep("wave", "", 0, 0, 0, "")), 0, 0, true);
		Skill flaky = new Skill("c", "d", steps(new ActionStep("wave", "", 0, 0, 0, "")), 1, 6, true);
		assertEquals(10, proven.trials());
		assertTrue(proven.reliability() > newOne.reliability());
		assertTrue(newOne.reliability() > flaky.reliability(), "an untried skill beats a known-bad one");
	}

	@Test
	void emptyStoreIsSafeAndSilent() {
		assertTrue(SkillRegistry.all().isEmpty());
		assertEquals(Optional.empty(), SkillRegistry.get("nope"));
		assertTrue(SkillRegistry.match("mine some iron", 3).isEmpty());
		assertEquals("", SkillRegistry.describeForPrompt(4));
		assertDoesNotThrow(() -> SkillRegistry.recordOutcome("nope", true));
		assertDoesNotThrow(() -> SkillRegistry.setEnabled("nope", false));
		assertFalse(SkillRegistry.delete("nope"));
		assertTrue(SkillRegistry.inspect().contains("none yet"), SkillRegistry.inspect());
	}

	@Test
	void saveSkillRejectsBadInput() {
		assertFalse(SkillRegistry.saveSkill("", "no name", steps(new ActionStep("wave", "", 0, 0, 0, ""))));
		assertFalse(SkillRegistry.saveSkill("   ", null, steps(new ActionStep("wave", "", 0, 0, 0, ""))));
		assertFalse(SkillRegistry.saveSkill("no-steps", "d", List.of()));
		assertFalse(SkillRegistry.saveSkill("no-steps", "d", null));
		// only whitelisted actions survive
		assertFalse(SkillRegistry.saveSkill("fly", "d", steps(new ActionStep("fly", "", 0, 0, 0, ""))));
		// too many steps
		List<ActionStep> many = new ArrayList<>();
		for (int i = 0; i <= SkillRegistry.MAX_STEPS; i++) many.add(new ActionStep("wait", "", 0, 0, 0, ""));
		assertFalse(SkillRegistry.saveSkill("too-long", "d", many));
		assertTrue(SkillRegistry.all().isEmpty());
	}

	@Test
	void saveSkillNormalisesTheName() {
		assertTrue(SkillRegistry.saveSkill("  Torch A Dark Room!!  ", "light it up",
				steps(new ActionStep("place", "minecraft:torch", 0, 1, 0, "rel"))));
		List<Skill> all = SkillRegistry.all();
		assertEquals(1, all.size());
		assertEquals("torch-a-dark-room", all.get(0).name());
		assertTrue(all.get(0).steps().get(0).relative());
		assertTrue(all.get(0).enabled());
	}

	@Test
	void duplicateNamesAreRejected() {
		List<ActionStep> s = steps(new ActionStep("collect", "", 0, 0, 0, ""));
		assertTrue(SkillRegistry.saveSkill("collect-drops", "pick up loot", s));
		assertFalse(SkillRegistry.saveSkill("Collect-Drops", "again", s));
		assertEquals(1, SkillRegistry.all().size());
	}

	@Test
	void storeIsCapped() {
		List<ActionStep> s = steps(new ActionStep("wait", "", 0, 0, 0, ""));
		for (int i = 0; i < SkillRegistry.MAX_SKILLS; i++) {
			assertTrue(SkillRegistry.saveSkill("skill-" + i, "filler " + i, s), "skill " + i);
		}
		assertEquals(SkillRegistry.MAX_SKILLS, SkillRegistry.all().size());
		assertFalse(SkillRegistry.saveSkill("one-too-many", "nope", s));
	}

	@Test
	void outcomesAreCountedAndPersisted() {
		assertTrue(SkillRegistry.saveSkill("mine-iron", "mine iron ore",
				steps(new ActionStep("walk-to", "", 10, 64, 10, ""),
						new ActionStep("mine", "", 10, 63, 10, ""))));
		assertTrue(SkillRegistry.recordOutcome("mine-iron", true));
		assertTrue(SkillRegistry.recordOutcome("mine-iron", true));
		assertFalse(SkillRegistry.recordOutcome("missing-skill", true));

		Skill s = SkillRegistry.get("mine-iron").orElseThrow();
		assertEquals(2, s.successes());
		assertEquals(0, s.failures());

		SkillRegistry.loadFrom(file); // reload from disk
		Skill reread = SkillRegistry.get("mine-iron").orElseThrow();
		assertEquals(2, reread.successes());
		assertEquals(2, reread.steps().size());
		assertEquals(10, reread.steps().get(0).x());
	}

	@Test
	void matchRanksByOverlapThenReliability() {
		SkillRegistry.saveSkill("mine-iron-ore", "dig iron ore out of a cave wall",
				steps(new ActionStep("mine", "minecraft:iron_ore", 1, -1, 0, "rel")));
		SkillRegistry.saveSkill("smelt-iron", "smelt raw iron in a furnace",
				steps(new ActionStep("place", "minecraft:furnace", 0, 0, 0, "")));
		SkillRegistry.saveSkill("sheep-soup", "kill a sheep and cook mutton",
				steps(new ActionStep("attack", "minecraft:sheep", 0, 0, 0, "")));
		SkillRegistry.recordOutcome("smelt-iron", true);
		SkillRegistry.recordOutcome("smelt-iron", true);
		SkillRegistry.recordOutcome("mine-iron-ore", false);

		List<Skill> hits = SkillRegistry.match("cobbie can you mine and smelt some iron please", 3);
		assertFalse(hits.isEmpty());
		assertEquals("smelt-iron", hits.get(0).name(), "same overlap, better track record wins: " + hits);
		assertTrue(hits.stream().anyMatch(s -> s.name().equals("mine-iron-ore")));
		assertFalse(hits.stream().anyMatch(s -> s.name().equals("sheep-soup")), "unrelated skill must not match");

		assertFalse(SkillRegistry.match("mine the iron ore in this cave", 3).isEmpty());
		assertTrue(SkillRegistry.match("", 3).isEmpty());
		assertTrue(SkillRegistry.match(null, 3).isEmpty());
		assertTrue(SkillRegistry.match("qwerty asdfz", 3).isEmpty());
	}

	@Test
	void disabledSkillsAreHiddenButKept() {
		SkillRegistry.saveSkill("wave-hello", "greet the player",
				steps(new ActionStep("wave", "", 0, 0, 0, "")));
		assertTrue(SkillRegistry.match("wave at me", 3).size() == 1);

		assertTrue(SkillRegistry.setEnabled("wave-hello", false));
		assertTrue(SkillRegistry.match("wave at me", 3).isEmpty(), "disabled skills must not be offered");
		assertEquals("", SkillRegistry.describeForPrompt(4));
		assertEquals(1, SkillRegistry.all().size(), "disabling keeps the skill around");
		assertEquals(0, SkillRegistry.get("wave-hello").orElseThrow().trials());

		SkillRegistry.loadFrom(file);
		assertFalse(SkillRegistry.get("wave-hello").orElseThrow().enabled());
		assertTrue(SkillRegistry.setEnabled("wave-hello", true));
		assertTrue(SkillRegistry.get("wave-hello").orElseThrow().enabled());
	}

	@Test
	void deleteRemovesAndPersists() {
		SkillRegistry.saveSkill("bad-habit", "punch the player",
				steps(new ActionStep("attack", "player", 0, 0, 0, "")));
		assertTrue(SkillRegistry.delete("bad-habit"));
		assertFalse(SkillRegistry.delete("bad-habit"));
		assertTrue(SkillRegistry.all().isEmpty());

		SkillRegistry.loadFrom(file);
		assertTrue(SkillRegistry.all().isEmpty());
	}

	@Test
	void describeForPromptIsCompactAndCapped() {
		List<ActionStep> longSteps = new ArrayList<>();
		for (int i = 0; i < SkillRegistry.MAX_STEPS; i++) {
			longSteps.add(new ActionStep("walk-to", "some very long target name " + i, i, 64, -i, ""));
		}
		for (int i = 0; i < 10; i++) {
			SkillRegistry.saveSkill("procedure-number-" + i,
					"a description that goes on for quite a while so the cap has work to do, attempt " + i, longSteps);
		}
		String block = SkillRegistry.describeForPrompt(20);
		assertFalse(block.isEmpty());
		assertTrue(block.length() <= SkillRegistry.PROMPT_CHAR_CAP, "len=" + block.length());
		assertTrue(block.lines().count() <= SkillRegistry.MAX_PROMPT_SKILLS, block);
		assertTrue(block.contains("procedure-number"), block);
	}

	@Test
	void unreadableFileIsSetAsideNotFatal() throws Exception {
		SkillRegistry.saveSkill("keep-me", "d", steps(new ActionStep("wait", "", 0, 0, 0, "")));
		Files.writeString(file, "} not json at all {{{");

		assertDoesNotThrow(() -> SkillRegistry.loadFrom(file));
		assertTrue(SkillRegistry.all().isEmpty());
		try (var siblings = Files.list(dir)) {
			assertTrue(siblings.anyMatch(p -> p.getFileName().toString().contains(".bad-")));
		}
		assertTrue(SkillRegistry.saveSkill("fresh-start", "d", steps(new ActionStep("wave", "", 0, 0, 0, ""))));
	}

	@Test
	void legacyFormatIsReadAndUpgraded() throws Exception {
		// 0.4.0 wrote a name-keyed object with "context" / "success" / "failure"
		Files.writeString(file, """
				{
				  "mine-coal": {
				    "name": "mine-coal",
				    "context": "dark-cave",
				    "steps": [
				      { "action": "walk-to", "target": "", "x": 10, "y": 64, "z": 10, "data": "" },
				      { "action": "teleport", "target": "", "x": 0, "y": 0, "z": 0, "data": "" }
				    ],
				    "success": 3,
				    "failure": 1
				  }
				}
				""");
		SkillRegistry.loadFrom(file);

		Skill s = SkillRegistry.get("mine-coal").orElseThrow();
		assertEquals("dark-cave", s.description());
		assertEquals(3, s.successes());
		assertEquals(1, s.failures());
		assertEquals(1, s.steps().size(), "the step the executor cannot run is dropped");
		assertTrue(s.enabled(), "old skills default to enabled");

		SkillRegistry.recordOutcome("mine-coal", true);
		assertTrue(Files.readString(file).contains("\"version\": 1"), "rewritten in the new shape");
		SkillRegistry.loadFrom(file);
		assertEquals(4, SkillRegistry.get("mine-coal").orElseThrow().successes());
	}

	@Test
	void allowedActionsMatchTheExecutorWhitelist() {
		for (String a : List.of("answer", "follow", "stay", "wave", "walk-to", "mine", "place",
				"attack", "collect", "chat", "wait", "explore")) {
			assertTrue(SkillRegistry.isAllowedAction(a), a);
		}
		assertFalse(SkillRegistry.isAllowedAction("fly"));
		assertFalse(SkillRegistry.isAllowedAction(""));
		assertFalse(SkillRegistry.isAllowedAction(null));
	}
}
