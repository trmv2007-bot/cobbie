package com.rishi.cobbie.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns "craft a wooden pickaxe" into the ordered recipe runs that get there from what he is
 * actually carrying.
 *
 * <p>Pure Java on purpose. The recipe table comes from the game and the stock comes from the bag,
 * but the search — which recipe, how many runs, what has to be made first, and what is genuinely
 * missing — lives here, where it can be tested without a world. The chain that matters is the one
 * a fresh companion needs: log → planks → sticks → tool.
 *
 * <h2>How a plan is built</h2>
 * The stock is simulated forward, not just inspected. {@link #ensure} takes what it can from the
 * simulated stock, and anything still owed is produced by a recipe, whose own ingredients are
 * resolved by the same call before its step is appended. That ordering is exactly the order the
 * steps have to run in, and it is why two branches of one plan cannot both spend the same log.
 *
 * <p>Each step records the concrete items it consumes, so the executor does not have to re-derive
 * anything: it decrements those ids and adds the result, then reads the bag back.
 */
public final class CraftChain {

	/** Deeper than this and "make X" means the recipe graph is looping, not that he is clever. */
	public static final int DEFAULT_MAX_DEPTH = 6;

	/** Where a recipe is worked: in his hands, or at a station he has to stand at. */
	public static final String HAND = "hand";
	public static final String FURNACE = "furnace";
	public static final String STONECUTTER = "stonecutter";

	/**
	 * One recipe as the planner needs it.
	 *
	 * @param id          the recipe's registry id, for the report
	 * @param result      registry id of the item it makes
	 * @param resultCount how many of that item one run produces
	 * @param slots       one entry per consuming slot; each entry lists the item ids that slot accepts
	 * @param station     {@link #HAND}, {@link #FURNACE} or {@link #STONECUTTER}
	 */
	public record Recipe(String id, String result, int resultCount, List<List<String>> slots, String station) {
		public Recipe {
			slots = slots == null ? List.of() : List.copyOf(slots);
			station = station == null || station.isBlank() ? HAND : station;
		}

		/** A recipe worked by hand, which is nearly all of them. */
		public Recipe(String id, String result, int resultCount, List<List<String>> slots) {
			this(id, result, resultCount, slots, HAND);
		}

		public boolean needsFurnace() {
			return FURNACE.equals(station);
		}

		/** A station that has to be found, but does not make him wait. */
		public boolean needsStonecutter() {
			return STONECUTTER.equals(station);
		}

		public boolean needsStation() {
			return needsFurnace() || needsStonecutter();
		}
	}

	/**
	 * The game's recipe table, asked one item at a time.
	 *
	 * <p>A list, not a single recipe, because one item can have several: sticks come from planks and
	 * from bamboo, and which one he should use depends on what he is carrying — which the planner
	 * knows and the table does not.
	 */
	public interface Book {
		/** @return every ordinary-crafting recipe that makes {@code itemId}; empty when there is none. */
		List<Recipe> findFor(String itemId);
	}

	/** One recipe to run, with the exact items it consumes so the executor can verify itself. */
	public record Step(String recipeId, String result, int resultCount, int runs, List<String> consumes,
			String station) {
		public Step {
			consumes = consumes == null ? List.of() : List.copyOf(consumes);
			station = station == null || station.isBlank() ? HAND : station;
		}

		public Step(String recipeId, String result, int resultCount, int runs, List<String> consumes) {
			this(recipeId, result, resultCount, runs, consumes, HAND);
		}

		public boolean needsFurnace() {
			return FURNACE.equals(station);
		}

		public boolean needsStonecutter() {
			return STONECUTTER.equals(station);
		}

		/** How many of {@link #result} this step adds to the bag. */
		public int produced() {
			return runs * resultCount;
		}
	}

	/**
	 * @param steps   what to run, in order; empty when the item is already in the bag
	 * @param failure why it cannot be done, or null when it can
	 */
	public record Plan(List<Step> steps, String failure) {
		public Plan {
			steps = steps == null ? List.of() : List.copyOf(steps);
		}

		public boolean possible() {
			return failure == null;
		}
	}

	private CraftChain() {}

	/**
	 * @param wantId   registry id of the item to end up with
	 * @param quantity how many of it he should be holding, at least
	 * @param stock    what he has now, registry id → count; not modified
	 */
	public static Plan plan(String wantId, int quantity, Book book, Map<String, Integer> stock) {
		return plan(wantId, quantity, book, stock, DEFAULT_MAX_DEPTH);
	}

	public static Plan plan(String wantId, int quantity, Book book, Map<String, Integer> stock, int maxDepth) {
		if (wantId == null || wantId.isBlank()) return fail("tell me what to craft");
		if (book == null) return fail("I cannot look up recipes right now");
		Search search = new Search(book, new LinkedHashMap<>(stock == null ? Map.of() : stock),
				Math.max(1, maxDepth));
		String failure = search.ensure(wantId, Math.max(1, quantity), 0, new LinkedHashSet<>());
		return failure == null ? new Plan(search.steps, null) : fail(failure);
	}

	private static Plan fail(String reason) {
		return new Plan(List.of(), reason);
	}

	private static final class Search {
		private final Book book;
		private final Map<String, Integer> have;
		private final List<Step> steps = new ArrayList<>();
		private final int maxDepth;

		Search(Book book, Map<String, Integer> have, int maxDepth) {
			this.book = book;
			this.have = have;
			this.maxDepth = maxDepth;
		}

		/** @return null when {@code item} will be in hand afterwards, otherwise why not. */
		private String ensure(String item, int quantity, int depth, Set<String> path) {
			int stocked = Math.min(have.getOrDefault(item, 0), quantity);
			if (stocked > 0) have.put(item, have.getOrDefault(item, 0) - stocked);
			int need = quantity - stocked;
			if (need <= 0) return null;

			if (depth >= maxDepth) return "the recipe for " + item + " needs too many steps at once";
			if (!path.add(item)) return "making " + item + " would need " + item;

			List<Recipe> options = book.findFor(item);
			if (options == null || options.isEmpty()) {
				path.remove(item);
				return "I have no recipe for " + item + " and none in my bag";
			}

			// Several recipes can make the same thing and only some will work from this bag, so each
			// one is tried against a snapshot and the simulation is rolled back when it fails.
			String firstReason = null;
			for (Recipe recipe : options) {
				Map<String, Integer> stockBefore = new LinkedHashMap<>(have);
				int stepsBefore = steps.size();
				String reason = tryRecipe(recipe, item, need, depth, path);
				if (reason == null) {
					path.remove(item);
					return null;
				}
				if (firstReason == null) firstReason = reason;
				have.clear();
				have.putAll(stockBefore);
				while (steps.size() > stepsBefore) steps.remove(steps.size() - 1);
			}
			path.remove(item);
			return firstReason;
		}

		/** @return null when this recipe settles {@code need} of {@code item}, otherwise why not. */
		private String tryRecipe(Recipe recipe, String item, int need, int depth, Set<String> path) {
			if (recipe.resultCount() <= 0) return "the recipe for " + item + " produces nothing";

			int runs = (need + recipe.resultCount() - 1) / recipe.resultCount();
			// Which item each slot spends, decided once so identical slots pool their demand: a
			// pickaxe wants three planks, and three planks is one run of the planks recipe, not three.
			Map<String, Integer> demand = new LinkedHashMap<>();
			for (List<String> slot : recipe.slots()) {
				String pick = choose(slot);
				if (pick != null) demand.merge(pick, runs, Integer::sum);
			}
			List<String> consumes = new ArrayList<>();
			for (Map.Entry<String, Integer> wanted : demand.entrySet()) {
				String reason = ensure(wanted.getKey(), wanted.getValue(), depth + 1, path);
				if (reason != null) return reason;
				for (int used = 0; used < wanted.getValue(); used++) consumes.add(wanted.getKey());
			}

			steps.add(new Step(recipe.id(), recipe.result(), recipe.resultCount(), runs, consumes, recipe.station()));
			// Keep what this run leaves over, minus what the caller asked for — otherwise the same
			// produced item would be handed out twice.
			have.put(item, have.getOrDefault(item, 0) + runs * recipe.resultCount() - need);
			return null;
		}

		/**
		 * Which acceptable item to spend on a slot. Stock wins, so he uses what he has before
		 * making more; when he has none of them, the first is taken so the failure names a real
		 * item and the recursion still gets a chance to craft it.
		 */
		private String choose(List<String> acceptable) {
			if (acceptable == null || acceptable.isEmpty()) return null;
			String best = null;
			int bestCount = 0;
			for (String id : acceptable) {
				int count = have.getOrDefault(id, 0);
				if (count > bestCount) {
					bestCount = count;
					best = id;
				}
			}
			return best != null ? best : acceptable.get(0);
		}
	}
}
