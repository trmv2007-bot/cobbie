package com.rishi.cobbie.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

import java.util.Optional;

/**
 * What a brewing stand does, without opening its menu.
 *
 * <p>A stand is not a menu-shaped machine the way an anvil is. It is a {@code Container} that ticks
 * itself: put bottles in slots 0-2, one reagent in slot 3 and fuel in slot 4, and
 * {@code BrewingStandBlockEntity.serverTick} charges the fuel, runs the twenty-second brew and
 * rewrites the bottles. A {@code BrewingStandMenu} would add nothing but a {@code Player} in the
 * middle — the same call the furnace and the stonecutter already take.
 *
 * <p>So this class is the whole of brewing's game knowledge: which slot takes what, what a reagent
 * turns a bottle into, and what the game calls fuel. Nothing here invents a potion or a cost.
 */
public final class Brewing {

	/** Slots 0..2 hold the bottles; the last two are shared with the menu's own layout. */
	public static final int BOTTLE_SLOTS = 3;
	public static final int INGREDIENT_SLOT = 3;
	public static final int FUEL_SLOT = 4;

	/** One batch is twenty seconds; a job that needs two of them is already a long one. */
	public static final int BREW_TICKS = 400;

	private Brewing() {}

	/** The stand at a position, or null when there is not one there. */
	public static BrewingStandBlockEntity at(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos)) return null;
		return level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand ? stand : null;
	}

	/**
	 * What this reagent turns that bottle into, or empty when it does not act on it.
	 *
	 * <p>The answer comes from the game's own brewing recipes, matched exactly the way the stand's
	 * own {@code isBrewable} matches them, so a refusal here and a refusal at the stand cannot
	 * disagree.
	 */
	public static Optional<ItemStack> resultOf(ServerLevel level, ItemStack bottle, ItemStack reagent) {
		if (level == null || bottle == null || bottle.isEmpty() || reagent == null || reagent.isEmpty()) {
			return Optional.empty();
		}
		if (!(level.recipeAccess() instanceof RecipeManager manager)) return Optional.empty();
		BrewingInput input = new BrewingInput(bottle.copyWithCount(1), reagent.copyWithCount(1));
		return manager.getRecipeFor(RecipeType.BREWING, input, level)
				.map(holder -> holder.value().assemble(input))
				.filter(stack -> stack != null && !stack.isEmpty());
	}

	/** A bottle the stand would accept: a potion, splash potion or lingering potion. */
	public static boolean isBottle(ServerLevel level, ItemStack stack) {
		return level != null && stack != null && !stack.isEmpty()
				&& PotionIngredient.isPotionInput(stack, level.recipeAccess());
	}

	/**
	 * Whether the game calls this fuel.
	 *
	 * <p>The component holds a loot-resolved number of uses, and resolving it needs a loot context
	 * the block entity owns; nothing here needs the number, only the fact that the stand would bank
	 * it. {@code canPlaceItem} asks exactly the same question of the same component.
	 */
	public static boolean isFuel(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.has(DataComponents.BREWING_FUEL);
	}

	/**
	 * The potion inside a bottle, for a report that says what came out. "3 potion" is a technically
	 * true answer that tells the owner nothing; "3 mundane potion" tells him why he did not get the
	 * one he wanted.
	 */
	public static String potionName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "nothing";
		String item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
				.replace('_', ' ');
		net.minecraft.world.item.alchemy.PotionContents contents =
				stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) return item;
		return contents.potion()
				.map(holder -> holder.unwrapKey()
						.map(key -> key.identifier().getPath().replace('_', ' ') + " " + item)
						.orElse(item))
				.orElse(item);
	}
}
