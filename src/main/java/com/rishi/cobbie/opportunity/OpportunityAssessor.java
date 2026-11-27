package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.OperationalMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the world, the owner, his own body and his notes, and produces the shortlist. Decides nothing.
 *
 * <p>This replaces a picker that decided by itself: it looked for a pile of blocks and, if it found one,
 * that was the project. The inversion is that <b>the assessor only says what could be started</b>, and
 * the choice among those is the model's — which is why nothing here ranks, scores or sorts by worth.
 * Candidates come out in a fixed order of kind, ids are numbered in that order, and the prompt tells the
 * model the order means nothing.
 *
 * <p><b>Validation is the executor's own gate, not a second opinion.</b> Every candidate is built only
 * after the exact check its first step will run: {@code canStartMining} for a block, {@code canPlace}
 * for a square, the legality rule and line of sight for a fight, a real path for a walk. An opportunity
 * that cannot take its first step is not offered at all — the promise-before-the-check defect this
 * codebase keeps finding, closed by construction rather than by care.
 *
 * <p>Which families are produced today, and what is missing from the rest of the brief's list:
 *
 * <table>
 *   <tr><td>{@code protect}</td><td>a hostile already coming for him or his owner, in sight</td></tr>
 *   <tr><td>{@code return}</td><td>the owner is further than arm's reach, walked back in legal legs</td></tr>
 *   <tr><td>{@code mine}</td><td>three or more logs or stone he can actually break, or rubble to tidy</td></tr>
 *   <tr><td>{@code build}</td><td>a cairn from what he carries, on ground that is free</td></tr>
 *   <tr><td>{@code replenish}</td><td>something on the ground he can walk to and pick up — restocking</td></tr>
 *   <tr><td>{@code farm}</td><td>ripe crops around his feet</td></tr>
 *   <tr><td>{@code fight}</td><td>a hostile in sight that is not yet engaging</td></tr>
 *   <tr><td>{@code find}</td><td>ground he has not walked, when exploring is allowed</td></tr>
 *   <tr><td>{@code interact}, {@code trade}</td><td><b>not offered</b> — no deterministic gate says a
 *       station or a villager's offer is both affordable and his to use unasked</td></tr>
 *   <tr><td>{@code help}</td><td><b>not offered</b> — the one kind of help the game lets him read is a
 *       fight, and that is {@code protect}</td></tr>
 *   <tr><td>{@code wait}</td><td><b>not a candidate</b> — rejecting the list already means WAIT; an
 *       idleness candidate would let the assessor offer doing nothing as if it were a choice</td></tr>
 * </table>
 */
public final class OpportunityAssessor {

	/** How far he will look for something to take, and how many squares one job may touch. */
	private static final int GATHER_RADIUS = 6;
	private static final int MAX_STEPS = 3;
	private static final int MIN_BLOCKS = 3;

	/** How far a hostile counts, how far a loose item or a crop counts. */
	private static final int FIGHT_RADIUS = 16;
	private static final int FARM_RADIUS = 4;

	/** Closer than this the owner does not need collecting: he is already there. */
	private static final int RETURN_NEAR = 8;

	/** A return is walked in legs the verb will accept; more than four legs is out of a walk's range. */
	private static final int MAX_RETURN_LEGS = 4;

	/** What he is willing to stack a cairn out of, in the order he would rather use it. */
	private static final List<String> CAIRN_MATERIALS = List.of(
			"minecraft:cobblestone", "minecraft:stone", "minecraft:oak_planks", "minecraft:dirt");

	private OpportunityAssessor() {}

	/** Everything he could start right now, suppressed and numbered. Empty when there is nothing. */
	public static Shortlist assess(CobbieEntity cobbie) {
		List<Opportunity> found = new ArrayList<>();
		if (cobbie == null || !(cobbie.level() instanceof ServerLevel level)) return Shortlist.assemble(found);
		BlockPos feet = cobbie.blockPosition();

		protect(found, cobbie);
		returning(found, cobbie);
		mining(found, cobbie, level, feet);
		building(found, cobbie, level, feet);
		collecting(found, cobbie);
		farming(found, cobbie, level, feet);
		fighting(found, cobbie);
		finding(found, cobbie);

		return Shortlist.assemble(found);
	}

