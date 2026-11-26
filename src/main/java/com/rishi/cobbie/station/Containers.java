package com.rishi.cobbie.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What he can do with a container, without ever opening a menu.
 *
 * <p>Every chest, barrel, shulker box and furnace in the game is a {@link Container}: a fixed set of
 * slots with get, set and remove. Opening one as a player is what needs a {@code Player} and an
 * {@code AbstractContainerMenu}; moving items does not. So this is the whole of his station support —
 * a resolution step that finds the real container behind a block, and two transfers that report how
 * much actually moved so the caller can verify the world instead of trusting a return code.
 *
 * <p>Deliberately written against {@link Container} and not against Cobbie's own bag: the 36-slot
 * inventory is a separate migration, and code that assumed his nine slots would have to be rewritten
 * for it.
 */
public final class Containers {

	/** Furnace slot order is fixed by the block entity, and its constants are protected. */
	public static final int FURNACE_INPUT = 0;
	public static final int FURNACE_FUEL = 1;
	public static final int FURNACE_RESULT = 2;

	private Containers() {}

	/**
	 * The container behind a block, or null when there is none.
	 *
	 * <p>A double chest is the interesting case: each half is its own block entity, and
	 * {@link ChestBlock#getContainer} is what merges them into the one inventory a player sees.
	 * An ender chest has no storage of its own at all — it is the owner's own chest, which is why it
	 * needs the owner passed in and its own permission.
	 *
	 * @param owner the owner, used only for an ender chest; may be null
	 * @param enderChestAllowed whether the owner's ender chest may be touched
	 */
	public static Container resolve(ServerLevel level, BlockPos pos, ServerPlayer owner, boolean enderChestAllowed) {
		if (level == null || pos == null || !level.isLoaded(pos)) return null;
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		if (state.is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
			return enderChestAllowed && owner != null ? owner.getEnderChestInventory() : null;
		}
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity instanceof Container container) return container;
		return null;
	}

	/** True when the block has storage at all, whether or not he is allowed to touch it. */
	public static boolean hasStorage(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos)) return false;
		BlockEntity entity = level.getBlockEntity(pos);
		return entity instanceof Container;
	}

	/** A container's name for his report, never a coordinate. */
	public static String nameOf(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) return "container";
		BlockState state = level.getBlockState(pos);
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().replace('_', ' ');
	}

	/** Furnaces, smokers and blast furnaces all share the input/fuel/result layout. */
	public static boolean isFurnace(Container container) {
		return container instanceof AbstractFurnaceBlockEntity;
	}

	/** Barrels and shulkers accept anything in every slot; a furnace does not. */
	public static boolean isPlainStorage(Container container) {
		return container instanceof BarrelBlockEntity || container instanceof ShulkerBoxBlockEntity;
	}

	/**
	 * Put as much of {@code stack} into {@code container} as it will take, mutating {@code stack} to
	 * whatever is left over.
	 *
	 * @return how many items moved, which is what the caller verifies against
	 */
	public static int insert(Container container, ItemStack stack) {
		if (container == null || stack == null || stack.isEmpty()) return 0;
		int moved = 0;
		int slots = container.getContainerSize();

		// Top up matching stacks first, exactly as a player's shift-click does.
		for (int i = 0; i < slots && !stack.isEmpty(); i++) {
			if (!container.canPlaceItem(i, stack)) continue;
			ItemStack existing = container.getItem(i);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
			int space = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing)) - existing.getCount();
			if (space <= 0) continue;
			int take = Math.min(space, stack.getCount());
			existing.grow(take);
			stack.shrink(take);
			moved += take;
		}

		// Then fill empty slots.
		for (int i = 0; i < slots && !stack.isEmpty(); i++) {
			if (!container.canPlaceItem(i, stack)) continue;
			if (!container.getItem(i).isEmpty()) continue;
			int take = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), container.getMaxStackSize(stack)));
			container.setItem(i, stack.split(take));
			moved += take;
		}
		if (moved > 0) container.setChanged();
		return moved;
	}

	/**
	 * Move up to {@code limit} items matching {@code wantedId} out of a container and into
	 * {@code bag}. A blank {@code wantedId} means "whatever is there", which is what "empty that
	 * chest" means.
	 *
	 * @return how many items actually moved
	 */
	public static int extract(Container container, String wantedId, int limit, Container bag) {
		if (container == null || bag == null || limit <= 0) return 0;
		int moved = 0;
		boolean furnace = isFurnace(container);
		for (int i = 0; i < container.getContainerSize() && moved < limit; i++) {
			// Never take a furnace's burning fuel or its un-smelted input: a companion who empties a
			// lit furnace because you said "take the iron" is worse than one who waits.
			if (furnace && (i == FURNACE_FUEL || i == FURNACE_INPUT)) continue;
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty()) continue;
			if (!matchesId(wantedId, stack)) continue;
			int take = Math.min(limit - moved, stack.getCount());
			ItemStack trial = stack.copyWithCount(take);
			int before = trial.getCount();
			addToBag(trial, bag);
			int fit = before - trial.getCount();
			if (fit <= 0) break;
			container.removeItem(i, fit);
			moved += fit;
		}
		if (moved > 0) container.setChanged();
		return moved;
	}

	/**
	 * How much of {@code wantedId} the container holds, without moving anything.
	 *
	 * <p>Asked by the planner when a step's turn comes, so "the chest has no coal in it any more" is a
	 * fact read off the chest rather than an inference from a failure. A furnace's fuel and input slots
	 * are skipped for the same reason {@link #extract} skips them: a burning coal is not something he may
	 * count as taking.
	 */
	public static int count(Container container, String wantedId) {
		if (container == null) return 0;
		boolean furnace = isFurnace(container);
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (furnace && (i == FURNACE_FUEL || i == FURNACE_INPUT)) continue;
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !matchesId(wantedId, stack)) continue;
			total += stack.getCount();
		}
		return total;
	}

	/**
	 * Registry-id match tolerant of how the model writes item names: "cobblestone",
	 * "minecraft:cobblestone" and "mine craft:cobble stone" all have to land on the same item.
	 */
	static boolean matchesId(String wanted, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		if (wanted == null || wanted.isBlank()) return true;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String ask = wanted.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
		if (ask.contains(":")) return ask.equals(id);
		return id.equals(ask) || id.endsWith(":" + ask);
	}

	/** Store a stack in a plain container, mutating what did not fit. */
	static void addToBag(ItemStack stack, Container bag) {
		if (stack == null || stack.isEmpty() || bag == null) return;
		for (int i = 0; i < bag.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack existing = bag.getItem(i);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
			int space = Math.min(existing.getMaxStackSize(), bag.getMaxStackSize(existing)) - existing.getCount();
			if (space <= 0) continue;
			int move = Math.min(space, stack.getCount());
			existing.grow(move);
			stack.shrink(move);
		}
		for (int i = 0; i < bag.getContainerSize() && !stack.isEmpty(); i++) {
			if (!bag.getItem(i).isEmpty()) continue;
			int move = Math.min(stack.getCount(), stack.getMaxStackSize());
			bag.setItem(i, stack.split(move));
		}
	}

	/** Fuel for a furnace, judged by the component the game itself reads. */
	public static boolean isFuel(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.has(DataComponents.COOKING_FUEL);
	}
}
