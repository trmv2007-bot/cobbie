package com.rishi.cobbie.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rishi.cobbie.memory.OwnerWords.Said;
import org.junit.jupiter.api.Test;

/**
 * The owner's sentence, read without the model. What matters is that a miss comes back as null rather
 * than as a guess: a parser that invents a subject would store a memory about something nobody said.
 */
class OwnerWordsTest {

	@Test
	void aPlaceIsANameAndThreeNumbers() {
		Said said = OwnerWords.parse("home is at 12 64 -7");
		assertEquals("home", said.subject());
		assertEquals("", said.fact());
		assertTrue(said.at());
		assertEquals(12, said.x());
		assertEquals(64, said.y());
		assertEquals(-7, said.z());
	}

	@Test
	void commasBetweenTheNumbersAreTheSameThing() {
		Said said = OwnerWords.parse("my base is 320, 64, -11");
		assertEquals("my base", said.subject());
		assertTrue(said.at());
		assertEquals(320, said.x());
		assertEquals(-11, said.z());
	}

	@Test
	void theRememberOpenerIsNotPartOfWhatHeStores() {
		Said said = OwnerWords.parse("remember the roof is at 4 90 0");
		assertEquals("the roof", said.subject());
		assertTrue(said.at());
		assertEquals(90, said.y());

		Said polite = OwnerWords.parse("please remember camp at 0 70 0");
		assertEquals("camp", polite.subject());
		assertEquals(70, polite.y());
	}

	@Test
	void aStatementWithoutCoordinatesIsTheOwnersWordAndNotAPlace() {
		Said said = OwnerWords.parse("the iron is in the barrel by the door");
		assertEquals("the iron", said.subject());
		assertEquals("in the barrel by the door", said.fact());
		assertFalse(said.at(), "nothing was named with coordinates, so this is not a place");
	}

	@Test
	void aSentenceWithNoStatementInItIsDeclined() {
		assertNull(OwnerWords.parse(""));
		assertNull(OwnerWords.parse("   "));
		assertNull(OwnerWords.parse("remember"));
		assertNull(OwnerWords.parse("remember that"));
		assertNull(OwnerWords.parse(null));
	}

	@Test
	void aNumberInTheMiddleOfAnInstructionIsNotACoordinate() {
		// Only a trailing triple counts; "3 stacks" in the middle of a sentence is not a place.
		Said said = OwnerWords.parse("I want 3 stacks of oak planks in the chest");
		assertFalse(said.at(), "that 3 is a quantity, not a coordinate");
		assertEquals("I want 3 stacks of oak planks in the chest", said.subject());
	}
}
