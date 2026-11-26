package com.rishi.cobbie.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * Falls — what a drop costs, with the game's arithmetic handed in.
 *
 * <p>No world here. The damage function is the game's own {@code calculateFallDamage} in the entity, and
 * this file hands in the same shape so the boundary — a fall that costs exactly his health is fatal — is
 * pinned by a unit test rather than only by the block he refused to dig in a real world.
 *
 * <p>The fake below is the game's rule: nothing for the first three blocks, then a block of damage each.
 */
class FallsTest {

	/** Vanilla's shape: ceil(distance - 3) with no jump boost and no immunity. */
	private static final DoubleUnaryOperator DAMAGE = distance ->
			distance <= 3.0 ? 0.0 : Math.ceil(distance - 3.0);

	private static Falls.Column column(int groundAt, Falls.Floor aboveGround) {
		return y -> y == groundAt ? Falls.Floor.SOLID : aboveGround;
	}

	@Test
	void aFloorDirectlyUnderTheBreakIsNotAFallAtAll() {
		// Feet at 64, the break at 63, stone at 62: he lands on top of 62, so he falls one block.
		Falls.Below below = Falls.below(64, -64, y -> y == 62 ? Falls.Floor.SOLID : Falls.Floor.AIR);
		assertEquals(Falls.Landing.GROUND, below.landing());
		assertEquals(1.0, below.distance());
		assertFalse(Falls.fatal(below, 20.0, DAMAGE));
	}

	@Test
	void theDistanceIsMeasuredToTheTopOfWhatHeLandsOn() {
		// Feet at 64, nothing until stone at 60: he stops at 61 and has fallen three blocks, not four.
		Falls.Below below = Falls.below(64, -64, column(60, Falls.Floor.AIR));
		assertEquals(3.0, below.distance());
		assertFalse(Falls.fatal(below, 20.0, DAMAGE), "three blocks is exactly the free fall");
	}

	@Test
	void waterEndsTheFallWithoutDamage() {
		Falls.Below below = Falls.below(64, -64, column(-64, Falls.Floor.WATER));
		assertEquals(Falls.Landing.WATER, below.landing());
		assertFalse(Falls.fatal(below, 1.0, DAMAGE));
	}

	@Test
	void lavaIsFatalWhateverHisHealth() {
		Falls.Below below = Falls.below(64, -64, column(-64, Falls.Floor.LAVA));
		assertEquals(Falls.Landing.LAVA, below.landing());
		assertTrue(Falls.fatal(below, 20.0, DAMAGE));
	}

	@Test
	void anEmptyColumnIsTheVoidAndThatIsFatal() {
		Falls.Below below = Falls.below(64, 0, y -> Falls.Floor.AIR);
		assertEquals(Falls.Landing.VOID, below.landing());
		assertTrue(Falls.fatal(below, 20.0, DAMAGE));
	}

	@Test
	void aFallCostingExactlyHisHealthIsFatal() {
		// Twenty blocks of fall is 17 damage: survivable on 20. Twenty-three is 20 damage: not.
		Falls.Below survivable = Falls.below(64, -64, column(43, Falls.Floor.AIR));
		Falls.Below fatal = Falls.below(64, -64, column(40, Falls.Floor.AIR));
		assertEquals(20.0, survivable.distance());
		assertEquals(23.0, fatal.distance());
		assertFalse(Falls.fatal(survivable, 20.0, DAMAGE));
		assertTrue(Falls.fatal(fatal, 20.0, DAMAGE));
	}

	@Test
	void theComfortRuleWouldHaveAllowedTheFallThatKills() {
		// The game's own limit is floor(health + 3) = 23, and the game's own damage at 23 is exactly the
		// health he has. That coincidence is the defect this number replaces.
		int vanilla = (int) Math.floor(20.0 + 3.0);
		assertEquals(20.0, DAMAGE.applyAsDouble(vanilla), "the game's comfort distance costs his whole bar");
		assertEquals(22, Falls.safeDrop(20.0, DAMAGE));
		assertTrue(DAMAGE.applyAsDouble(Falls.safeDrop(20.0, DAMAGE)) < 20.0);
	}

	@Test
	void theSafeDropShrinksWithHisHealth() {
		assertEquals(10, Falls.safeDrop(8.0, DAMAGE));
		assertEquals(3, Falls.safeDrop(1.0, DAMAGE), "one health may still take the free three blocks");
	}

	@Test
	void aBodyThatTakesNoFallDamageIsLimitedOnlyByTheSearch() {
		assertTrue(Falls.safeDrop(20.0, distance -> 0.0) > 100);
	}

	@Test
	void zeroHealthAsksForNoDropAtAllRatherThanANegativeOne() {
		assertEquals(0, Falls.safeDrop(0.0, distance -> 0.0));
		assertEquals(0, Falls.safeDrop(0.0, DAMAGE));
	}

	@Test
	void theScanStopsAtTheFloorItWouldHitFirst() {
		Falls.Column layered = y -> switch (y) {
			case 55 -> Falls.Floor.LAVA;
			case 40 -> Falls.Floor.WATER;
			case 30 -> Falls.Floor.SOLID;
			default -> Falls.Floor.AIR;
		};
		Falls.Below below = Falls.below(64, -64, layered);
		assertEquals(Falls.Landing.LAVA, below.landing(), "the lava is reached before the water");
		assertEquals(9.0, below.distance());
	}
}
