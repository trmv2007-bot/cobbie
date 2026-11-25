package com.rishi.cobbie.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The out-of-sight boundary: one line, a deadline, and an end.
 *
 * <p>The shape this replaces was a bare {@code if (!loaded) return;} in the follow loop — nothing asked for, nothing
 * said, retried on a timer forever. These are the four things that shape got wrong, each pinned separately: the line
 * is said once per episode, the wait is bounded, giving up is a state and not a silence, and coming back resumes on
 * the same tick with the right to speak again next time.
 */
class FollowSightTest {

	@Test
	void theLineIsSaidOnceForOneEpisode() {
		FollowSight sight = new FollowSight(200);
		assertEquals(FollowSight.Step.SPEAK, sight.onTick(false, 100));
		// Never SPEAK again inside the same episode, whichever side of the deadline the tick falls on.
		for (long tick = 101; tick < 300; tick++) {
			assertEquals(FollowSight.Step.WAITING, sight.onTick(false, tick), "tick " + tick);
		}
		for (long tick = 300; tick < 500; tick++) {
			assertEquals(FollowSight.Step.GAVE_UP, sight.onTick(false, tick), "tick " + tick);
		}
	}

	@Test
	void theWaitIsBounded() {
		FollowSight sight = new FollowSight(50);
		assertEquals(FollowSight.Step.SPEAK, sight.onTick(false, 0));
		assertEquals(FollowSight.Step.WAITING, sight.onTick(false, 49));
		assertEquals(FollowSight.Step.GAVE_UP, sight.onTick(false, 50));
		assertTrue(sight.gaveUp(), "past the deadline he has stopped trying, and says so");
	}

	@Test
	void aLoadedChunkResumesFollowingAndClearsTheEpisode() {
		FollowSight sight = new FollowSight(50);
		sight.onTick(false, 0);
		sight.onTick(false, 80);
		assertTrue(sight.gaveUp());

		assertEquals(FollowSight.Step.VISIBLE, sight.onTick(true, 81));
		assertFalse(sight.gaveUp(), "back in sight, he is following again, not given up");
		// Same tick, still following: no lingering wait between the chunk loading and the walk.
		assertEquals(FollowSight.Step.VISIBLE, sight.onTick(true, 82));
	}

	@Test
	void aSecondDisappearanceIsANewEpisodeAndMaySpeakOnceMore() {
		FollowSight sight = new FollowSight(50);
		assertEquals(FollowSight.Step.SPEAK, sight.onTick(false, 0));
		sight.onTick(true, 10);
		assertEquals(FollowSight.Step.SPEAK, sight.onTick(false, 20), "a new episode gets its one line");
		assertEquals(FollowSight.Step.WAITING, sight.onTick(false, 21));
	}

	@Test
	void whileTheOwnerIsVisibleNothingIsSaidAtAll() {
		FollowSight sight = new FollowSight(50);
		for (long tick = 0; tick < 100; tick++) {
			assertEquals(FollowSight.Step.VISIBLE, sight.onTick(true, tick));
		}
		assertFalse(sight.gaveUp());
	}
}
