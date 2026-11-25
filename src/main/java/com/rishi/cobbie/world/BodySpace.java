package com.rishi.cobbie.world;

/**
 * Where a body of his size actually fits.
 *
 * <p>He is 0.6 by 1.2 blocks, so a position takes two free blocks: the one his feet are in and the one
 * his head is in. A one-block gap is not a place he can stand, and a destination inside a wall is a
 * destination that gets him killed when the walk drives him into it. Both questions — "does he fit
 * here" and "where is the nearest place he does" — are asked with plain coordinates and one predicate,
 * no Minecraft types, so the rule is a unit test rather than something only an in-game run can check.
 */
public final class BodySpace {

	/** Does the block at this position have a collision shape? */
	@FunctionalInterface
	public interface Solid {
		boolean solidAt(int x, int y, int z);
	}

	private BodySpace() {}

	/** Feet block and head block both free: the column a body occupies, and nothing else. */
	public static boolean fits(Solid solid, int x, int y, int z) {
		return !solid.solidAt(x, y, z) && !solid.solidAt(x, y + 1, z);
	}

	/**
	 * The nearest column he can stand in — two free blocks with solid ground under them — searching
	 * outward from where he is. Rings are taken nearest first so the answer never teleports him across a
	 * wall when a closer gap exists, and vertical moves cost extra so he is not dropped down a shaft when
	 * the same distance sideways would do.
	 *
	 * @return {x, y, z} of the column's feet, or null when there is nothing free within {@code radius}
	 */
	public static int[] nearestFree(Solid solid, int x, int y, int z, int radius) {
		for (int ring = 1; ring <= Math.max(1, radius); ring++) {
			int[] best = null;
			double bestCost = Double.MAX_VALUE;
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					for (int dy = -ring; dy <= ring; dy++) {
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != ring) continue;
						int px = x + dx;
						int py = y + dy;
						int pz = z + dz;
						if (!fits(solid, px, py, pz)) continue;
						if (!solid.solidAt(px, py - 1, pz)) continue;
						double cost = dx * dx + dz * dz + 0.5 * dy * dy;
						if (cost < bestCost) {
							bestCost = cost;
							best = new int[] {px, py, pz};
						}
					}
				}
			}
			if (best != null) return best;
		}
		return null;
	}
}
