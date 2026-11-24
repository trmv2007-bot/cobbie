package com.rishi.cobbie.station;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/**
 * What a smithing table does, without opening its menu.
 *
 * <p>The table's menu is three input slots and a result slot whose whole job is
 * {@code getRecipeFor(SMITHING, input, level)} then {@code assemble(input)} — there is no charge, no
 * player level and no menu-side state. So the table is not a menu capability at all: it is a recipe
 * lookup, and this class is that lookup.
 *
 * <p>The one thing worth being careful about is that {@code assemble} is
 * {@code TransmuteRecipe.createWithOriginalComponents(result, base)}: it rewrites the base item and
 * carries its components — enchantments, name, damage — onto the result. That is exactly what makes
 * netherite an upgrade of <em>your</em> pickaxe rather than a fresh one, and it is why the base must
 * be the real stack and not a copy with its components stripped.
 */
public final class Smithing {

	private Smithing() {}

	/** The table at or near a position, or null. */
	public static BlockPos tableNear(ServerLevel level, BlockPos around, int radius) {
		if (level == null || around == null) return null;
		for (BlockPos probe : BlockPos.betweenClosed(
				around.offset(-radius, -6, -radius), around.offset(radius, 6, radius))) {
			if (level.getBlockState(probe).is(Blocks.SMITHING_TABLE)) return probe.immutable();
		}
		return null;
	}

	/**
	 * The recipe this trio makes, or empty when the table will not pair them.
	 *
	 * <p>An upgrade is preferred over a trim: both are smithing recipes, but a trim only paints an
	 * armour piece, and "make my pickaxe netherite" must never resolve to a decorative change on a
	 * chestplate that happened to match first.
	 */
	public static Optional<RecipeHolder<SmithingRecipe>> match(ServerLevel level, ItemStack template,
			ItemStack base, ItemStack addition) {
		if (level == null || base == null || base.isEmpty()) return Optional.empty();
		if (!(level.recipeAccess() instanceof RecipeManager manager)) return Optional.empty();
		SmithingRecipeInput input = new SmithingRecipeInput(
				template == null ? ItemStack.EMPTY : template,
				base,
				addition == null ? ItemStack.EMPTY : addition);
		return manager.getRecipeFor(RecipeType.SMITHING, input, level);
	}

	/** True when the table would accept this as a base item at all. */
	public static boolean isBase(ServerLevel level, ItemStack stack) {
		return test(level, RecipePropertySet.SMITHING_BASE, stack);
	}

	/** True when the table would accept this in the template slot. */
	public static boolean isTemplate(ServerLevel level, ItemStack stack) {
		return test(level, RecipePropertySet.SMITHING_TEMPLATE, stack);
	}

	/** True when the table would accept this in the addition slot. */
	public static boolean isAddition(ServerLevel level, ItemStack stack) {
		return test(level, RecipePropertySet.SMITHING_ADDITION, stack);
	}

	private static boolean test(ServerLevel level, net.minecraft.resources.ResourceKey<RecipePropertySet> key,
			ItemStack stack) {
		if (level == null || stack == null || stack.isEmpty()) return false;
		if (!(level.recipeAccess() instanceof RecipeManager manager)) return false;
		return manager.propertySet(key).test(stack);
	}

	public static ItemStack assemble(SmithingRecipe recipe, ItemStack template, ItemStack base, ItemStack addition) {
		ItemStack made = recipe.assemble(new SmithingRecipeInput(template, base, addition));
		return made == null ? ItemStack.EMPTY : made;
	}

	/** A transform changes the item; a trim only decorates one. Used to pick a verb's wording. */
	public static boolean isUpgrade(SmithingRecipe recipe) {
		return recipe instanceof SmithingTransformRecipe;
	}
}
