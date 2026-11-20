package com.rishi.cobbie.skill;

import static org.junit.jupiter.api.Assertions.*;

import com.rishi.cobbie.action.ActionIntent;
import org.junit.jupiter.api.Test;

/**
 * Tests for SkillRegistry — skill selection and execution.
 */
class SkillRegistryTest {

	@Test
	void testSkillCreation() {
		ActionStep step1 = new ActionStep("walk-to", "", 10, 64, 10, "");
		ActionStep step2 = new ActionStep("mine", "", 10, 63, 10, "");
		Skill skill = new Skill("mine-coal", "dark-cave",
				java.util.List.of(step1, step2), 3, 1);

		assertEquals("mine-coal", skill.name());
		assertEquals("dark-cave", skill.context());
		assertEquals(2, skill.steps().size());
		assertEquals(3, skill.successCount());
		assertEquals(1, skill.failureCount());
	}

	@Test
	void testActionStepFields() {
		ActionStep step = new ActionStep("place", "cobblestone", 5, 64, 5, "data");
		assertEquals("place", step.action());
		assertEquals("cobblestone", step.target());
		assertEquals(5, step.x());
		assertEquals(64, step.y());
		assertEquals(5, step.z());
		assertEquals("data", step.data());
	}

	@Test
	void testSkillRegistryLoadSave() {
		// Registry loads (may be empty initially)
		assertNotNull(SkillRegistry.all());
		assertNotNull(SkillRegistry.get("nonexistent"));
		assertTrue(SkillRegistry.get("nonexistent").isEmpty());

		// Register a skill
		ActionStep step = new ActionStep("walk-to", "", 0, 64, 0, "");
		Skill skill = new Skill("test-skill", "test-context",
				java.util.List.of(step), 0, 0);
		SkillRegistry.register(skill);

		assertTrue(SkillRegistry.get("test-skill").isPresent());
		assertEquals("test-skill", SkillRegistry.get("test-skill").get().name());

		// Record success
		SkillRegistry.recordSuccess("test-skill");
		assertEquals(1, SkillRegistry.get("test-skill").get().successCount());

		// Record failure
		SkillRegistry.recordFailure("test-skill");
		assertEquals(1, SkillRegistry.get("test-skill").get().failureCount());
	}

	@Test
	void testSkillMatchByContext() {
		ActionStep step = new ActionStep("walk-to", "", 0, 64, 0, "");
		Skill skill = new Skill("cave-explore", "dark-cave-exploration",
				java.util.List.of(step), 5, 0);
		SkillRegistry.register(skill);

		var match = SkillRegistry.match("dark-cave");
		assertTrue(match.isPresent());
		assertEquals("cave-explore", match.get().name());
	}

	@Test
	void testSkillRegistryEmpty() {
		// Registry operations on empty state should not throw
		assertDoesNotThrow(() -> SkillRegistry.all());
		assertDoesNotThrow(() -> SkillRegistry.get("nonexistent"));
		assertDoesNotThrow(() -> SkillRegistry.match("anything"));
		assertDoesNotThrow(() -> SkillRegistry.recordSuccess("nonexistent"));
		assertDoesNotThrow(() -> SkillRegistry.recordFailure("nonexistent"));
	}
}
