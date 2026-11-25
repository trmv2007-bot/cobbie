package com.rishi.cobbie.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * His navigation, with one change: a closed fence gate is priced as a door he can open, not as a fence.
 *
 * <p>Vanilla's {@link WalkNodeEvaluator#getPathTypeFromState} returns {@code PathType.FENCE} for a
 * {@link FenceGateBlock} whose {@code OPEN} is false, so A* treats every closed gate as a wall and no
 * route ever goes through one. That is why a walled fence ends a walk that a house with a door does
 * not: doors are priced as closed wooden doors, gates are not. This evaluator prices an unpowered
 * closed gate the same way it already prices a closed wooden door, so the route exists.
 *
 * <p><b>A route is not a way through.</b> The block is still closed on the server, and vanilla has no
 * goal that opens one ({@code DoorInteractGoal}, the base of the door goal, only ever handles
 * {@code DoorBlock}). {@link OpenFenceGateGoal} is the other half; a planner willing to route through a
 * gate nothing opens would just leave him pushing at it until the plan times out.
 *
 * <p>This is a subclass, not a mixin: only his own navigation uses it, so no other mob in the world
 * changes what it can walk through. It works because {@code WalkNodeEvaluator} asks itself for a block's
 * path type through two virtual methods ({@code getCachedPathType} and {@code getPathType}), both of
 * which a subclass can override; the {@code static} helper that computes the vanilla answer cannot be
 * overridden, which is why the check is done here rather than there.
 */
final class GateAwareNavigation extends GroundPathNavigation {

	GateAwareNavigation(Mob mob, Level level) {
		super(mob, level);
	}

	@Override
	protected PathFinder createPathFinder(int maxVisitedNodes) {
		this.nodeEvaluator = new GateAwareNodeEvaluator();
		return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
	}

	/** A walk evaluator that treats a closed, unpowered fence gate as a closed wooden door. */
	private static final class GateAwareNodeEvaluator extends WalkNodeEvaluator {

		@Override
		protected PathType getCachedPathType(int x, int y, int z) {
			PathType type = super.getCachedPathType(x, y, z);
			return type == PathType.FENCE ? openableGate(this.currentContext.level(), x, y, z) : type;
		}

		@Override
		public PathType getPathType(PathfindingContext context, int x, int y, int z) {
			PathType type = super.getPathType(context, x, y, z);
			return type == PathType.FENCE ? openableGate(context.level(), x, y, z) : type;
		}

		/**
		 * Fence, wall, or a gate that is already open or held by redstone stays exactly as vanilla priced
		 * it. Only a gate he could open himself becomes a closed wooden door, which he already walks
		 * through and which {@link OpenFenceGateGoal} opens.
		 */
		private static PathType openableGate(BlockGetter level, int x, int y, int z) {
			BlockState state = level.getBlockState(new BlockPos(x, y, z));
			if (!(state.getBlock() instanceof FenceGateBlock)) {
				return PathType.FENCE;
			}
			if (state.getValue(FenceGateBlock.OPEN) || state.getValue(FenceGateBlock.POWERED)) {
				return PathType.FENCE;
			}
			return PathType.DOOR_WOOD_CLOSED;
		}
	}
}
