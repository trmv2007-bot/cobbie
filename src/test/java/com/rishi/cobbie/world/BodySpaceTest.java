package com.rishi.cobbie.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * BodySpace — where his body fits, and where to put him when it does not.
 *
 * <p>No world here: the rule is "two free blocks with ground under them", stated as a predicate over
 * coordinates, so the check a walk is refused by and the rescue that gets him out of a wall are both
 * decided by something a unit test can pin down. The in-game tier proves the entity asks the question;
 * this proves the answer.
 */
class BodySpaceTest {

	/** A stone floor at feet y = 4 and open air above it. */
	private static BodySpace.Solid overFloor() {
		return (x, y, z) -> y <= 4;
	}

	@Test
	void aColumnTwoBlocksHighOverGroundFits() {
		assertTrue(BodySpace.fits(overFloor(), 0, 5, 0));
	}

	@Test
	void stoneAtHisHeadMeansHeDoesNotFit() {
		BodySpace.Solid ceiling = (x, y, z) -> y <= 4 || y == 6;
		assertFalse(BodySpace.fits(ceiling, 0, 5, 0));
	}

	@Test
	void stoneAtHisFeetMeansHeDoesNotFit() {
		BodySpace.Solid filled = (x, y, z) -> y <= 5;
		assertFalse(BodySpace.fits(filled, 0, 5, 0));
	}

	@Test
	void oneFreeBlockIsNotEnoughBecauseHeIsTallerThanABlock() {
		// A one-block corridor: feet free, head in stone. This is the doorway a walk must refuse.
		BodySpace.Solid corridor = (x, y, z) -> y != 5;
		assertFalse(BodySpace.fits(corridor, 0, 5, 0));
	}

	@Test
	void theRescueFindsTheNearestGapOutOfAWall() {
		// Solid everywhere except a two-block gap three blocks east of him.
		BodySpace.Solid wall = (x, y, z) -> !(x == 3 && (y == 5 || y == 6));
		assertArrayEquals(new int[] {3, 5, 0}, BodySpace.nearestFree(wall, 0, 5, 0, 4));
	}

	@Test
	void theRescueTakesTheCloserOfTwoGaps() {
		BodySpace.Solid twoGaps = (x, y, z) -> !((x == 1 || x == 2) && (y == 5 || y == 6));
		assertArrayEquals(new int[] {1, 5, 0}, BodySpace.nearestFree(twoGaps, 0, 5, 0, 4));
	}

	@Test
	void theRescueWantsGroundUnderTheGap() {
		// The whole column at x=1 is open, floor included, so standing there is a fall; x=2 is a gap
		// standing on stone. The closer one is the wrong answer, so support decides and distance only
		// breaks ties.
		BodySpace.Solid floaty = (x, y, z) -> {
			if (x == 1) return false;
			if (x == 2 && (y == 5 || y == 6)) return false;
			return true;
		};
		assertArrayEquals(new int[] {2, 5, 0}, BodySpace.nearestFree(floaty, 0, 5, 0, 4));
	}

	@Test
	void theRescueGivesUpRatherThanInventAPlace() {
		BodySpace.Solid solidEverywhere = (x, y, z) -> true;
		assertNull(BodySpace.nearestFree(solidEverywhere, 0, 5, 0, 3));
	}

	@Test
	void theRescueDoesNotSearchPastItsRadius() {
		BodySpace.Solid farGap = (x, y, z) -> !(x == 9 && (y == 5 || y == 6));
		assertNull(BodySpace.nearestFree(farGap, 0, 5, 0, 4));
	}
}
