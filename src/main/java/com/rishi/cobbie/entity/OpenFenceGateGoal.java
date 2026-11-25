package com.rishi.cobbie.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Opens a closed fence gate that is in his way, and shuts it behind him.
 *
 * <p>Vanilla's door goal cannot do this: {@code DoorInteractGoal}, which {@code OpenDoorGoal} extends,
 * checks {@code instanceof DoorBlock} and calls {@code DoorBlock.setOpen}, and no vanilla goal handles a
 * {@link FenceGateBlock}. So a closed gate is a wall to him even when his planner is willing to route
 * through it — {@link GateAwareNavigation} supplies the willingness, this supplies the hands.
 *
 * <p>The shape mirrors the door goal deliberately: it only looks while he is actually blocked
 * ({@code horizontalCollision}), it looks at the next few path nodes rather than only at his feet, it
 * re-opens the gate if something shut it again, and it closes the gate once he is clear. A gate held by
 * redstone is left alone — it would not stay open, and the planner refuses to route through it, so the
 * plan stops and names the obstacle instead of walking into a gate that will not budge.
 */
final class OpenFenceGateGoal extends Goal {

	private final Mob mob;
	private final boolean closeBehind;
	private BlockPos gate;

	OpenFenceGateGoal(Mob mob, boolean closeBehind) {
		this.mob = mob;
		this.closeBehind = closeBehind;
	}

	@Override
	public boolean canUse() {
		if (!this.mob.horizontalCollision) {
			return false;
		}
		this.gate = this.closedGateInTheWay();
		return this.gate != null;
	}

	@Override
	public boolean canContinueToUse() {
		if (this.gate == null) {
			return false;
		}
		if (!(this.mob.level().getBlockState(this.gate).getBlock() instanceof FenceGateBlock)) {
			return false;
		}
		// Keep working until he is clear of it, then stop and close up behind him.
		return this.mob.blockPosition().distSqr(this.gate) <= 4;
	}

	@Override
	public void start() {
		this.setOpen(true);
	}

	@Override
	public void tick() {
		if (this.gate == null) {
			return;
		}
		BlockState state = this.mob.level().getBlockState(this.gate);
		if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
			this.setOpen(true); // something shut it while he was walking through
		}
	}

	@Override
	public void stop() {
		if (this.closeBehind && this.gate != null && this.mob.blockPosition().distSqr(this.gate) > 2) {
			this.setOpen(false);
		}
		this.gate = null;
	}

	/**
	 * The nearest closed, unpowered gate he is walking into: the next few path nodes first, because that
	 * is what he planned through, then his own feet and the block he is pushing against, because a gate
	 * node can sit on top of the gate rather than in it.
	 */
	private BlockPos closedGateInTheWay() {
		Path path = this.mob.getNavigation().getPath();
		if (path != null && !path.isDone()) {
			int from = path.getNextNodeIndex();
			for (int i = from; i < Math.min(from + 3, path.getNodeCount()); i++) {
				Node node = path.getNode(i);
				BlockPos at = new BlockPos(node.x, node.y, node.z);
				if (isClosedGate(at)) {
					return at;
				}
				if (isClosedGate(at.above())) {
					return at.above();
				}
			}
		}
		BlockPos feet = this.mob.blockPosition();
		if (isClosedGate(feet)) {
			return feet;
		}
		Vec3 motion = this.mob.getDeltaMovement();
		Direction push = motion.horizontalDistanceSqr() > 1.0e-4
				? Direction.getNearest((int) Math.signum(motion.x), 0, (int) Math.signum(motion.z),
						this.mob.getDirection())
				: this.mob.getDirection();
		BlockPos ahead = feet.relative(push);
		return isClosedGate(ahead) ? ahead : null;
	}

	private boolean isClosedGate(BlockPos at) {
		BlockState state = this.mob.level().getBlockState(at);
		return state.getBlock() instanceof FenceGateBlock
				&& !state.getValue(FenceGateBlock.OPEN)
				&& !state.getValue(FenceGateBlock.POWERED);
	}

	/** The same route the game takes when a player uses a gate: set the state, sound it, report the event. */
	private void setOpen(boolean open) {
		if (this.gate == null) {
			return;
		}
		BlockState state = this.mob.level().getBlockState(this.gate);
		if (!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN) == open) {
			return;
		}
		this.mob.level().setBlock(this.gate, state.setValue(FenceGateBlock.OPEN, open), 10);
		this.mob.level().playSound(null, this.gate,
				open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0f, 1.0f);
		this.mob.level().gameEvent(this.mob, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, this.gate);
	}
}
