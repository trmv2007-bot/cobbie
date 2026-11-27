package com.rishi.cobbie.safety;

/**
 * When an animal is food and when it is somebody's, as one rule rather than a heuristic.
 *
 * <p>He could not hunt at all until 0.88: {@code isLegalTarget} was hostiles-only, so a companion with a
 * hunger bar had no way to fill it and starved beside a herd. Teaching it "animals are targets now" would
 * have been the opposite defect — a companion who kills your cow because it is made of meat.
 *
 * <p><b>The rule, in one sentence:</b> he may take an animal only while he is hungry and carrying nothing
 * to eat, and never one that is someone's pet or standing in a pen.
 *
 * <p>Each clause is a thing the game can answer, not an opinion. "Hungry with nothing to eat" is the
 * hunger bar and the bag's own food components, the same numbers his eating policy uses. "Someone's pet"
 * is {@code OwnableEntity.getOwner} — the check that already protected tamed wolves. "In a pen" is the
 * only clause that has to look, so it is stated as exactly what it looks at: a fence, fence gate or wall
 * within {@link #PEN_RADIUS} blocks of where the animal is standing. That is as close as a block-state
 * rule can come to "a pen the owner built" — he cannot see who placed a fence — and the limit is named
 * here rather than hidden: a large pen's middle reads as open ground.
 */
public final class Hunting {

	/** How far from the animal a fence, gate or wall still means "kept". */
	public static final int PEN_RADIUS = 8;

	/** What he says when an animal is there and he may not take it. */
	public static final String RULE = "I only hunt when I am hungry and carrying nothing to eat, and never"
			+ " someone's pet or an animal in a pen";

	/** A block that means somebody built a boundary here. */
	@FunctionalInterface
	public interface Barrier {
		boolean at(int x, int y, int z);
	}

	private Hunting() {}

	/**
	 * Whether there is a barrier within {@code radius} of this square, searching the animal's own level.
	 *
	 * <p>Plain coordinates and one predicate, so the rule is a unit test: the part that decides "kept" is
	 * arithmetic over a block lookup and nothing else.
	 */
	public static boolean inPen(Barrier barrier, int x, int y, int z, int radius) {
		if (barrier == null) return false;
		int reach = Math.max(1, radius);
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -reach; dz <= reach; dz++) {
					if (barrier.at(x + dx, y + dy, z + dz)) return true;
				}
			}
		}
		return false;
	}
}