	// ------------------------------------------------------------------
	// The candidates. Each one asks the gate its own first step will ask.
	// ------------------------------------------------------------------

	/** Something is already coming for him or for his owner. The one thing that cannot wait. */
	private static void protect(List<Opportunity> out, CobbieEntity cobbie) {
		if (!CobbieConfig.permits(ActionVocabulary.ATTACK) || cobbie.isRetreating()) return;
		LivingEntity threat = cobbie.threatToDefendAgainst();
		if (threat == null) return;
		String what = threat.getName().getString();
		out.add(new Opportunity(null, Opportunity.Kind.PROTECT,
				"a " + what + " is already coming for " + (ownsIt(cobbie, threat) ? "you" : "me"),
				"whoever it reaches first is hurt if nobody stops it",
				List.of(),
				140,
				Opportunity.Risk.MEDIUM,
				3,
				List.of("it dies or runs", "you speak", "my health drops below a third"),
				"fighting the " + what,
				List.of(swingAt(threat))));
	}

	/** The owner has walked off further than he follows from. Walked back in legs the verb accepts. */
	private static void returning(List<Opportunity> out, CobbieEntity cobbie) {
		ServerPlayer owner = cobbie.owner();
		if (owner == null) return; // offline or another dimension: the follow goal owns that case
		double away = Math.sqrt(cobbie.distanceToSqr(owner));
		if (away <= RETURN_NEAR) return;

		List<BlockPos> legs = legsToward(cobbie, owner.blockPosition());
		if (legs.isEmpty()) return;
		// The first leg is checked the way its own step will be checked on the ground he is standing on; the
		// legs after it for room, because reachability from a square he has not reached yet is not a question
		// the pathfinder can be asked. Each of those is re-checked by its own step when it runs.
		for (int i = 0; i < legs.size(); i++) {
			if (i == 0 ? !cobbie.canWalkTo(legs.get(i)) : !cobbie.canStandAt(legs.get(i))) return;
		}
		List<ActionIntent> steps = new ArrayList<>(legs.size());
		for (BlockPos leg : legs) {
			steps.add(new ActionIntent(ActionVocabulary.WALK_TO, "", leg.getX(), leg.getY(), leg.getZ(), ""));
		}
		out.add(new Opportunity(null, Opportunity.Kind.RETURN,
				"you are " + (int) away + " blocks off and I am not with you",
				"back at your side, where a companion is useful",
				List.of(),
				(int) Math.max(20, away * 4),
				Opportunity.Risk.LOW,
				3,
				List.of("you come back to me", "you speak"),
				"coming back to you",
				steps));
	}

