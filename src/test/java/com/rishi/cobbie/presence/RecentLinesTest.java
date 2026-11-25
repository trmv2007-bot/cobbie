package com.rishi.cobbie.presence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window a "what did he say" question deserves.
 *
 * <p>Each case here is a failure this project has already paid for once: a line overwritten by the next one and read
 * as if it were never said, an unbounded history, and an order that a reader has to guess at.
 */
class RecentLinesTest {

	@Test
	void anOlderLineIsStillThereWhenANewerOneLandsOnTop() {
		RecentLines said = new RecentLines(8);
		said.add("we changed worlds, so I stopped what I was doing");
		said.add("all done");

		assertEquals("all done", said.last(), "the newest line is still the newest");
		assertTrue(said.saidRecently("changed worlds"),
				"the line that explained the event is still in the window, which is the whole point");
		assertFalse(said.saidRecently("nothing left to do"));
	}

	@Test
	void theHistoryIsBoundedAndDropsTheOldestFirst() {
		RecentLines said = new RecentLines(3);
		said.add("one");
		said.add("two");
		said.add("three");
		said.add("four");

		assertEquals(3, said.size());
		assertEquals(List.of("two", "three", "four"), said.recent());
		assertFalse(said.saidRecently("one"), "a line that aged out is gone, not remembered forever");
		assertTrue(said.saidRecently("two"));
	}

	@Test
	void orderIsOldestFirstAndTheLastIsTheNewest() {
		RecentLines said = new RecentLines(4);
		said.add("first");
		said.add("second");
		said.add("third");

		assertEquals(List.of("first", "second", "third"), said.recent());
		assertEquals("third", said.last());
	}

	@Test
	void nothingSaidIsTheEmptyAnswerRatherThanNull() {
		RecentLines said = new RecentLines(4);
		assertEquals("", said.last());
		assertFalse(said.saidRecently("anything"));
		assertTrue(said.recent().isEmpty());
	}

	@Test
	void blanksAreNotSpeech() {
		RecentLines said = new RecentLines(4);
		said.add(null);
		said.add("   ");
		assertEquals(0, said.size());
		assertEquals("", said.last());
	}
}
