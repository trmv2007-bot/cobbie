package com.rishi.cobbie.world;

import java.util.function.DoubleUnaryOperator;

/**
 * What a fall costs, asked of the game instead of guessed.
 *
 * <p>Two behaviours turn on the same question — "would this drop cost me more health than I have?" — and
 * both of them used to be answered by nothing at all: the dig verb broke the block under his feet without
 * looking down, and the pathfinder's own limit for descents is {@code getComfortableFallDistance(health)},
 * which by the game's damage arithmetic is a fall of <em>exactly</em> his health: the last one he would
 * ever take.
 *
 * <p>So the arithmetic lives here, as plain numbers, with the damage function handed in. The game's own
 * {@code calculateFallDamage} is the only implementation that ships (fall distance, jump boost and the
 * fall-damage-immune tag all inside it), and a test can hand in its own to pin the boundary.
 */
public final class Falls {

	/** What a body would meet at one level of a column. */
	public enum Floor { SOLID, WATER, LAVA, AIR }

	/** What stopped the fall, from the inside of the column outward. */
	public enum Landing { GROUND, WATER, LAVA, VOID }

	/**
	 * One level of the column under his feet, described <em>after</em> the block he was standing on is
	 * gone: the caller knows what its own break leaves behind, and this only says what is below.
	 */
	@FunctionalInterface
	public interface Column {
		Floor at(int y);
	}

	/** How far the body would fall, and what it would land in. */
	public record Below(double distance, Landing landing) {}

	/**
	 * A depth no drop needs to consider. Damage grows a block at a time, so a body that survives this far
	 * is fall-immune and the exact number stops mattering.
	 */
	private static final int MAX_DROP = 320;

	private Falls() {}

	/**
	 * Follow the column under {@code feetY} down to the first thing a body would land in or on.
	 *
	 * <p>Distance is measured to the <em>top</em> of the landing block, because that is where his feet
	 * stop: feet at 64 over a solid block at 60 is a three-block drop, not four.
	 */
	public static Below below(int feetY, int fromY, int minY, Column column) {
		for (int y = Math.min(fromY, feetY - 1); y >= minY; y--) {
			switch (column.at(y)) {
				case SOLID -> {
					return new Below(Math.max(0, feetY - (y + 1)), Landing.GROUND);
				}
				case WATER -> {
					return new Below(Math.max(0, feetY - y), Landing.WATER);
				}
				case LAVA -> {
					return new Below(Math.max(0, feetY - y), Landing.LAVA);
				}
				case AIR -> { }
			}
		}
		return new Below(Math.max(0, feetY - minY), Landing.VOID);
	}

	/** The same question for a break that happens right at his feet: scan from the block he stands on. */
	public static Below below(int feetY, int minY, Column column) {
		return below(feetY, feetY - 1, minY, column);
	}

	/**
	 * Is this a fall he would not walk away from? Water is not a fall at all — the game cancels the damage
	 * in it — lava and the void need no arithmetic, and for ground the game's own damage decides. A fall
	 * that costs exactly his health is fatal: the game applies damage at zero health, and dead is dead.
	 */
	public static boolean fatal(Below below, double health, DoubleUnaryOperator damageFor) {
		return switch (below.landing()) {
			case LAVA, VOID -> true;
			case WATER -> false;
			case GROUND -> damageFor.applyAsDouble(below.distance()) >= health;
		};
	}

	/**
	 * The deepest whole-block fall that still leaves him standing: the largest distance whose damage is
	 * strictly less than his health.
	 *
	 * <p>This is the number {@code Mob.getMaxFallDistance} should be answering. The game's answer is
	 * {@code floor(health + 3)}, and with the game's own damage arithmetic that is a fall costing exactly
	 * his health — so a pathfinder given health 20 will happily plan a 23-block drop that kills him.
	 */
	public static int safeDrop(double health, DoubleUnaryOperator damageFor) {
		int deepest = 0;
		for (int d = 0; d <= MAX_DROP; d++) {
			if (damageFor.applyAsDouble(d) >= health) return Math.max(0, d - 1);
			deepest = d;
		}
		return deepest;
	}
}
