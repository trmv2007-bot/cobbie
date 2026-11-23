package com.rishi.cobbie.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The latency record: the arithmetic the release note's table is built from, and the marks that
 * have to be there for a number to mean anything.
 */
class ActionTimingTest {

	@Test
	void oneInstructionIsBrokenIntoTheStagesThatCanWait() {
		ActionTiming.chat(1_000);
		ActionTiming.dispatched(1_350);
		ActionTiming.replied(2_850);
		ActionTiming.firstStep(2_900);
		ActionTiming.settled(11_020, 4);
		ActionTiming.resultSpoken(11_022);

		ActionTiming.Timings t = ActionTiming.last();
		assertEquals(350, t.batchMillis(), "chat batch window");
		assertEquals(1_500, t.modelMillis(), "model round trip");
		assertEquals(50, t.startMillis(), "reply to first movement");
		assertEquals(8_120, t.workMillis(), "the work itself");
		assertEquals(2, t.resultMillis(), "settled to his line about it");
		assertEquals(4, t.steps());
		assertEquals(1, t.modelCalls());
		assertEquals(10_022, t.totalMillis());
		assertTrue(t.toString().contains("model calls=1"));
	}

	@Test
	void aSecondModelCallIsCountedBecauseThatWasTheProblem() {
		ActionTiming.chat(0);
		ActionTiming.dispatched(350);
		ActionTiming.extraModelCall();
		ActionTiming.replied(1_750);
		ActionTiming.resultSpoken(1_760);
		assertEquals(2, ActionTiming.last().modelCalls());
	}

	@Test
	void anInstructionThatNeverStartedWorkStillCloses() {
		ActionTiming.chat(5_000);
		ActionTiming.dispatched(5_350);
		ActionTiming.replied(6_850);
		ActionTiming.resultSpoken(6_851);

		ActionTiming.Timings t = ActionTiming.last();
		assertEquals(0, t.startMillis());
		assertEquals(0, t.workMillis());
		assertEquals(1, t.modelCalls());
	}

	@Test
	void closingTheSameRecordTwiceRecordsNothingNew() {
		ActionTiming.chat(1_000);
		ActionTiming.dispatched(1_350);
		ActionTiming.replied(2_000);
		ActionTiming.resultSpoken(2_001);
		ActionTiming.Timings first = ActionTiming.last();

		ActionTiming.resultSpoken(9_999);
		assertEquals(first, ActionTiming.last(), "no instruction is open, so nothing may be overwritten");
	}
}
