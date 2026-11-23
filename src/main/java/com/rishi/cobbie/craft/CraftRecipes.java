package com.rishi.cobbie.craft;

import com.rishi.cobbie.Cobbie;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.ArrayList;
import java.util.List;

/**
 * The bridge between the game's own recipe table and {@link CraftChain}.
 *
 * <p>Nothing here invents a recipe. The ingredient slots and the result stack both come from the
 * recipe itself: the slots from its {@link PlacementInfo}, the result from {@code assemble}. The
 * only recipes considered are ordinary crafting recipes that are not special — the special ones
 * (banners, maps, firework stars) read the grid they are handed, and he is not going to be handed
 * a grid.
 *
 * <p>The one judgement call is the grid. Vanilla's own {@code matches} is not used: a shapeless
 * recipe with two of the same ingredient would not re-match against a sample grid, which is a
 * false negative that would leave him unable to make sticks. What matters for him is whether the
 * recipe declares ingredients he can spend and a result he asked for — and both of those are
 * exactly what the recipe's own data says.
 */
public final class CraftRecipes {

	/**
	 * @param recipe the planner-visible shape
	 * @param output the real resulting stack for one run, straight from the game
	 */
	public record Match(CraftChain.Recipe recipe, ItemStack output) {}

	private CraftRecipes() {}

	/** @return every crafting recipe that produces {@code itemId}; empty when the game has none. */
	public static List<Match> allFor(ServerLevel level, String itemId) {
		if (level == null || itemId == null || itemId.isBlank()) return List.of();
		RecipeManager manager = level.recipeAccess() instanceof RecipeManager found ? found : null;
		if (manager == null) return List.of();

		List<Match> matches = new ArrayList<>();
		String lastObstacle = null;
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			if (recipe.isSpecial()) continue;
			PlacementInfo placement;
			ItemStack output;
			try {
				placement = recipe.placementInfo();
				if (placement == null || placement.isImpossibleToPlace()) continue;
				output = recipe.assemble(CraftingInput.EMPTY);
			} catch (Exception e) {
				lastObstacle = holder.id().identifier() + " threw " + e.getClass().getSimpleName();
				continue; // one odd recipe must not cost him the whole craft
			}
			if (output == null || output.isEmpty()) continue;
			String produced = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
			if (!produced.equals(itemId)) continue;

			List<List<String>> slots = slotItems(placement);
			if (slots.isEmpty()) {
				lastObstacle = holder.id().identifier() + " declares no ingredients";
				continue;
			}
			// A recipe that consumes what it makes is not a way to make it — transmute recipes read
			// their input's components, and assembling one with an empty grid produces nonsense.
			boolean consumesItself = slots.stream().anyMatch(accepts -> accepts.contains(produced));
			if (consumesItself) {
				lastObstacle = holder.id().identifier() + " consumes what it makes";
				continue;
			}
			CraftChain.Recipe shape = new CraftChain.Recipe(
					holder.id().identifier().toString(), produced, output.getCount(), slots);
			matches.add(new Match(shape, output.copy()));
		}
		// A furnace can also make it, and that is how iron is reached. Always asked, not only when
		// the table came up empty: a hand recipe that turns out to be unsatisfiable from his bag
		// must not hide the furnace one.
		matches.addAll(furnaceRecipesFor(level, manager, itemId));

		if (matches.isEmpty()) {
			// One line, and only when a craft could not find a recipe: "the game has none" and "the
			// recipe would not load" look identical from the outside and are different bugs.
			Cobbie.LOGGER.info("Cobbie craft: no usable recipe for {}{}", itemId,
					lastObstacle == null ? "" : " (last obstacle: " + lastObstacle + ")");
		}
		return matches;
	}

	/**
	 * The same question asked of the furnace: a smelting recipe is one ingredient and one result, so
	 * a raw ore is a step rather than a verb of its own, and {@code craft iron_pickaxe} can plan
	 * "smelt two iron ore, then make the pickaxe" without being told about the furnace at all.
	 */
	private static List<Match> furnaceRecipesFor(ServerLevel level, RecipeManager manager, String itemId) {
		List<Match> matches = new ArrayList<>();
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			if (!(holder.value() instanceof SingleItemRecipe recipe)) continue;
			if (recipe.getType() != RecipeType.SMELTING) continue;
			ItemStack output;
			try {
				output = recipe.assemble(new SingleRecipeInput(ItemStack.EMPTY));
			} catch (Exception e) {
				continue;
			}
			if (output == null || output.isEmpty()) continue;
			String produced = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
			if (!produced.equals(itemId)) continue;
			List<String> ids = itemIds(recipe.input());
			if (ids.isEmpty()) continue;
			CraftChain.Recipe shape = new CraftChain.Recipe(holder.id().identifier().toString(), produced,
					output.getCount(), List.of(ids), CraftChain.FURNACE);
			matches.add(new Match(shape, output.copy()));
		}
		return matches;
	}

	/**
	 * The consuming slots as item-id choices.
	 *
	 * <p>Built from the slot map rather than straight from the ingredient list: a shaped recipe lists
	 * an ingredient once and consumes it in three slots, so the pickaxe needs three planks, not one.
	 */
	private static List<List<String>> slotItems(PlacementInfo placement) {
		List<Ingredient> ingredients = placement.ingredients();
		var slotToIngredient = placement.slotsToIngredientIndex();
		List<List<String>> slots = new ArrayList<>();
		for (int slot = 0; slot < slotToIngredient.size(); slot++) {
			int index = slotToIngredient.getInt(slot);
			if (index == PlacementInfo.EMPTY_SLOT || index < 0 || index >= ingredients.size()) continue;
			List<String> ids = itemIds(ingredients.get(index));
			if (!ids.isEmpty()) slots.add(ids);
		}
		if (slots.isEmpty()) {
			// A recipe whose placement says nothing: one slot per listed ingredient instead.
			for (Ingredient ingredient : ingredients) {
				List<String> ids = itemIds(ingredient);
				if (!ids.isEmpty()) slots.add(ids);
			}
		}
		return slots;
	}

	private static List<String> itemIds(Ingredient ingredient) {
		if (ingredient == null || ingredient.isEmpty()) return List.of();
		return ingredient.items()
				.map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString())
				.toList();
	}
}
