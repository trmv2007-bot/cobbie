package com.rishi.cobbie.actor;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Do it the way a player would" — without putting a player in his world.
 *
 * <h2>Why this exists</h2>
 * Every right-click in the game takes a {@code Player} as the actor: {@code ItemStack.use},
 * {@code ItemStack.useOn}, {@code ServerPlayerGameMode.useItemOn}, and the block methods
 * {@code useItemOn}/{@code useWithoutItem} that levers, buttons, crops and bone meal implement. Cobbie
 * is a {@code PathfinderMob}, so all of it is out of reach unless something plays the part of the
 * player. This class is that something, and it is the only place in the mod that pretends.

 * <h2>Why Fabric's {@code FakePlayer}</h2>
 * It is the supported shape of "a player who is not there", and the reason is the sharp edges it
 * already smooths. A hand-built {@code ServerPlayer} has a null connection, so any code path that
 * sends the player a message or opens a menu would throw — and {@code useItemOn} does both: it can
 * open a container's menu and it sends overlay messages. Fabric's {@code FakePlayer} installs a
 * do-nothing connection, returns an empty {@code OptionalInt} from {@code openMenu}, and no-ops
 * {@code awardStat}, {@code startSleeping}, {@code startRiding} and {@code openTextEdit}. It is
 * cached one per level, so it also cannot accumulate.
 *
 * <h2>What still leaks, and what the caller must do</h2>
 * <ul>
 *   <li><b>Other mods see him.</b> A {@code FakePlayer} is a real {@code ServerPlayer} on the event
 *       bus, so a mod listening to a use-block callback sees an actor it did not expect. Nothing
 *       distinguishes him from a player with an odd name except the profile, which is
 *       {@code [Cobbie]}.</li>
 *   <li><b>Advancement triggers fire.</b> {@code useItemOn} calls
 *       {@code ItemUsedOnLocationTrigger}/{@code DefaultBlockInteractionTrigger} for the fake profile.
 *       Those live in memory and are never saved, because he is not in the player list, but they are
 *       called.</li>
 *   <li><b>His hand is shared, cached state.</b> The item is put in the fake player's hand for the
 *       call and taken out again afterwards, always, even when the call throws. The caller owns the
 *       stack before and after: whatever the game changed (a stack spent, a durability point lost)
 *       comes back in {@link Outcome#remaining()} and must be written into Cobbie's bag.</li>
 * </ul>
 *
 * <p>He is never added to the world, never ticked, never saved, and never given a chunk ticket: the
 * only thing he does is stand where Cobbie is standing for the duration of one call.
 */
public final class FakeActor {

	/**
	 * @param result   what the game decided (success, consume, pass…)
	 * @param remaining the held stack after the call — spent, damaged, or replaced
	 */
	public record Outcome(InteractionResult result, ItemStack remaining) {}

	private FakeActor() {}

	/** Right-click a block: seeds on farmland, bone meal on a crop, a lever, a button, a bucket. */
	public static Outcome useOnBlock(CobbieEntity body, BlockPos pos, Direction face, ItemStack held) {
		if (!(body.level() instanceof ServerLevel level)) return null;
		FakePlayer actor = place(body, level, held);
		try {
			Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
			BlockHitResult where = new BlockHitResult(hit, face, pos, false);
			InteractionResult result = actor.gameMode.useItemOn(actor, level,
					actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, where);
			return new Outcome(result, takeBack(actor));
		} catch (RuntimeException e) {
			takeBack(actor);
			throw e;
		}
	}

	/** Right-click with nothing in front of him: a bucket into air, a potion, a bow being drawn. */
	public static Outcome useInAir(CobbieEntity body, ItemStack held) {
		if (!(body.level() instanceof ServerLevel level)) return null;
		FakePlayer actor = place(body, level, held);
		try {
			InteractionResult result = actor.gameMode.useItem(actor, level,
					actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			return new Outcome(result, takeBack(actor));
		} catch (RuntimeException e) {
			takeBack(actor);
			throw e;
		}
	}

	/** Stand the actor where the body is and put the stack in his hand. */
	private static FakePlayer place(CobbieEntity body, ServerLevel level, ItemStack held) {
		if (level == null) return null;
		FakePlayer actor = FakePlayer.get(level);
		actor.setPos(body.getX(), body.getY(), body.getZ());
		actor.setYRot(body.getYRot());
		actor.setXRot(body.getXRot());
		actor.setItemInHand(InteractionHand.MAIN_HAND, held == null ? ItemStack.EMPTY : held.copy());
		return actor;
	}

	/** Clear the fake player's hand and hand back what is in it. */
	private static ItemStack takeBack(FakePlayer actor) {
		ItemStack left = actor.getItemInHand(InteractionHand.MAIN_HAND);
		actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		return left == null ? ItemStack.EMPTY : left;
	}
}
