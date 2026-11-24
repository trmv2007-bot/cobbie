package com.rishi.cobbie.actor;

import com.rishi.cobbie.entity.CobbieEntity;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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

	/**
	 * His identity on the event bus: a fixed id so anything that logs or filters him sees the same
	 * actor every time, and a name that says what he is. The id is derived from a constant string, not
	 * random, so it is stable across worlds and a test can look for his player data by name.
	 */
	public static final UUID PROFILE_ID = UUID.nameUUIDFromBytes("cobbie:actor".getBytes(StandardCharsets.UTF_8));
	public static final String PROFILE_NAME = "[Cobbie]";
	private static final GameProfile PROFILE = new GameProfile(PROFILE_ID, PROFILE_NAME);

	/**
	 * What came of driving the enchanting table: the enchanted item, and where his level ended up.
	 *
	 * @param reason why it failed, or null
	 */
	public record EnchantOutcome(boolean success, String reason, ItemStack remaining, int levelAfter,
			float progressAfter, int levelsSpent) {}

	private FakeActor() {}

	/**
	 * Enchant an item at a real table, through the real menu.
	 *
	 * <p>{@code EnchantmentMenu} wants a {@code Player.Inventory}, which is the one thing the actor has
	 * that a mob does not. His item goes in slot 0, his lapis in slot 1, his level into the actor before
	 * {@code clickMenuButton} and back out afterwards — and the price is read from the menu's own
	 * {@code costs[]} first, so a refusal quotes the table rather than a constant of mine.
	 */
	public static EnchantOutcome enchant(CobbieEntity body, ServerLevel level, BlockPos table, ItemStack item,
			int lapis, int xpLevel, float progress, int tier) {
		FakePlayer actor = place(body, level, ItemStack.EMPTY, null);
		try {
			var access = net.minecraft.world.inventory.ContainerLevelAccess.create(level, table);
			var menu = new net.minecraft.world.inventory.EnchantmentMenu(0, actor.getInventory(), access);
			menu.getSlot(0).set(item.copyWithCount(1));
			menu.getSlot(1).set(new ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI, Math.max(1, lapis)));
			// What vanilla actually charges: the button's own index + 1, which is also the lapis cost.
			// `costs[]` is the *enchantment power* the option rolls with, not its price — proven by
			// putting a real player at level 4 in front of the same table, where a cost of 2 charged one
			// level (4 -> 3), exactly as this path does.
			int slot = Math.max(0, Math.min(2, tier));
			int price = slot + 1;
			if (menu.costs[slot] <= 0) {
				return new EnchantOutcome(false, "the table is not offering that", item, xpLevel, progress, 0);
			}
			if (xpLevel < price) {
				return new EnchantOutcome(false, "I need " + price + " levels for that and I have " + xpLevel,
						item, xpLevel, progress, 0);
			}
			// FakePlayer starts with creative-style abilities, and a menu that sees instabuild does not
			// charge for anything: the first run of this test enchanted a sword for free. He has to be
			// made to pay like anyone else.
			boolean wasInstabuild = actor.getAbilities().instabuild;
			actor.getAbilities().instabuild = false;
			actor.experienceLevel = xpLevel;
			actor.experienceProgress = progress;
			boolean did = menu.clickMenuButton(actor, slot);
			int after = actor.experienceLevel;
			float afterProgress = actor.experienceProgress;
			ItemStack out = menu.getSlot(0).getItem().copy();
			menu.getSlot(0).set(ItemStack.EMPTY);
			menu.getSlot(1).set(ItemStack.EMPTY);
			actor.experienceLevel = 0;
			actor.experienceProgress = 0.0f;
			actor.getAbilities().instabuild = wasInstabuild;
			if (!did) return new EnchantOutcome(false, "the table refused", item, xpLevel, progress, 0);
			if (out.isEmpty() || out.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS) == null) {
				return new EnchantOutcome(false, "nothing was enchanted", item, xpLevel, progress, 0);
			}
			return new EnchantOutcome(true, null, out, after, afterProgress, xpLevel - after);
		} catch (RuntimeException e) {
			return new EnchantOutcome(false, "the table went wrong (" + e.getClass().getSimpleName() + ")",
					item, xpLevel, progress, 0);
		}
	}

	/** Right-click a block: seeds on farmland, bone meal on a crop, a lever, a button. */
	public static Outcome useOnBlock(CobbieEntity body, BlockPos pos, Direction face, ItemStack held) {
		if (!(body.level() instanceof ServerLevel level)) return null;
		FakePlayer actor = place(body, level, held, pos);
		try {
			Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
			BlockHitResult where = new BlockHitResult(hit, face, pos, false);
			InteractionResult result = actor.gameMode.useItemOn(actor, level,
					actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, where);
			return new Outcome(result, actor.getItemInHand(InteractionHand.MAIN_HAND).copy());
		} finally {
			// Nothing may survive the call, including a throwing one: the actor is cached per level and
			// its hand is shared state.
			clearHand(actor);
		}
	}

	/**
	 * Right-click with an item that acts on its own: a bucket (which raycasts for fluid from the eye in
	 * this version rather than using a block), a potion, a bow being drawn.
	 *
	 * @param aimAt the block to look at, or null to keep the body's own facing
	 */
	public static Outcome useInAir(CobbieEntity body, ItemStack held, BlockPos aimAt) {
		if (!(body.level() instanceof ServerLevel level)) return null;
		FakePlayer actor = place(body, level, held, aimAt);
		try {
			InteractionResult result = actor.gameMode.useItem(actor, level,
					actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			return new Outcome(result, actor.getItemInHand(InteractionHand.MAIN_HAND).copy());
		} finally {
			clearHand(actor);
		}
	}

	/** What came of using an anvil: the item that came off it, and where his level ended up. */
	public record AnvilOutcome(boolean success, String reason, ItemStack remaining, int levelAfter,
			float progressAfter, int levelsSpent) {}
	/**
	 * Take the enchantments off an item at a real grindstone.
	 *
	 * <p>Nothing is charged here: in 26.3 {@code GrindstoneMenu} holds no XP anywhere — its
	 * {@code removed} only clears the input slots — so the menu is a pure item transformation and the
	 * take is a real click on its result slot. {@code computeResult} decides what is legal: a single
	 * enchanted item comes back with its non-curse enchantments stripped, and an item with none comes
	 * back as nothing at all, which is how a refusal here stays the game's.
	 */
	public static Outcome grind(CobbieEntity body, ServerLevel level, BlockPos stone, ItemStack first) {
		FakePlayer actor = place(body, level, ItemStack.EMPTY, null);
		try {
			var access = net.minecraft.world.inventory.ContainerLevelAccess.create(level, stone);
			var menu = new net.minecraft.world.inventory.GrindstoneMenu(0, actor.getInventory(), access);
			menu.getSlot(0).set(first.copyWithCount(1));
			menu.getSlot(1).set(ItemStack.EMPTY);
			ItemStack offered = menu.getSlot(2).getItem().copy();
			if (offered.isEmpty()) {
				return new Outcome(InteractionResult.FAIL, first);
			}
			menu.clicked(2, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, actor);
			ItemStack taken = menu.getCarried().copy();
			menu.setCarried(ItemStack.EMPTY);
			menu.getSlot(0).set(ItemStack.EMPTY);
			menu.getSlot(1).set(ItemStack.EMPTY);
			return new Outcome(taken.isEmpty() ? InteractionResult.FAIL : InteractionResult.SUCCESS, taken);
		} catch (RuntimeException e) {
			return new Outcome(InteractionResult.FAIL, first);
		}
	}

	/**
	 * Right-click an entity: shears on a sheep, a bucket on a cow, a lead on a horse.
	 *
	 * <p>{@code Player.interactOn} is the vanilla entry point and it is public, so this is the same
	 * gesture a player makes — not a reimplementation of shearing. The actor is aimed at the entity's
	 * own centre, because the item's behaviour can depend on where it was clicked.
	 *
	 * <p>What still leaks: {@code Mob.interact} fires {@code GameEvent.ENTITY_INTERACT} with him as
	 * the source, and a lead handed to him would leash the animal to a player who is never in the
	 * world. Anything the caller does not want leashed to the actor has to be handled before this.
	 */
	public static Outcome useOnEntity(CobbieEntity body, net.minecraft.world.entity.LivingEntity target,
			ItemStack held) {
		if (!(body.level() instanceof ServerLevel level)) return null;
		FakePlayer actor = place(body, level, held, target.blockPosition());
		try {
			Vec3 at = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
			InteractionResult result = actor.interactOn(target, InteractionHand.MAIN_HAND, at);
			return new Outcome(result, actor.getItemInHand(InteractionHand.MAIN_HAND).copy());
		} finally {
			clearHand(actor);
		}
	}

	/**
	 * Put an enchanted book onto an item at a real anvil.
	 *
	 * <p>The take is what charges: the menu's own result slot is clicked the way a player clicks it
	 * ({@code clicked(RESULT_SLOT, PICKUP)}), so the level cost, the {@code mayPickup} refusal and the
	 * enchantment merging are the game's. The actor's level goes in before the click and comes back out
	 * afterwards, and instabuild is stripped for the call because {@code mayPickup} reads it.
	 */
	public static AnvilOutcome applyBook(CobbieEntity body, ServerLevel level, BlockPos anvil, ItemStack item,
			ItemStack book, int xpLevel, float progress) {
		FakePlayer actor = place(body, level, ItemStack.EMPTY, null);
		try {
			var access = net.minecraft.world.inventory.ContainerLevelAccess.create(level, anvil);
			var menu = new net.minecraft.world.inventory.AnvilMenu(0, actor.getInventory(), access);
			menu.getSlot(0).set(item.copyWithCount(1));
			menu.getSlot(1).set(book.copyWithCount(1));
			// His level has to be in place *before* the result is worked out: the anvil decides what to
			// offer against the player's own level, so an actor at zero makes it offer nothing.
			boolean wasInstabuild = actor.getAbilities().instabuild;
			actor.getAbilities().instabuild = false;
			actor.experienceLevel = xpLevel;
			actor.experienceProgress = progress;
			menu.createResult();

			ItemStack offered = menu.getSlot(2).getItem().copy();
			if (offered.isEmpty()) {
				actor.getAbilities().instabuild = wasInstabuild;
				return new AnvilOutcome(false, "the anvil will not put that book on that", item, xpLevel,
						progress, 0);
			}
			menu.clicked(2, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, actor);
			ItemStack taken = menu.getCarried().copy();
			int after = actor.experienceLevel;
			float afterProgress = actor.experienceProgress;
			menu.setCarried(ItemStack.EMPTY);
			actor.experienceLevel = 0;
			actor.experienceProgress = 0.0f;
			actor.getAbilities().instabuild = wasInstabuild;
			menu.getSlot(0).set(ItemStack.EMPTY);
			menu.getSlot(1).set(ItemStack.EMPTY);

			if (taken.isEmpty()) {
				return new AnvilOutcome(false, "I need more levels for that than I have (" + xpLevel + ")",
						item, xpLevel, progress, 0);
			}
			if (taken.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS) == null) {
				return new AnvilOutcome(false, "that book put no enchantment on it", item, xpLevel, progress, 0);
			}
			return new AnvilOutcome(true, null, taken, after, afterProgress, xpLevel - after);
		} catch (RuntimeException e) {
			return new AnvilOutcome(false, "the anvil went wrong (" + e.getClass().getSimpleName() + ")",
					item, xpLevel, progress, 0);
		}
	}

	/**
	 * Stand the actor where the body is and put the stack in his hand.
	 *
	 * <p>Position and rotation are set on <em>every</em> call, because {@link FakePlayer#get} caches
	 * one actor per level: a stale position would run a block interaction from wherever the previous
	 * call left him, which reads as Cobbie acting at a distance.
	 */
	private static FakePlayer place(CobbieEntity body, ServerLevel level, ItemStack held, BlockPos aimAt) {
		if (level == null) return null;
		FakePlayer actor = FakePlayer.get(level, PROFILE);
		actor.setPos(body.getX(), body.getY(), body.getZ());
		if (aimAt == null) {
			actor.setYRot(body.getYRot());
			actor.setXRot(body.getXRot());
		} else {
			// Aim, because an item that raycasts from the eye (a bucket, most obviously) finds nothing
			// unless it is actually looking at the block.
			Vec3 eye = body.getEyePosition();
			double dx = aimAt.getX() + 0.5 - eye.x;
			double dy = aimAt.getY() + 0.5 - eye.y;
			double dz = aimAt.getZ() + 0.5 - eye.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			actor.setYRot((float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
			actor.setXRot((float) (-(Math.atan2(dy, flat) * 180.0 / Math.PI)));
		}
		actor.setItemInHand(InteractionHand.MAIN_HAND, held == null ? ItemStack.EMPTY : held.copy());
		return actor;
	}

	private static void clearHand(FakePlayer actor) {
		actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
	}
}
