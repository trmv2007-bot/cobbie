package com.rishi.cobbie.opportunity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cadence, in exact ticks: when a scene has not changed, the model is not asked again.
 *
 * <p>The failure this guards against is the one the brief names — re-asking about an unchanged situation,
 * which for a local 8B model is a second of its owner's battery and a slice of a small budget for the same
 * answer. Every assertion below is arithmetic on integers rather than a sleep, because the other failure
 * this project has paid for three times is a wait that measures nothing.
 */
class OpportunityCadenceTest {

	private static Situation scene() {
		return new Situation(1, 14, 4, 0, 12345, false, 7, 900);
	}

	private static Situation changed() {
		return new Situation(1, 14, 4, 1, 12345, false, 7, 900);
	}

	@Test
	void theFirstSceneIsAlwaysAsked() {
		OpportunityCadence cadence = new OpportunityCadence();
		assertTrue(cadence.shouldAsk(0, scene()));
	}

	@Test
	void anUnchangedSceneIsNotAskedAgainUntilTheHeartbeat() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(100, scene());
		for (long t = 101; t < 100 + OpportunityCadence.HEARTBEAT_TICKS; t += 7) {
			assertFalse(cadence.shouldAsk(t, scene()), "asked again at tick " + t + " with nothing changed");
		}
		assertTrue(cadence.shouldAsk(100 + OpportunityCadence.HEARTBEAT_TICKS, scene()),
				"the slow heartbeat is what stops a long quiet spell from freezing an old decision");
	}

	@Test
	void theHeartbeatIsSixtySeconds() {
		assertEquals(1200, OpportunityCadence.HEARTBEAT_TICKS, "1200 ticks is one minute at twenty a second");
	}

	@Test
	void aChangedSceneIsAskedAtOnce() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(100, scene());
		assertTrue(cadence.shouldAsk(100 + OpportunityCadence.MIN_GAP_TICKS, changed()));
	}

	@Test
	void aChangeInsideTheMinimumGapWaitsForTheGap() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(100, scene());
		assertFalse(cadence.shouldAsk(110, changed()), "a scene that flaps must not become a poll");
		assertTrue(cadence.shouldAsk(100 + OpportunityCadence.MIN_GAP_TICKS, changed()));
	}

	@Test
	void aRejectionBacksOffInsteadOfRepeatingItself() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(0, scene());
		cadence.waited(0);
		long wait = OpportunityCadence.backoffFor(1);
		assertFalse(cadence.shouldAsk(wait - 1, scene()));
		assertTrue(cadence.shouldAsk(wait, scene()));
		assertEquals(1, cadence.waitStreak());
		assertEquals(OpportunityCadence.HEARTBEAT_TICKS, wait, "the first rejection never asks sooner than the heartbeat");
	}

	@Test
	void repeatedRejectionsOnOneSceneBackOffButNeverFurtherThanTwoMinutes() {
		OpportunityCadence cadence = new OpportunityCadence();
		long now = 0;
		cadence.noted(now, scene());
		for (int i = 1; i <= OpportunityCadence.MAX_WAIT_STREAK + 3; i++) {
			cadence.waited(now);
			long wait = OpportunityCadence.backoffFor(i);
			assertFalse(cadence.shouldAsk(now + wait - 1, scene()), "asked before the backoff at round " + i);
			assertTrue(cadence.shouldAsk(now + wait, scene()), "never asked after the backoff at round " + i);
			now += wait;
			cadence.noted(now, scene());
		}
		assertEquals(OpportunityCadence.MAX_WAIT_STREAK, cadence.waitStreak());
		assertEquals(2400, OpportunityCadence.backoffFor(9), "a rejection streak never stretches past two minutes");
	}

	@Test
	void aChangeEndsTheBackoffBecauseItIsANewQuestion() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(0, scene());
		cadence.waited(0);
		assertFalse(cadence.shouldAsk(100, scene()), "the same unchanged scene is still in backoff");
		assertTrue(cadence.shouldAsk(100, changed()),
				"a rejection is about the scene it was given; a scene that moved on deserves a new question");
		cadence.noted(120, changed());
		assertEquals(0, cadence.waitStreak(), "a scene that moved on has had its answer");
		assertTrue(cadence.shouldAsk(120 + OpportunityCadence.HEARTBEAT_TICKS, changed()));
	}

	@Test
	void afterHeStartsSomethingOfHisOwnTheNextQuestionWaitsTwoMinutes() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(0, scene());
		cadence.chosen(0);
		assertFalse(cadence.shouldAsk(OpportunityCadence.QUIET_AFTER_CHOICE_TICKS - 1, changed()));
		assertTrue(cadence.shouldAsk(OpportunityCadence.QUIET_AFTER_CHOICE_TICKS, changed()));
	}

	@Test
	void theReasonNamesWhichPartOfTheSceneMoved() {
		Situation changed = new Situation(2, 14, 4, 0, 12345, false, 7, 900);
		assertEquals("the owner moved", changed.changesSince(scene()));
		assertEquals("nothing changed", scene().changesSince(scene()));
		assertEquals("nothing asked yet", scene().changesSince(null));
		String both = new Situation(2, 14, 4, 3, 999, true, 8, 1000).changesSince(scene());
		assertTrue(both.contains("the owner moved"), both);
		assertTrue(both.contains("the hostiles changed"), both);
		assertTrue(both.contains("his bag changed"), both);
		assertTrue(both.contains("a job started or finished"), both);
		assertTrue(both.contains("a different job ran"), both);
		assertTrue(both.contains("the owner spoke"), both);
	}

	@Test
	void theGateCanSayWhyItDidNotAsk() {
		OpportunityCadence cadence = new OpportunityCadence();
		cadence.noted(100, scene());
		assertTrue(cadence.why(110, scene()).contains("asked 10 ticks ago"), cadence.why(110, scene()));
		String idle = cadence.why(400, scene());
		assertTrue(idle.contains("unchanged"), idle);
		assertTrue(idle.contains("heartbeat in"), idle);
		assertEquals("changed: the owner moved", cadence.why(400,
				new Situation(3, 14, 4, 0, 12345, false, 7, 900)));
	}
}