	/** Three or more of one useful block within reach, or rubble worth tidying. */
	private static void mining(List<Opportunity> out, CobbieEntity cobbie, ServerLevel level, BlockPos feet) {
		if (!CobbieConfig.permits(ActionVocabulary.MINE)) return;

		Map<String, List<BlockPos>> useful = new LinkedHashMap<>();
		List<BlockPos> rubble = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-GATHER_RADIUS, -1, -GATHER_RADIUS),
				feet.offset(GATHER_RADIUS, 2, GATHER_RADIUS))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !breakable(level, pos, state)) continue;
			// The gate his first step will run. A promise made before the check is the defect.
			if (!cobbie.canStartMining(pos)) continue;
			if (state.is(BlockTags.LOGS) || stoneLike(state)) {
				useful.computeIfAbsent(state.getBlock().getName().getString(), k -> new ArrayList<>())
						.add(pos.immutable());
			} else if (isRubble(state) && pos.getY() == feet.getY() && level.getBlockState(pos.above()).isAir()) {
				rubble.add(pos.immutable());
			}
		}

		for (Map.Entry<String, List<BlockPos>> entry : useful.entrySet()) {
			List<BlockPos> pile = new ArrayList<>(entry.getValue());
			pile.sort(nearestFirst(cobbie));
			if (pile.size() < MIN_BLOCKS) continue;
			List<ActionIntent> steps = mines(pile.subList(0, Math.min(MAX_STEPS, pile.size())));
			String what = entry.getKey().toLowerCase(Locale.ROOT);
			String request = "gathering the " + what + " nearby";
			out.add(new Opportunity(null, Opportunity.Kind.MINE,
					pile.size() + " " + what + " within reach" + lastTime(cobbie, request),
					"material for tools, planks and repairs",
					List.of(),
					stepTicks(steps.size(), 25),
					Opportunity.Risk.LOW,
					1,
					List.of("you speak", "the blocks are gone"),
					request,
					steps));
		}

		if (rubble.size() >= MIN_BLOCKS) {
			rubble.sort(nearestFirst(cobbie));
			List<ActionIntent> steps = mines(rubble.subList(0, Math.min(MAX_STEPS, rubble.size())));
			String request = "clearing the rubble around me";
			out.add(new Opportunity(null, Opportunity.Kind.MINE,
					rubble.size() + " loose block(s) underfoot" + lastTime(cobbie, request),
					"ground that can be built on",
					List.of(),
					stepTicks(steps.size(), 25),
					Opportunity.Risk.LOW,
					1,
					List.of("you speak", "the ground is clear"),
					request,
					steps));
		}
	}

	/** A cairn from what he carries: the one thing whose shape he decides, so it is a real choice. */
	private static void building(List<Opportunity> out, CobbieEntity cobbie, ServerLevel level, BlockPos feet) {
		if (!CobbieConfig.permits(ActionVocabulary.PLACE)) return;
		for (String material : CAIRN_MATERIALS) {
			if (cobbie.countOf(material) < MIN_BLOCKS) continue;
			for (BlockPos site : flatSitesNear(level, feet)) {
				List<ActionIntent> steps = new ArrayList<>();
				for (int i = 0; i < MAX_STEPS && cobbie.countOf(material) > steps.size(); i++) {
					BlockPos pos = site.above(i);
					// The base needs real ground; each block above stands on the one the plan places first.
					boolean buildable = i == 0 ? cobbie.canPlace(material, pos) : cobbie.canPlaceOnOwn(material, pos);
					if (!buildable) break;
					steps.add(new ActionIntent(ActionVocabulary.PLACE, material,
							pos.getX(), pos.getY(), pos.getZ(), ""));
				}
				if (steps.size() < MIN_BLOCKS) continue;
				String name = material.substring(material.indexOf(':') + 1).replace('_', ' ');
				String request = "building a cairn of " + name + " here";
				out.add(new Opportunity(null, Opportunity.Kind.BUILD,
						"flat ground beside me, " + steps.size() + " " + name + " in my bag"
								+ lastTime(cobbie, request),
						"a marker at this spot, visible from a distance",
						List.of(name + " x" + steps.size()),
						stepTicks(steps.size(), 20),
						Opportunity.Risk.LOW,
						1,
						List.of("you speak", "the ground is taken"),
						request,
						steps));
				break;
			}
		}
	}

	/**
	 * Something on the ground he can walk to: restocking his own bag, which is what {@code replenish} means
	 * here. Eating is not offered — that is his body's own policy at {@code EAT_AT}, and offering it would
	 * be offering something that happens without a decision.
	 */
	private static void collecting(List<Opportunity> out, CobbieEntity cobbie) {
		if (!CobbieConfig.permits(ActionVocabulary.COLLECT)) return;
		ItemEntity item = cobbie.nearestLooseItem();
		if (item == null) return;
		ItemStack stack = item.getItem();
		BlockPos at = item.blockPosition();
		if (!cobbie.canWalkTo(at)) return;
		String what = stack.getHoverName().getString();
		out.add(new Opportunity(null, Opportunity.Kind.REPLENISH,
				"a " + what + " is lying " + (int) Math.sqrt(cobbie.distanceToSqr(item)) + " blocks away",
				"in the bag rather than on the ground",
				List.of(),
				30,
				Opportunity.Risk.LOW,
				2,
				List.of("someone else takes it", "you speak"),
				"picking up the " + what,
				List.of(new ActionIntent(ActionVocabulary.COLLECT, "", at.getX(), at.getY(), at.getZ(), ""))));
	}

	/** Ripe crops within arm's reach: food without leaving his owner. */
	private static void farming(List<Opportunity> out, CobbieEntity cobbie, ServerLevel level, BlockPos feet) {
		if (!CobbieConfig.permits(ActionVocabulary.FARM)) return;
		int ripe = cobbie.ripeCropsNear(FARM_RADIUS);
		if (ripe <= 0) return;
		out.add(new Opportunity(null, Opportunity.Kind.FARM,
				ripe + " ripe crop(s) right beside me",
				"food, and the field keeps growing",
				List.of(),
				40,
				Opportunity.Risk.LOW,
				1,
				List.of("you speak", "it is harvested"),
				"harvesting what is ripe here",
				List.of(new ActionIntent(ActionVocabulary.FARM, "", feet.getX(), feet.getY(), feet.getZ(), ""))));
	}

	/** A hostile in sight that is not yet coming for anyone: worth going after, never urgent. */
	private static void fighting(List<Opportunity> out, CobbieEntity cobbie) {
		if (!CobbieConfig.permits(ActionVocabulary.ATTACK) || cobbie.isRetreating()) return;
		LivingEntity hostile = cobbie.idleHostileNear(FIGHT_RADIUS);
		if (hostile == null) return;
		String what = hostile.getName().getString();
		out.add(new Opportunity(null, Opportunity.Kind.FIGHT,
				"a " + what + " in sight, " + (int) Math.sqrt(cobbie.distanceToSqr(hostile)) + " blocks off",
				"it gone before it turns into a problem",
				List.of(),
				120,
				Opportunity.Risk.MEDIUM,
				2,
				List.of("it moves out of sight", "you speak", "my health drops below a third"),
				"putting down the " + what,
				List.of(swingAt(hostile))));
	}

	/** Ground he has not walked. The only candidate that takes him away from his owner. */
	private static void finding(List<Opportunity> out, CobbieEntity cobbie) {
		if (!CobbieConfig.permits(ActionVocabulary.EXPLORE)) return;
		out.add(new Opportunity(null, Opportunity.Kind.FIND,
				"nothing is close to hand and I do not know this ground yet",
				"a bearing on what is around, and maybe something worth doing",
				List.of(),
				400,
				Opportunity.Risk.MEDIUM,
				0,
				List.of("you speak", "something worth doing turns up"),
				"looking around a bit",
				List.of(new ActionIntent(ActionVocabulary.EXPLORE, "", 0, 0, 0, ""))));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static ActionIntent swingAt(LivingEntity target) {
		BlockPos at = target.blockPosition();
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
		return new ActionIntent(ActionVocabulary.ATTACK, id, at.getX(), at.getY(), at.getZ(), "");
	}

	/** Whether the hostile's own target is his owner rather than him: it decides how the reason reads. */
	private static boolean ownsIt(CobbieEntity cobbie, LivingEntity threat) {
		ServerPlayer owner = cobbie.owner();
		return owner != null && threat instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == owner;
	}

	private static int stepTicks(int steps, int each) {
		return Math.max(1, steps * each);
	}

	private static Comparator<BlockPos> nearestFirst(CobbieEntity cobbie) {
		BlockPos feet = cobbie.blockPosition();
		return Comparator.comparingDouble((BlockPos p) -> p.distSqr(feet))
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ);
	}

	private static List<ActionIntent> mines(List<BlockPos> positions) {
		List<ActionIntent> steps = new ArrayList<>(positions.size());
		for (BlockPos pos : positions) {
			steps.add(new ActionIntent(ActionVocabulary.MINE, "", pos.getX(), pos.getY(), pos.getZ(), ""));
		}
		return steps;
	}

	/**
	 * The walk home in legs the verb accepts, or an empty list when it is further than a walk's worth.
	 *
	 * <p>A player's "come to me" is one instruction; his verb refuses a target beyond its own range, so
	 * a distance that long is walked as consecutive points along the line. Each leg is inside the limit
	 * when it is planned, and each is re-checked by its own step when it runs.
	 */
	private static List<BlockPos> legsToward(CobbieEntity cobbie, BlockPos owner) {
		double limit = Math.max(8, CobbieConfig.getNavigationRange()) - 1.0;
		double distance = Math.sqrt(cobbie.blockPosition().distSqr(owner));
		int legs = (int) Math.ceil(distance / limit);
		if (legs < 1) return List.of();
		if (legs > MAX_RETURN_LEGS) return List.of(); // further than any walk the verb allows: the follow goal's job
		BlockPos from = cobbie.blockPosition();
		List<BlockPos> out = new ArrayList<>(legs);
		for (int i = 1; i <= legs; i++) {
			double t = Math.min(1.0, (limit * i) / distance);
			int x = (int) Math.round(from.getX() + (owner.getX() - from.getX()) * t);
			int y = (int) Math.round(from.getY() + (owner.getY() - from.getY()) * t);
			int z = (int) Math.round(from.getZ() + (owner.getZ() - from.getZ()) * t);
			BlockPos leg = i == legs ? owner : new BlockPos(x, y, z);
			out.add(leg);
		}
		return out;
	}

	/** What he last recorded about this exact job, appended to the reason — never a veto, always info. */
	private static String lastTime(CobbieEntity cobbie, String request) {
		UUID owner = cobbie.getOwner();
		if (owner == null) return "";
		try {
			for (OperationalMemory.Entry entry : OperationalMemory.entries(owner)) {
				if (entry.kind() != OperationalMemory.Kind.OUTCOME) continue;
				if (!request.equalsIgnoreCase(entry.subject())) continue;
				String fact = entry.fact() == null ? "" : entry.fact().trim();
				// Only failures are worth saying: "worked" is what a companion should assume.
				if (!fact.toLowerCase(Locale.ROOT).startsWith("failed")) return "";
				return " (last time: " + fact + ")";
			}
		} catch (Throwable ignored) {
			// a note that cannot be read is not a reason to offer nothing
		}
		return "";
	}

	/** Flat ground within reach that nothing occupies and nobody he can see is standing on. */
	private static List<BlockPos> flatSitesNear(ServerLevel level, BlockPos feet) {
		List<BlockPos> sites = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos site = feet.offset(dx, 0, dz);
				if (!level.isLoaded(site)) continue;
				if (level.getBlockState(site.below()).isAir()) continue;
				if (!level.getBlockState(site).canBeReplaced()) continue;
				if (crowded(level, site)) continue;
				sites.add(site);
			}
		}
		return sites;
	}

	/** Somebody standing on the spot, in any of the three blocks he would fill. */
	private static boolean crowded(ServerLevel level, BlockPos site) {
		for (int i = 0; i < MAX_STEPS; i++) {
			BlockPos pos = site.above(i);
			if (!level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(pos),
					LivingEntity::isAlive).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Not lava or fire, and not somebody's storage or machinery, which is what a block entity means. */
	private static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return false;
		if (state.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) return false;
		return level.getBlockEntity(pos) == null;
	}

	/** Loose ground standing on top of the surface: dirt, gravel, cobblestone and the like. */
	private static boolean isRubble(BlockState state) {
		return state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRAVEL)
				|| state.is(Blocks.COBBLESTONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.TUFF);
	}

	/** Stone that a pickaxe takes and a wall is made of: worth carrying, unlike dirt. */
	private static boolean stoneLike(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.COBBLESTONE) || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK);
	}
}
