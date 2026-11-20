package com.rishi.cobbie.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for CobbieMemory — persistent cross-session memory logic.
 * (FabricLoader disk access not available in test env; tests logic only.)
 */
class CobbieMemoryTest {

	@Test
	void testMemoryCreation() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.PlayerMemory mem = CobbieMemory.get(uuid);
		assertNotNull(mem);
		assertEquals("Player", mem.nickname());
		assertTrue(mem.turns().isEmpty());
		assertTrue(mem.skills().isEmpty());
	}

	@Test
	void testAddTurn() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.addTurn(uuid, "hello cobbie", "hi there!");
		CobbieMemory.addTurn(uuid, "how are you", "i'm good!");

		var mem = CobbieMemory.get(uuid);
		assertEquals(2, mem.turns().size());
		assertEquals("hi there!", mem.turns().get(0).cobbieAnswer());
		assertEquals("i'm good!", mem.turns().get(1).cobbieAnswer());
		assertEquals("hello cobbie", mem.turns().get(0).playerText());
	}

	@Test
	void testSetNickname() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.setNickname(uuid, "Rishi");

		var mem = CobbieMemory.get(uuid);
		assertEquals("Rishi", mem.nickname());
	}

	@Test
	void testAddSkill() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.addSkill(uuid, "mine-coal");
		CobbieMemory.addSkill(uuid, "place-torch");

		var mem = CobbieMemory.get(uuid);
		assertTrue(mem.skills().contains("mine-coal"));
		assertTrue(mem.skills().contains("place-torch"));
		assertEquals(2, mem.skills().size());

		// Adding duplicate should not increase count
		CobbieMemory.addSkill(uuid, "mine-coal");
		assertEquals(2, CobbieMemory.get(uuid).skills().size());
	}

	@Test
	void testRecentAnswers() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.addTurn(uuid, "msg1", "resp1");
		CobbieMemory.addTurn(uuid, "msg2", "resp2");
		CobbieMemory.addTurn(uuid, "msg3", "resp3");

		var recent = CobbieMemory.recentAnswers(uuid, 2);
		assertEquals(2, recent.size());
		assertEquals("resp2", recent.get(0));
		assertEquals("resp3", recent.get(1));
	}

	@Test
	void testChronologicalOrder() {
		UUID uuid = UUID.randomUUID();
		CobbieMemory.addTurn(uuid, "first", "first-resp");
		CobbieMemory.addTurn(uuid, "second", "second-resp");

		var mem = CobbieMemory.get(uuid);
		assertEquals("first-resp", mem.turns().get(0).cobbieAnswer());
		assertEquals("second-resp", mem.turns().get(1).cobbieAnswer());
	}
}
