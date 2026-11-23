package com.rishi.cobbie.craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain a fresh companion actually needs: log → planks → sticks → tool, from what is in the
 * bag and nothing else. The recipe table is faked here on purpose — a unit test that needed a
 * running server would not be a unit test — but the shapes are vanilla's.
 *
 * <p>The property that matters is not the exact step count but that the plan can be *run*: every
 * step must find the items it consumes in the bag at the moment it runs. {@link #runsFrom} is that
 * simulation, and it is what catches a planner that spends one log twice.
 */
class CraftChainTest {

	private static final Map<String, CraftChain.Recipe> VANILLA_ISH = Map.of(
			"oak_planks", recipe("minecraft:oak_planks", "oak_planks", 4, slot("oak_log")),
			"stick", recipe("minecraft:stick", "stick", 4, slot("oak_planks"), slot("oak_planks")),
			"wooden_pickaxe", recipe("minecraft:wooden_pickaxe", "wooden_pickaxe", 1,
					slot("oak_planks"), slot("oak_planks"), slot("oak_planks"),
					slot("stick"), slot("stick")),
			"crafting_table", recipe("minecraft:crafting_table", "crafting_table", 1,
					slot("oak_planks"), slot("oak_planks"), slot("oak_planks"), slot("oak_planks")));

	private static final CraftChain.Book BOOK = item -> {
		CraftChain.Recipe found = VANILLA_ISH.get(item);
		return found == null ? List.of() : List.of(found);
	};

	@Test
	void aPickaxeIsMadeFromLogsThroughPlanksAndSticks() {
		CraftChain.Plan plan = CraftChain.plan("wooden_pickaxe", 1, BOOK, Map.of("oak_log", 3));

		assertTrue(plan.possible(), plan.failure());
		List<String> results = plan.steps().stream().map(CraftChain.Step::result).toList();
		assertEquals("oak_planks", results.get(0), "planks have to be made before anything uses them");
		assertTrue(results.contains("stick"), "a pickaxe needs sticks, and they are not in the bag: " + results);
		assertEquals("wooden_pickaxe", results.get(results.size() - 1), "the tool is the last thing made");
		assertTrue(runsFrom(plan, Map.of("oak_log", 3)), "the plan must be runnable: " + plan.steps());
	}

	@Test
	void oneLogIsNotEnoughForAPickaxeAndSaysSo() {
		CraftChain.Plan plan = CraftChain.plan("wooden_pickaxe", 1, BOOK, Map.of("oak_log", 1));

		assertFalse(plan.possible(), "one log cannot cover three planks and the two sticks: " + plan.steps());
		assertTrue(plan.failure().contains("oak_log"), "the refusal names what is missing: " + plan.failure());
	}

	@Test
	void twoLogsCarryTheWholeChain() {
		CraftChain.Plan plan = CraftChain.plan("wooden_pickaxe", 1, BOOK, Map.of("oak_log", 2));

		assertTrue(plan.possible(), plan.failure());
		assertTrue(runsFrom(plan, Map.of("oak_log", 2)), "the plan must be runnable: " + plan.steps());
	}

	@Test
	void somethingAlreadyInTheBagNeedsNoSteps() {
		CraftChain.Plan plan = CraftChain.plan("oak_planks", 4, BOOK, Map.of("oak_planks", 5));

		assertTrue(plan.possible());
		assertTrue(plan.steps().isEmpty(), "he is already holding four planks");
	}

	@Test
	void quantityDecidesHowManyRuns() {
		CraftChain.Plan plan = CraftChain.plan("oak_planks", 9, BOOK, Map.of("oak_log", 3));

		assertTrue(plan.possible(), plan.failure());
		assertEquals(1, plan.steps().size());
		assertEquals(3, plan.steps().get(0).runs(), "nine planks is three runs of four");
		assertEquals(12, plan.steps().get(0).produced());
		assertTrue(runsFrom(plan, Map.of("oak_log", 3)), "the plan must be runnable: " + plan.steps());
	}

	@Test
	void aPartialStackIsCountedAndOnlyTheShortfallIsMade() {
		CraftChain.Plan plan = CraftChain.plan("oak_planks", 6, BOOK, Map.of("oak_planks", 4, "oak_log", 1));

		assertTrue(plan.possible(), plan.failure());
		assertEquals(1, plan.steps().get(0).runs(), "four in hand, two short, one run makes four");
		assertTrue(runsFrom(plan, Map.of("oak_planks", 4, "oak_log", 1)), "the plan must be runnable");
	}

	@Test
	void anItemWithNoRecipeIsRefusedByName() {
		CraftChain.Plan plan = CraftChain.plan("diamond", 1, BOOK, Map.of("oak_log", 3));

		assertFalse(plan.possible());
		assertTrue(plan.failure().contains("diamond"), plan.failure());
	}

	@Test
	void aRecipeLoopFailsInsteadOfHanging() {
		CraftChain.Book looping = item -> switch (item) {
			case "a" -> List.of(recipe("a_from_b", "a", 1, slot("b")));
			case "b" -> List.of(recipe("b_from_a", "b", 1, slot("a")));
			default -> List.of();
		};

		CraftChain.Plan plan = CraftChain.plan("a", 1, looping, Map.of());

		assertFalse(plan.possible());
		assertNotNull(plan.failure());
	}

	@Test
	void aSlotAcceptingSeveralItemsUsesTheOneHeHas() {
		CraftChain.Book planks = item -> item.equals("stick")
				? List.of(recipe("minecraft:stick", "stick", 4,
						slot("oak_planks", "birch_planks"), slot("oak_planks", "birch_planks")))
				: List.of();

		CraftChain.Plan plan = CraftChain.plan("stick", 2, planks, Map.of("birch_planks", 2));

		assertTrue(plan.possible(), plan.failure());
		assertEquals(List.of("birch_planks", "birch_planks"), plan.steps().get(0).consumes());
	}

	@Test
	void theRecipeThatFitsHisBagWinsWhenThereAreSeveral() {
		// Sticks come from planks and from bamboo in vanilla data. The first option is the one he
		// cannot do; the planner has to try the second instead of reporting "no recipe".
		CraftChain.Book twoWays = item -> item.equals("stick")
				? List.of(recipe("minecraft:stick_from_bamboo_item", "stick", 1, slot("bamboo")),
						recipe("minecraft:stick", "stick", 4, slot("oak_planks"), slot("oak_planks")))
				: List.of();

		CraftChain.Plan plan = CraftChain.plan("stick", 2, twoWays, Map.of("oak_planks", 3));

		assertTrue(plan.possible(), plan.failure());
		assertEquals("minecraft:stick", plan.steps().get(0).recipeId());
		assertTrue(runsFrom(plan, Map.of("oak_planks", 3)), "the plan must be runnable");
	}

	/**
	 * Walk the plan exactly as the executor would: take each consumed item out of the bag, then put
	 * the produced items in. False means the plan asked for something he would not have by then.
	 */
	private static boolean runsFrom(CraftChain.Plan plan, Map<String, Integer> stock) {
		Map<String, Integer> bag = new HashMap<>(stock);
		for (CraftChain.Step step : plan.steps()) {
			for (String needed : step.consumes()) {
				int held = bag.getOrDefault(needed, 0);
				if (held <= 0) return false;
				bag.put(needed, held - 1);
			}
			bag.merge(step.result(), step.produced(), Integer::sum);
		}
		return true;
	}

	private static CraftChain.Recipe recipe(String id, String result, int count, List<String>... slots) {
		return new CraftChain.Recipe(id, result, count, List.of(slots));
	}

	private static List<String> slot(String... ids) {
		return List.of(ids);
	}
}
