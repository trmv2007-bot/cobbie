package com.rishi.cobbie.entity;

import com.mojang.serialization.Codec;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionEvidence;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.action.ToolChoice;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.craft.CraftChain;
import com.rishi.cobbie.craft.CraftRecipes;
import com.rishi.cobbie.station.Brewing;
import com.rishi.cobbie.station.Containers;
import com.rishi.cobbie.station.Smithing;
import com.rishi.cobbie.net.CobbiePanelPayload;
import com.rishi.cobbie.presence.FollowSight;
import com.rishi.cobbie.presence.Narrator;
import com.rishi.cobbie.presence.Sight;
import com.rishi.cobbie.task.StepCheck;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskRunner;
import com.rishi.cobbie.task.TaskStep;
import com.rishi.cobbie.world.BodySpace;
import com.rishi.cobbie.world.Falls;
import com.rishi.cobbie.world.WorldPerception;
import com.rishi.cobbie.world.WorldSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cobbie: a peaceful, owner-bound AI companion.
 *
 * <h2>Threading</h2>
 * The world is only ever touched from {@link #tick()} on the server thread. The LLM lives on other
 * threads and reaches him exclusively through {@link #offerPlan} / {@link #enqueue}, which are safe
 * to call from anywhere. No async callback may mutate blocks, entities or the inventory directly.
 *
 * <h2>Doing things properly</h2>
 * Actions run through {@link TaskRunner}: he navigates first, and the interaction only fires once
 * he is genuinely inside {@link CobbieConfig#getInteractionReach()}. Success is decided by reading
 * the world back afterwards — "the API returned true" is not proof. {@code navigation_range} is how
 * far he will walk for you; {@code interaction_reach} is how far away he can actually touch a
 * block. Different numbers, deliberately, and a 48-block "action range" no longer exists.
 *
 * <h2>Persistence</h2>
 * Owner, following state and the inventory are saved with the entity, so unloading a chunk or
 * relogging keeps him the same Cobbie with the same stuff. The tool he is holding is the real
 * stack (moved out of the bag, not copied), so nothing duplicates across a save/load cycle.
 */
public class CobbieEntity extends PathfinderMob implements TaskRunner.Executor, TaskRunner.Listener {

	private static final EntityDataAccessor<Boolean> DATA_WAVING =
			SynchedEntityData.defineId(CobbieEntity.class, EntityDataSerializers.BOOLEAN);

	/** Said once, plainly, when a world change ends a plan that was running. */
	static final String WORLD_CHANGED = "we changed worlds, so I stopped what I was doing";

	/**
	 * Said once when he wakes into a save that had a project in flight. A project cannot survive a
	 * reload — the plan, its steps and where it had got to live in memory — so the only honest thing he
	 * can do is say he lost it and ask again. Silence here was the defect: the owner came back to a
	 * companion standing still with no word about the work that had been running.
	 */
	public static final String WAKE_LINE =
			"I lost track of what I was doing before the world reloaded — ask me again";

	private static final int WAVE_TICKS = 40;
	private static final int STEP_DEADLINE_TICKS = 1200;  // 60s per step, then it is abandoned
	private static final double ARRIVE_RADIUS = 1.5;      // "I have walked there", not "I can touch it"
	private static final int WALK_GRACE_TICKS = 10;       // let the navigator compute before judging it
	private static final int MAX_WALK_REPATHS = 3;         // then repair a dropped path, a few times
	/** How many times a walk may be re-handed to the navigator because a climb might get him higher. */
	private static final int MAX_CLIMB_ASSISTS = 3;
	private static final int FOLLOW_REPATH_INTERVAL = 12; // the pathfinder is not free
	private static final int PICKUP_INTERVAL = 20;
	/** Ticks inside a solid block before he gives up on what he was doing and gets himself out. */
	private static final int WALL_RESCUE_TICKS = 10;
	/** How far the rescue looks for two free blocks with ground under them. */
	private static final int WALL_RESCUE_RADIUS = 4;
	/** Ticks his body spends in fire, lava or against a cactus before he stops what he is doing. */
	private static final int HAZARD_RESCUE_TICKS = 5;
	/** How far the escape looks for a square that is not on fire, in blocks. */
	private static final int HAZARD_RESCUE_RADIUS = 4;
	/** How long a retreat lasts before he stops running: eight seconds is a sprint out of a fight. */
	private static final int RETREAT_TICKS = 160;
	/** How long after a retreat he will not begin another: a companion who runs at every scratch is useless. */
	private static final int RETREAT_COOLDOWN_TICKS = 1200;
	/** How close a hostile must be, and engaged, before a wound is worth running from. */
	private static final double RETREAT_THREAT_RANGE = 12.0;
	/**
	 * How far away a hostile may be and still be worth raising a shield against. A shield takes five
	 * ticks to come up (the game's own block delay), so this is a charge's worth of time, not a reach.
	 */
	private static final double SHIELD_THREAT_RANGE = 6.0;
	/** How far a replanned mine will look for the same block it was sent for, in blocks. */
	private static final int MINE_REPLAN_RADIUS = 8;
	/** How often he remarks on what he can see while he has nothing else to do (60s). */
	private static final int OBSERVE_INTERVAL_TICKS = 1200;

	/**
	 * How far ahead the follow loop aims when you are further away than this. A pathfinder asked
	 * to plan 60 blocks across terrain usually answers "no path"; asked for the next 24 it answers,
	 * and the goal moves with you on the next re-path. That is the whole trick behind following at
	 * distance — one step at a time toward where you are now, never one long plan from where he was.
	 */
	private static final double FOLLOW_STEP = 24.0;
	/** Below this the follow loop aims at you directly instead of at a point along the way. */
	private static final double FOLLOW_DIRECT_RANGE = 20.0;
	/** How long the recall last resort watches for progress before deciding he is stuck. */
	private static final int RECALL_PROGRESS_TICKS = 200;
	/** Blocks of progress inside that window that count as "he is coming". */
	private static final double RECALL_PROGRESS_BLOCKS = 4.0;

	// ---- climbing ----
	/** Upward push on a ladder or vine: a player's climb is about this much per tick. */
	private static final double CLIMB_UP = 0.16;
	private static final double CLIMB_DOWN = -0.14;

	// ---- swimming ----
	private static final double SWIM_PUSH = 0.055;         // per-tick horizontal thrust in water
	private static final double SWIM_MAX_HORIZONTAL = 0.30; // never faster than a sprinting player
	private static final double SWIM_RISE = 0.06;           // buoyancy toward the surface
	private static final double SWIM_MAX_RISE = 0.16;
	private static final int CRAFT_CHAIN_DEPTH = 6;
	/** A smelt is ten seconds a piece, so a station job gets much longer than a walk. */
	private static final int STATION_STEP_TICKS = 2400;
	/** No progress in this long means the furnace is not lit or the item is not cookable. */
	private static final int STATION_STALL_TICKS = 400;
	/** How far he will look for a furnace before giving up on making iron. */
	private static final int FURNACE_SEARCH = 12;
	/** How quiet the owner must be before he will start something of his own mid-conversation. */
	private static final int INITIATIVE_QUIET_TICKS = 200;
	/** How often the situation is read: twice a second, which is what the cadence compares. */
	private static final int SITUATION_INTERVAL_TICKS = 10;
	/** How far round himself he will look for two animals of a kind. */
	private static final int BREED_RADIUS = 8;
	/** How far round himself he will tend a farm. A plot is small; a field he walks. */
	private static final int FARM_RADIUS = 4;
	/** How far he will reach for a mob with an item. A player's reach is three blocks; he is taller. */
	private static final double ENTITY_USE_REACH = 6.0;
	/**
	 * Why a fishing rod is refused rather than cast.
	 *
	 * <p>The bobber belongs to a player, and {@code FishingHook.tick} discards itself when
	 * {@code getPlayerOwner()} finds nobody. A probe measured it: one bobber on the cast, none forty
	 * ticks later, because the fake actor is not in the world's player list. A cast that silently
	 * evaporates reports success and delivers nothing, so it is not offered.
	 */
	private static final String NO_FISHING =
			"I cannot fish — a fishing line belongs to a player and I am not one";
	/**
	 * Marks a walk he planned for himself rather than one he was asked for. The request limit
	 * ({@code navigation_range}) exists so a model cannot send him across the map; his own wander
	 * is bounded by {@code exploration_radius} instead, and without this it was bounded by the
	 * smaller of the two — which is why "explore" barely left the spot.
	 */
	private static final String EXPLORE_WALK = "explore";

	/** Blocks a companion must never break, whatever the model asks for. */
	private static final Set<Block> PROTECTED = Set.of(
			Blocks.BEDROCK, Blocks.BARRIER, Blocks.COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK,
			Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.JIGSAW,
			Blocks.END_PORTAL_FRAME);

	/** Blocks that legitimately hang off a wall or lie on the floor. */
	private static final Set<Block> NO_SUPPORT_NEEDED = Set.of(
			Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.LADDER, Blocks.SNOW,
			Blocks.TRIPWIRE_HOOK, Blocks.LEVER);

	/** Saved with the entity. ItemStack.OPTIONAL_CODEC tolerates empty slots. */
	private static final Codec<List<ItemStack>> ITEM_LIST = ItemStack.OPTIONAL_CODEC.listOf();

	// ---- companion state (persisted) ----
	private UUID ownerUuid;
	private boolean following = true;

	// ---- inventory (the bag; a held tool is a bag slot that has been raised) ----
	private final Container inventory;

	/**
	 * His own hunger, in the game's own {@code FoodData}. A mob adds no exhaustion and heals no food
	 * back, so this piece of a player is carried for him and ticked by the game.
	 */
	private final Hunger hunger = new Hunger(this);

	// ---- action execution ----
	private final TaskRunner tasks;
	private ActionIntent active;
	private BlockPos activeTarget;   // where he is walking to
	private BlockPos workTarget;     // the block he intends to act on once he is in reach
	private int activeDeadlineTick;
	private boolean navigatingForAction;
	private double arriveRadiusSq;   // how close counts as "arrived" for the action in flight
	private int walkStartTick;       // the tick the current walk was handed to the navigator
	private int walkRepaths;         // how many times this walk has been re-handed to it
	private int climbAssists;        // how many of those were forgiven for being beside a climbable
	private int pickupReportTick;    // last time a pickup was folded into what he tells the model
	private String toolNoteKey = "";  // the tool sentence he has already said for this plan
	private int pickedUpSinceReport;
	private final Set<String> pickedUpIds = new HashSet<>();
	private ActionOutcome pendingOutcome;
	/** Consecutive ticks with his body inside a solid block, and whether he has said so yet. */
	private int wallTicks;
	private boolean wallRescued;
	/** How many times this body has had to be lifted out of a block, for the log that says when it happens. */
	private int rescues;
	/** Consecutive ticks his body has been in fire, lava or against a cactus. */
	private int hazardTicks;
	/** How many times this body has got itself out of something burning. */
	private int hazardRescues;
	/** When the retreat he is in ends, and when he may begin another. */
	private int retreatUntilTick;
	private int retreatCooldownUntil;
	/** The health his fall limit was computed for, so the pathfinder does not recompute it per node. */
	private float fallLimitForHealth = -1.0f;
	private int fallLimit;

	// ---- bookkeeping ----
	private int waveTicksLeft;
	private int followRepathTick;
	private int lastPickupTick;
	/**
	 * The tick he last swung on. The weapon's own cooldown, not a timer of his own, decides when the
	 * next one is allowed — see {@link #attackStrength()}.
	 */
	private int lastSwingTick;
	/**
	 * What he is fighting, by identity rather than by the entity handle: a target that dies or walks out of
	 * a loaded chunk leaves {@code getTarget()} null, and "nothing left to fight" then has to be able to say
	 * which thing is gone and whether it is dead or simply not there.
	 */
	private UUID hunted;
	private String huntedName;
	private String lastActionResult = "nothing yet";
	/** The last thing he said out loud, so a test can check the words and not just the effect. */
	private String lastSpokenLine = "";
	/**
	 * The last few lines, newest last. One field answers "what did he say"; "did he say X" is the question a test
	 * actually has, and a line that lands a tick later must not erase the one that explained the event.
	 */
	private final com.rishi.cobbie.presence.RecentLines recentLines =
			new com.rishi.cobbie.presence.RecentLines();
	/**
	 * The request of the project that was in flight, saved with him so a reload can own up to it, and
	 * cleared the moment that project settles. Blank means nothing was running: a reload then has nothing
	 * to lose and he must stay quiet.
	 */
	private String interruptedPlan = "";
	/**
	 * Set from that saved marker on load: one line is owed to the owner, said on the first tick (or the
	 * first tick they are there to hear it), and never said again.
	 */
	private boolean wakeLinePending = false;
	/** His voice: one short line per step, at most one every chat cooldown. */
	private final Narrator narrator = new Narrator();
	private int lastObservationTick;
	/** Where he stood when the current plan started — the origin skill steps are stored relative to. */
	private BlockPos planOrigin;

	/**
	 * What the owner calls him, synchronized: the nameplate is drawn on the client, so a name that lives
	 * only in server memory looks like a rename that worked on the server and failed on screen.
	 */
	private static final EntityDataAccessor<String> DATA_NAME =
			SynchedEntityData.defineId(CobbieEntity.class, EntityDataSerializers.STRING);
	/** Items he already failed to take, so he does not claw at them every tick. */
	private final Set<UUID> skippedItems = new HashSet<>();

	// ---- recall (the last resort, off by default) ----
	private BlockPos recallSamplePos;
	private int recallSampleTick;
	/** Consecutive far re-paths the pathfinder refused; each one shortens the next step. */
	private int followMisses;
	/** What following does while the owner's chunk is not loaded: one line, a deadline, then idle. */
	private final FollowSight followSight = new FollowSight();
	/** True once he has told the owner he cannot find a portal, so he says it once, not every tick. */
	private boolean portalNoticeShown;

	// ---- experience: his own, earned and kept ----
	private int experienceLevel;
	private float experienceProgress;

	/** Items whose slots no longer exist after a bag size change; spilled once, never deleted. */
	private final List<ItemStack> pendingSpill = new ArrayList<>();

	/** The job in flight when it spans ticks: crafting through a furnace, or smelting on purpose. */
	private Work work;

	/** True while the plan running is a project he started, so an owner instruction can cancel it. */
	private boolean initiativePlan;
	/** When he may ask the model what to do next: changes wake it, a slow heartbeat keeps it honest. */
	private final com.rishi.cobbie.opportunity.OpportunityCadence opportunityCadence =
			new com.rishi.cobbie.opportunity.OpportunityCadence();
	/** The tick the owner last asked him for anything: he does not start work mid-sentence. */
	private int lastOwnerContactTick;

	/** The batch in the brewing stand, while it is brewing. */
	private Brew brew;

	/**
	 * One resolved chain being worked through, tick by tick.
	 *
	 * <p>The plan is decided once, before anything is spent. A step that needs a furnace then becomes a
	 * little state machine — load fuel, load the ore, wait for the result, take it — because a furnace
	 * is the one part of crafting that takes real time.
	 */
	private static final class Work {
		final String wanted;
		final CraftChain.Plan plan;
		final Map<String, CraftRecipes.Match> matches = new LinkedHashMap<>();
		final int bagBefore;
		int step;
		int collectedForStep;
		int waitingSince;
		int stored;
		BlockPos station;
		final Map<String, Integer> spilled = new LinkedHashMap<>();
		final StringBuilder chain = new StringBuilder();

		Work(String wanted, CraftChain.Plan plan, int bagBefore) {
			this.wanted = wanted;
			this.plan = plan;
			this.bagBefore = bagBefore;
		}
	}

	/**
	 * One batch in a brewing stand.
	 *
	 * <p>The bottles go into the stand's own slots and the stand brews them; this only remembers what
	 * was put in, which slots it went into and what each bottle is expected to turn into, so the
	 * completion test is "the stand now holds what the recipe says", not "some ticks passed".
	 */
	private static final class Brew {
		final BlockPos stand;
		final int[] slots;
		final List<ItemStack> expected;
		final String reagent;
		final int startedTick;

		Brew(BlockPos stand, int[] slots, List<ItemStack> expected, String reagent, int startedTick) {
			this.stand = stand;
			this.slots = slots;
			this.expected = expected;
			this.reagent = reagent;
			this.startedTick = startedTick;
		}
	}

	public CobbieEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.inventory = new SimpleContainer(Math.max(1, CobbieConfig.getInventorySize()));
		this.tasks = new TaskRunner(this,
				CobbieConfig.getPlanStepLimit(),
				CobbieConfig.getTaskRetryLimit(),
				CobbieConfig.getTaskTimeoutSeconds() * 1000L);
		this.tasks.setListener(this);
		this.setPersistenceRequired(); // a companion must not despawn because you walked away

		// Water is walkable for him. Without this the ground navigator refuses to plan at all while
		// he is in water (canUpdatePath is false), which is why a lake used to end the chase: he
		// would bob in place with no path and no way to say why.
		this.getNavigation().setCanFloat(true);
		this.setPathfindingMalus(PathType.WATER, 4.0f);
		this.setPathfindingMalus(PathType.WATER_BORDER, 2.0f);
		// Doors are a way through, not a wall.
		this.setPathfindingMalus(PathType.DOOR_WOOD_CLOSED, 2.0f);
		this.setPathfindingMalus(PathType.DOOR_OPEN, 0.0f);
		if (this.getNavigation() instanceof GroundPathNavigation ground) {
			ground.setCanOpenDoors(true);
			// Without this the navigator silently lifts any target below the surface to the top of
			// its column, so "dig down to that ore" walks him over the spot instead of into it.
			ground.setCanPathToTargetsBelowSurface(true);
		}
		// Things a player avoids and a mob does not think about. The numbers are the cost of entry
		// relative to plain ground (1.0): lava and fire are practically walls, an open trapdoor is a
		// pit, and a cautious node costs about as much as wading.
		this.setPathfindingMalus(PathType.LAVA, 24.0f);
		this.setPathfindingMalus(PathType.FIRE, 24.0f);
		this.setPathfindingMalus(PathType.DAMAGING, 16.0f);
		this.setPathfindingMalus(PathType.DAMAGE_CAUTIOUS, 6.0f);
		this.setPathfindingMalus(PathType.ON_TOP_OF_TRAPDOOR, 8.0f);
		this.setPathfindingMalus(PathType.TRAPDOOR, 8.0f);
	}

	/**
	 * His own navigation, so that a closed fence gate is a way through rather than a wall. Everything
	 * else about how he walks — water, doors, one-block rises, targets below the surface — is the
	 * ground navigator he had, unchanged.
	 */
	@Override
	protected PathNavigation createNavigation(Level level) {
		return new GateAwareNavigation(this, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				// A player's base, not a mob's. The game adds the held item's own modifier on top of
				// this, so 1.0 is what makes his swing carry exactly the number the owner's does with
				// the same item. A mob's 3.0 was three damage per hit that the game never chose.
				.add(Attributes.ATTACK_DAMAGE, 1.0)
				// The weapon's own pace, for a cooldown he does not invent: the game's own formula
				// (20 ticks over this number) is what paces his swing, exactly as it paces a player's.
				.add(Attributes.ATTACK_SPEED, 4.0)
				// The pathfinder plans one-block rises (WalkNodeEvaluator.getMobJumpHeight is at least
				// 1.125); at the mob default of 0.6 he planned steps his body could not take.
				.add(Attributes.STEP_HEIGHT, 1.0)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	// ==================================================================
	// Goals: exactly one following mechanism (tick-driven, owner-only)
	// and combat goals that re-check permission every tick.
	// ==================================================================

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(1, new FloatGoal(this));
		this.goalSelector.addGoal(2, new GatedMeleeAttack(this));
		// Doors: a player opens them; so does he, and the navigator is told they are passable so it
		// does not route a long way around a house to reach a bed inside it.
		this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));
		// Gates: vanilla gives him no way to open one and its planner prices a closed gate as a fence,
		// so a fenced lane was a wall. GateAwareNavigation makes the route and this opens the gate.
		this.goalSelector.addGoal(3, new OpenFenceGateGoal(this, true));
		this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
		// Deliberately absent: FollowMobGoal (it would trail whoever was nearest, not the owner)
		// and any unrestricted NearestAttackableTargetGoal (a combat-permission bypass).
		this.targetSelector.addGoal(1, new OwnerDefenseGoal(this));
	}

	/**
	 * Melee chasing that refuses to run while combat is switched off, and that swings on the weapon's
	 * pace rather than this goal's fixed twenty-tick interval.
	 *
	 * <p>{@link MeleeAttackGoal} keeps its own countdown and hurts on it, which is a timer by another
	 * name: a sword's cooldown is 12.5 ticks and a pickaxe's is 16.7, and a flat 20 made every weapon
	 * slower than the game says. Its cooldown is bypassed here and {@link #trySwingAt} decides, so the
	 * defensive fight and the asked-for one are paced by the same rule.
	 */
	private static final class GatedMeleeAttack extends MeleeAttackGoal {
		private final CobbieEntity cobbie;

		GatedMeleeAttack(CobbieEntity cobbie) {
			super(cobbie, 0.9, true);
			this.cobbie = cobbie;
		}

		@Override
		public boolean canUse() {
			return CobbieConfig.isCombatAllowed() && !cobbie.isRetreating()
					&& cobbie.getTarget() != null && super.canUse();
		}

		@Override
		public boolean canContinueToUse() {
			return CobbieConfig.isCombatAllowed() && !cobbie.isRetreating()
					&& cobbie.getTarget() != null && super.canContinueToUse();
		}

		@Override
		protected void checkAndPerformAttack(LivingEntity target) {
			if (target == null || !this.mob.getSensing().hasLineOfSight(target)) return;
			cobbie.trySwingAt(target);
		}
	}

	/** Defensive targeting: hostiles only, and only when they threaten the owner or Cobbie. */
	private static final class OwnerDefenseGoal extends Goal {
		private final CobbieEntity cobbie;
		private int scanCooldown;

		OwnerDefenseGoal(CobbieEntity cobbie) {
			this.cobbie = cobbie;
		}

		@Override
		public boolean canUse() {
			if (!CobbieConfig.isCombatAllowed()) return false;
			// Choosing a new fight while running from one is how a retreat becomes a death.
			if (cobbie.isRetreating()) return false;
			LivingEntity current = cobbie.getTarget();
			if (current != null && current.isAlive()) return false;
			if (--scanCooldown > 0) return false;
			scanCooldown = 20;
			return cobbie.findThreatToDefendAgainst() != null;
		}

		@Override
		public void start() {
			LivingEntity threat = cobbie.findThreatToDefendAgainst();
			if (threat != null) cobbie.setTarget(threat);
		}

		@Override
		public boolean canContinueToUse() {
			if (!CobbieConfig.isCombatAllowed()) return false;
			LivingEntity target = cobbie.getTarget();
			return target != null && target.isAlive() && cobbie.isLegalTarget(target);
		}

		@Override
		public void stop() {
			cobbie.setTarget(null);
		}
	}

	// ==================================================================
	// Wave animation (server sets a flag, client animates from it)
	// ==================================================================

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_WAVING, false);
		builder.define(DATA_NAME, com.rishi.cobbie.identity.CompanionName.DEFAULT);
	}

	public boolean isWaving() {
		return Boolean.TRUE.equals(this.entityData.get(DATA_WAVING));
	}

	/** 0..1 through the greeting cycle; derived from the client's own tick counter. */
	public float getWavePhase(float partialTick) {
		return (float) (((this.tickCount + partialTick) % 20.0d) / 20.0d);
	}

	// ==================================================================
	// Owner
	// ==================================================================

	public void setOwner(UUID uuid) {
		this.ownerUuid = uuid;
	}

	public UUID getOwner() {
		return ownerUuid;
	}

	public boolean isOwner(Player player) {
		return player != null && ownerUuid != null && player.getUUID().equals(ownerUuid);
	}

	/** The owner while they are online and in THIS dimension; null otherwise. */
	public ServerPlayer owner() {
		if (ownerUuid == null || level() == null) return null;
		Player found = level().getPlayerByUUID(ownerUuid);
		return found instanceof ServerPlayer sp ? sp : null;
	}

	/** The owner wherever they are, including another dimension. Null while offline. */
	public ServerPlayer ownerAnywhere() {
		if (ownerUuid == null || level() == null || level().getServer() == null) return null;
		return level().getServer().getPlayerList().getPlayer(ownerUuid);
	}

	public void startFollowing() {
		this.following = true;
	}

	/** "Stay" — stop trailing, stay exactly where he is. */
	public void stopFollowing() {
		this.following = false;
		if (level() != null && !level().isClientSide()) getNavigation().stop();
	}

	public boolean isFollowing() {
		return following;
	}

	// ==================================================================
	// Entry points for the brain / commands (safe from any thread)
	// ==================================================================

	/**
	 * Hand a validated plan to Cobbie.
	 *
	 * @return null when accepted, otherwise the reason it was refused, so the caller can say that
	 *         to the player instead of going quiet
	 */
	public String offerPlan(String request, List<ActionIntent> intents) {
		// Anyone asking him for anything ends his own project: it is his to abandon the moment the owner
		// wants him. A project that outranks an instruction would make him the one in charge.
		lastOwnerContactTick = this.tickCount;
		if (initiativePlan && tasks.hasActiveTask()) {
			tasks.cancel("the owner asked me for something else");
			initiativePlan = false;
		}
		return offerPlan(request, intents, false);
	}

	/**
	 * Start an opportunity the model chose from the shortlist: the same plan machinery, with the intent
	 * to cancel it on a word.
	 *
	 * <p>Called only by {@link com.rishi.cobbie.opportunity.OpportunityCycle}, on the server thread, and
	 * only for an opportunity that arrived from the assessor. The steps are the opportunity's own, so what
	 * runs is exactly what was offered and what was chosen — no re-invention between the two.
	 *
	 * @return null when started, otherwise why not
	 */
	public String startOpportunity(com.rishi.cobbie.opportunity.Opportunity opportunity) {
		if (opportunity == null || opportunity.steps().isEmpty()) return "nothing worth doing here";
		String refusal = tasks.start(opportunity.request(), opportunity.steps(), false);
		if (refusal == null) {
			initiativePlan = true;
			planOrigin = this.blockPosition().immutable();
			interruptedPlan = opportunity.request();
		}
		return refusal;
	}

	/** The cadence that decides when he may ask what to do next: exposed for the cycle and its tests. */
	public com.rishi.cobbie.opportunity.OpportunityCadence opportunityCadence() {
		return opportunityCadence;
	}

	/**
	 * Add work to the errand he is already on, nearest first. "Get coal while you are down there"
	 * should not mean walking back: among the things just asked for, he does the closest one next.
	 *
	 * @return null when merged, otherwise why it could not be
	 */
	public String offerAddition(String request, List<ActionIntent> intents) {
		lastOwnerContactTick = this.tickCount;
		if (intents == null || intents.isEmpty()) return "nothing to add";
		List<ActionIntent> ordered = new ArrayList<>(intents);
		ordered.sort(java.util.Comparator.comparingDouble(this::urgencyDistance));
		return tasks.append(request, ordered);
	}

	/** Lower is sooner: things with coordinates are ranked by how close they are to his feet. */
	private double urgencyDistance(ActionIntent intent) {
		if (intent == null) return Double.MAX_VALUE;
		boolean spatial = ActionVocabulary.MINE.equals(intent.action())
				|| ActionVocabulary.PLACE.equals(intent.action())
				|| ActionVocabulary.WALK_TO.equals(intent.action())
				|| ActionVocabulary.LIGHT.equals(intent.action())
				|| ActionVocabulary.COLLECT.equals(intent.action());
		if (!spatial) return Double.MAX_VALUE - 1; // talky steps go last, they cost nothing anyway
		return this.blockPosition().distSqr(new BlockPos(intent.x(), intent.y(), intent.z()));
	}

	/**
	 * Hand him an order that outranks what he is doing. The running job is parked, not cancelled, and
	 * picks up where it stopped once the order is done — that is how you steer one body mid-job.
	 */
	public String offerUrgentPlan(String request, List<ActionIntent> intents) {
		return offerPlan(request, intents, true);
	}

	private String offerPlan(String request, List<ActionIntent> intents, boolean urgent) {
		if (this.isRemoved()) return "I am not around any more";
		String impossible = unsatisfiableCraft(intents);
		if (impossible != null) {
			// Refused, not announced: nothing is handed to the runner, so there is no plan to fail and no
			// promise in the chat about work that was never possible.
			return "I can't make that — " + impossible;
		}
		String refusal = tasks.start(request, intents, urgent);
		if (refusal == null) {
			planOrigin = this.blockPosition().immutable();
			// Saved with him: a reload while this plan is in flight is exactly what he must own up to.
			interruptedPlan = request;
		}
		return refusal;
	}

	/** The plan he is running, or the one that most recently finished (for reporting / learning). */
	public com.rishi.cobbie.task.TaskPlan lastPlan() {
		return tasks.plan();
	}

	/**
	 * The first plan created after this many were already known — the way a caller binds a plan to the request
	 * that made it. Null means the request produced no plan at all.
	 */
	public com.rishi.cobbie.task.TaskPlan planAfter(long id) {
		return tasks.planAfter(id);
	}

	/** The most recently created plan's id: a caller notes this before asking, so it can bracket the ask. */
	public long latestPlanId() {
		return tasks.latestPlanId();
	}

	/** What became of a plan he started earlier: its request, status and report. */
	public String planRecord(long id) {
		var plan = tasks.planById(id);
		return plan == null ? null : plan.request() + " -> " + plan.status() + " — " + plan.report();
	}

	/** Plans created since an id: one is the ask's, more than one means a follow-up slipped into the window. */
	public int plansSince(long id) {
		return tasks.plansSince(id);
	}

	public String companionName() {
		return com.rishi.cobbie.identity.CompanionName.orDefault(this.entityData.get(DATA_NAME));
	}

	/** One of his own chat lines, prefixed with his own name. */
	public net.minecraft.network.chat.Component says(String text) {
		return net.minecraft.network.chat.Component
				.literal("§e[" + companionName() + "] §f" + (text == null ? "..." : text));
	}

	/**
	 * Rename him. The name lives here, is saved with him, and reaches the nameplate, his panel, his chat
	 * lines and his own prompt — a nameplate that disagrees with what he calls himself is the one bug
	 * this is built to avoid. Memory, skills and levels are keyed by owner, so nothing is orphaned.
	 */
	public String renameTo(String raw) {
		var check = com.rishi.cobbie.identity.CompanionName.check(raw);
		if (!check.ok()) return check.refusal();
		if (!check.name().equals(companionName())) {
			// His history still holds lines in the old name. Without a note the next prompt shows one name
			// while the transcript says another, and the likely symptom is him denying his own words.
			renameNote = "The owner has renamed you to " + check.name()
					+ "; any earlier lines of yours under a different name are still yours.";
		}
		this.entityData.set(DATA_NAME, check.name());
		// Rebuilt from the new name immediately: the nameplate is cached text and would otherwise keep
		// the old name until its next tick.
		showNameplate("§e" + check.name() + " §8· §7" + statusWord());
		return null;
	}

	/** Set by a rename, read once by the next prompt. Not saved: it is about one turn, not the world. */
	private String renameNote = "";

	/** The pending rename note, consumed by whoever builds the next prompt. */
	public String takeRenameNote() {
		String note = renameNote;
		renameNote = "";
		return note == null ? "" : note;
	}

	/**
	 * Renaming is an identity change, not a cosmetic one — his chat lines, his prompt and his memory all
	 * speak in that name — so it belongs to the owner. In single-player that is the same person; on a LAN
	 * it is the difference between "my companion" and "ours".
	 */
	public String renameBy(net.minecraft.world.entity.player.Player actor, String raw) {
		if (actor == null || !isOwner(actor)) return "only my owner can name me";
		return renameTo(raw);
	}

	/** Nameplate text that is not a name: connecting, offline, and the status line itself. */
	public void showNameplate(String text) {
		super.setCustomName(net.minecraft.network.chat.Component.literal(text));
		this.setCustomNameVisible(true);
	}

	/**
	 * A vanilla name tag arrives here. It lands in the same field as /cobbie name, so a player who uses a
	 * tag gets a companion whose panel and prompt agree with the plate — and a tag carrying formatting is
	 * refused by the same rules rather than silently rendering as colour.
	 */
	@Override
	public void setCustomName(net.minecraft.network.chat.Component name) {
		String text = name == null ? "" : name.getString();
		// Our own plate, in its own format — including the copy vanilla reads back from a save, which
		// arrives before the name field is populated and must not be mistaken for a name tag.
		if (text.startsWith("§e") && text.contains(" §8· §7")) {
			super.setCustomName(name);
			return;
		}
		if (renameTo(text) != null) {
			showNameplate("§e" + companionName() + " §8· §7" + statusWord());
		}
	}

	public BlockPos planOrigin() {
		return planOrigin;
	}

	/** Queue a single action. */
	public void enqueue(ActionIntent intent) {
		if (intent == null) return;
		offerPlan(intent.action(), List.of(intent));
	}

	public void wave() {
		this.waveTicksLeft = WAVE_TICKS;
		this.entityData.set(DATA_WAVING, true);
	}

	public void say(String text) {
		enqueue(new ActionIntent(ActionVocabulary.CHAT, text, 0, 0, 0, ""));
	}

	public void moveTo(BlockPos pos) {
		enqueue(new ActionIntent(ActionVocabulary.WALK_TO, "", pos.getX(), pos.getY(), pos.getZ(), ""));
	}

	public void mine(BlockPos pos) {
		enqueue(new ActionIntent(ActionVocabulary.MINE, "", pos.getX(), pos.getY(), pos.getZ(), ""));
	}

	public void place(String blockName, BlockPos pos) {
		enqueue(new ActionIntent(ActionVocabulary.PLACE, blockName, pos.getX(), pos.getY(), pos.getZ(), ""));
	}

	public String currentTaskDescription() {
		return tasks.currentTaskDescription();
	}

	public String lastActionResult() {
		return lastActionResult;
	}

	public String lastSpokenLine() {
		return lastSpokenLine;
	}

	/** Record a line he actually said: the newest field, and the bounded window behind it. */
	private void noteSaid(String text) {
		if (text == null || text.isBlank()) return;
		lastSpokenLine = text;
		recentLines.add(text);
	}

	/**
	 * Did he say a line containing this fragment recently? A window, not a slot — asked this way so a report landing
	 * a tick behind the line it summarises cannot score as "he never said it".
	 */
	public boolean saidRecently(String fragment) {
		return recentLines.saidRecently(fragment);
	}

	/** What he has said recently, oldest first, for a failure message that shows the actual words. */
	public java.util.List<String> recentLines() {
		return recentLines.recent();
	}

	/** One-shot "here is what actually happened" text when a plan settles. */
	public String takeTaskReport() {
		return tasks.takeReport();
	}

	/** The world he was in last tick, so a portal is noticed on the very next one. */
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastWorld;

	public boolean isBusy() {
		return tasks.hasActiveTask() || active != null;
	}

	public boolean cancelTask(String reason) {
		return tasks.cancel(reason);
	}

	/** The project request saved as in flight, for whoever has to check the marker: blank when idle. */
	public String interruptedPlanRequest() {
		return interruptedPlan;
	}

	/** Whether the line about a lost project is still owed — the state a reload check has to read. */
	public boolean wakeLinePending() {
		return wakeLinePending;
	}

	/**
	 * One line for the owner, in his own voice, recorded as the last thing he said.
	 *
	 * <p>Everything he says to the owner goes through the same prefix — the companion's own name — so the
	 * chat line and the nameplate cannot disagree. Returns whether it actually reached them, because a
	 * line owed to an owner who is still joining is a line not yet said.
	 */
	public boolean sayToOwner(String text) {
		if (text == null || text.isBlank()) return false;
		net.minecraft.server.level.ServerPlayer owner = ownerAnywhere();
		if (owner == null) return false;
		owner.sendSystemMessage(com.rishi.cobbie.brain.CobbieBrain.cobbieSays(this, text));
		noteSaid(text);
		return true;
	}

	/**
	 * The wake-up line: a save that came back with a project in flight lost that project, and saying what
	 * was lost is the difference between a companion who forgot and one who vanished without a word.
	 *
	 * <p>Said exactly once, on the first tick he can be heard — never in the read method, which runs
	 * before he is in a world, and never again on a later login: the marker is cleared as soon as it is
	 * spoken, so a save taken afterwards carries nothing to say. A reload with nothing in flight leaves
	 * the marker blank and he says nothing at all, which is the half of this that matters most.
	 */
	private void wakeTick() {
		if (!wakeLinePending) return;
		if (!sayToOwner(WAKE_LINE)) return;
		wakeLinePending = false;
		interruptedPlan = "";
		Cobbie.LOGGER.info("Cobbie woke into a save with a project in flight and said so");
	}

	/**
	 * Has the world moved on since this step's plan was made? Asked at the moment a step is about to run,
	 * and once at adoption, by one rule.
	 *
	 * <p><b>The rule: answer only from something he can see, and answer in the direction that cannot
	 * produce a falsehood.</b> A block that is now air is a step the world has done — that is a
	 * completion, and saying so is not a claim about work he did. A block that is now stone where the plan
	 * had a free square is a step whose precondition has changed, and {@link StepCheck#broken} says what
	 * changed rather than retrying the same impossible thing. Everything else — a verb with no such
	 * predicate, a target whose kind he has no record of, an unloaded chunk — is
	 * {@link StepCheck#ready()} or {@link StepCheck#unknown()}, and the step runs and fails with its own
	 * reason. A wrong "ready" costs a walk; a wrong "satisfied" is a false completion claim, which is the
	 * one failure this project exists to prevent.
	 *
	 * <p>What is deliberately <b>not</b> answered here: whether the bag holds what a step needs, whether a
	 * furnace is present, whether a chain of recipes exists. Those are the step's own questions and each
	 * verb re-asks them as it starts (a craft re-plans its chain, a smelt re-resolves its furnace), so a
	 * second rule here would be two answers to one question waiting to disagree.
	 */
	@Override
	public StepCheck checkStep(ActionIntent intent) {
		if (intent == null || level() == null) return StepCheck.unknown("nothing to look at");
		String verb = intent.action() == null ? "" : intent.action();
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!level().isLoaded(pos)) return StepCheck.unknown("that area is not loaded");

		return switch (verb) {
			// The thing he was sent to remove is gone: the goal holds, and there is nothing to swing at.
			case ActionVocabulary.MINE -> {
				BlockState state = level().getBlockState(pos);
				if (state.isAir()) yield StepCheck.satisfied("there is nothing left at " + describe(pos) + " to break");
				Block named = resolveBlock(intent.target());
				yield named != null && !state.is(named)
						? StepCheck.broken(describe(pos) + " is " + blockId(state) + " now, not the "
								+ wordsFor(intent.target()) + " i was sent for")
						: StepCheck.ready();
			}
			// What he was sent to build is already standing there, or the square is no longer free.
			case ActionVocabulary.PLACE, ActionVocabulary.LIGHT -> {
				BlockState wanted = resolveState(
						ActionVocabulary.LIGHT.equals(verb) ? "minecraft:torch" : intent.target(), pos);
				if (wanted == null) yield StepCheck.unknown("i do not know a block called " + intent.target());
				BlockState standing = level().getBlockState(pos);
				if (standing.is(wanted.getBlock())) {
					yield StepCheck.satisfied("the " + blockId(wanted) + " is already standing at " + describe(pos));
				}
				yield !standing.isAir() && !standing.canBeReplaced()
						? StepCheck.broken(describe(pos) + " is " + blockId(standing) + " now, where i was going to build")
						: StepCheck.ready();
			}
			// Being near a spot is not standing in it, and a destination he could never occupy is not
			// satisfied by distance — without the fit test this answered yes for a walk aimed at solid
			// stone, which became "nothing left to do — the world had already done that" about a place he
			// cannot stand in.
			case ActionVocabulary.WALK_TO -> {
				if (!BodySpace.fits(this::solidAt, pos.getX(), pos.getY(), pos.getZ())) {
					yield StepCheck.broken(describe(pos) + " is " + blockId(level().getBlockState(pos))
							+ " now, so i cannot stand there any more");
				}
				yield this.blockPosition().closerThan(pos, 1.5)
						? StepCheck.satisfied("i am already at " + describe(pos))
						: StepCheck.ready();
			}
			// The container was there when the plan was made, and what he was sent for is not in it any
			// more. Asked of the contents, not of the block: a chest that is still standing and empty is
			// exactly the case a player makes by taking the coal out while he walks over.
			case ActionVocabulary.TAKE -> {
				if (!(level() instanceof ServerLevel serverLevel)) yield StepCheck.unknown("no server world");
				Container container = Containers.resolve(serverLevel, pos, owner(), CobbieConfig.isEnderChestAllowed());
				if (container == null) yield StepCheck.ready(); // nothing resolved: a question he cannot answer
				String what = resolveItemId(intent.target());
				if (Containers.count(container, what) > 0) yield StepCheck.ready();
				String where = "the " + Containers.nameOf(serverLevel, pos) + " at " + describe(pos);
				yield StepCheck.broken(what == null || what.isBlank()
						? where + " is empty now"
						: where + " has no " + wordsFor(what) + " in it now");
			}
			default -> StepCheck.ready();
		};
	}

	/**
	 * Where else this same step could be done, now that the place it named is taken.
	 *
	 * <p><b>One verb wide on purpose.</b> A substitute may move the coordinates and nothing else, so it is
	 * only honest where the coordinate is a spot rather than the thing asked for: the square for a torch
	 * someone just built in is the one case in this codebase where "there" and "there" are the same job.
	 * A chest that was emptied is not re-sourced from the chest next door, and a destination that no
	 * longer fits is not quietly moved — those are the instruction, so they stop the plan and say what
	 * changed instead.
	 */
	@Override
	public ActionIntent replan(ActionIntent broken, StepCheck why) {
		if (broken == null || why == null || level() == null) return null;
		String verb = broken.action() == null ? "" : broken.action();

		// A log that was broken out from under him: the job was one log, and there may be another. Only
		// when the step names what it wanted — the coordinates alone do not say what to look for.
		if (ActionVocabulary.MINE.equals(verb) && why.isSatisfied()) {
			Block named = resolveBlock(broken.target());
			if (named == null) return null;
			BlockPos was = new BlockPos(broken.x(), broken.y(), broken.z());
			BlockPos other = nearestBlockOf(named, was, MINE_REPLAN_RADIUS);
			return other == null ? null
					: new ActionIntent(verb, broken.target(), other.getX(), other.getY(), other.getZ(), broken.data());
		}

		// A square someone else built in, with the thing he was going to build still in his bag. Beside it
		// before above it: a torch goes on the wall next to the blocked spot, not on their head.
		if ((ActionVocabulary.PLACE.equals(verb) || ActionVocabulary.LIGHT.equals(verb)) && why.isBroken()) {
			String block = ActionVocabulary.LIGHT.equals(verb) ? "minecraft:torch" : broken.target();
			BlockPos was = new BlockPos(broken.x(), broken.y(), broken.z());
			if (!level().isLoaded(was)) return null;
			BlockPos[] candidates = { was.north(), was.south(), was.east(), was.west(), was.above() };
			double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
			for (BlockPos candidate : candidates) {
				if (!level().isLoaded(candidate)) continue;
				if (placementRefusal(block, candidate) != null) continue;
				BlockPos from = this.blockPosition().distSqr(candidate) > reachSquared()
						? standingSpotNear(candidate) : this.blockPosition();
				if (from == null || this.blockPosition().distSqr(from) > navLimit * navLimit) continue;
				return new ActionIntent(verb, broken.target(), candidate.getX(), candidate.getY(),
						candidate.getZ(), broken.data());
			}
		}
		return null;
	}

	/** The nearest block of this kind within {@code radius} stood at a height he could work from. */
	private BlockPos nearestBlockOf(Block block, BlockPos from, int radius) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos at = from.offset(dx, dy, dz);
					if (at.equals(from) || !level().isLoaded(at)) continue;
					if (!level().getBlockState(at).is(block)) continue;
					double distance = from.distSqr(at);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = at.immutable();
					}
				}
			}
		}
		return best;
	}


	/**
	 * A plan is a list of coordinates in one world. When a portal or a teleport changes the world
	 * under him, those coordinates are a place he can no longer reach, and the step fails with "that
	 * area is not loaded" — which a player reads as him being thick rather than as the plan being
	 * stale. The world change is where the plan ends: one plain line, nothing left running.
	 */
	private void watchWorldChange() {
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> world = level().dimension();
		if (lastWorld == null) {
			lastWorld = world;
			return;
		}
		if (lastWorld.equals(world)) return;
		lastWorld = world;
		onWorldChanged();
	}

	/**
	 * End a live plan because the world under it changed. Public because the trigger is a comparison
	 * this class cannot always make: on a portal crossing the game can replace the companion with a
	 * new instance, and then the remembered world starts empty. Any caller that knows the world
	 * changed — this tick, or a hook holding state that outlives the instance — ends the plan here.
	 */
	public void onWorldChanged() {
		if (!tasks.hasActiveTask()) return;
		cancelTask(WORLD_CHANGED);
		say(WORLD_CHANGED);
	}

	/**
	 * A body inside a solid block is the one state nothing else recovers from.
	 *
	 * <p>Suffocation damage starts on the first tick, and an encased companion has no plan that can help
	 * him: the pathfinder has nothing to plan from, so every step times out while he loses health. A walk
	 * aimed into a wall is refused before it starts, but that is only one cause — a piston, a teleport, a
	 * crash, or a world built over his head will do it too — so the check lives on the body and not on the
	 * thing that pushed him in.
	 *
	 * <p>Ten ticks is well before the damage is fatal, and long enough not to fight the game's own push-out
	 * when he is only a hair inside a corner. The work he was on is cancelled rather than resumed: a plan
	 * built on coordinates he could not occupy is the plan that put him there.
	 */
	private void wallRescueTick() {
		if (!this.isInWall()) {
			wallTicks = 0;
			wallRescued = false;
			return;
		}
		if (++wallTicks <= WALL_RESCUE_TICKS) return;
		wallTicks = 0;

		BlockPos stuck = this.blockPosition().immutable();
		int[] free = BodySpace.nearestFree(this::solidAt, stuck.getX(), stuck.getY(), stuck.getZ(),
				WALL_RESCUE_RADIUS);
		// What was in flight when the body was in a wall, and how many times the walk had already been
		// repaired: this guard fixes a symptom, and the symptom is only worth having in the log beside
		// the plan it happened under.
		TaskPlan live = tasks.plan();
		TaskStep step = live == null ? null : live.running();
		String context = live == null || live.isFinished() ? "no plan in flight"
				: "plan " + live.id() + " \"" + live.request() + "\" step "
						+ (step == null ? "between steps" : (step.index() + 1) + " " + step.intent().action()
								+ " attempt " + step.attempts())
						+ ", walk repaths " + walkRepaths;
		rescues++;
		cancelTask("i was inside a block at " + describe(stuck));
		getNavigation().stop();
		if (free != null) {
			this.teleportTo(free[0] + 0.5, free[1], free[2] + 0.5);
		}
		Cobbie.LOGGER.warn("Cobbie wall rescue #{}: inside a block at {} ({}); recovery destination {}",
				rescues, describe(stuck), context,
				free == null ? "nowhere free nearby" : free[0] + " " + free[1] + " " + free[2]);
		if (!wallRescued) {
			wallRescued = true;
			say(free == null
					? "i am stuck inside a block and cannot get out"
					: "i was inside a block, got myself out");
		}
	}

	/**
	 * Fire, lava and cactus: his body is in something that is taking his health, and no plan is worth
	 * standing in it.
	 *
	 * <p>This lives on the tick beside {@link #wallRescueTick()} and not in a verb, for the same reason:
	 * a body can be put into a fire by the walk it was given, by a piston, by a creeper's knockback or by
	 * the world changing under it, and the one thing that must not happen is that he keeps walking deeper
	 * because a plan said so. So the plan in flight is cancelled rather than obeyed, and he steps onto the
	 * nearest square that is not on fire — four blocks at most, the same bounded search the wall rescue
	 * uses — and says which of the three he got out of.
	 *
	 * <p>In a fight he stays where he is: a companion who flees a fire is alive, and one who flees a fight
	 * he was winning has lost the fight. Getting out of the fire after it is the lesser of the two.
	 */
	private void hazardRescueTick() {
		String hazard = hazardOnBody();
		if (hazard == null || isFighting()) {
			hazardTicks = 0;
			return;
		}
		// A couple of ticks of contact, not one: walking past a lit campfire or through the edge of a
		// creeper's blast should not teleport him sideways. Five ticks is a quarter second.
		if (++hazardTicks < HAZARD_RESCUE_TICKS) return;
		hazardTicks = 0;

		BlockPos from = this.blockPosition().immutable();
		int[] safe = BodySpace.nearestSafe(this::solidAt, (x, y, z) -> !blockHurts(x, y, z),
				from.getX(), from.getY(), from.getZ(), HAZARD_RESCUE_RADIUS);
		boolean stoppedThePlan = cancelTask("i was standing in " + hazard + ", so i stopped what i was doing");
		getNavigation().stop();
		interrupt();
		if (safe == null) {
			Cobbie.LOGGER.warn("Cobbie hazard rescue: standing in {} at {} and found nowhere safe within {} blocks",
					hazard, describe(from), HAZARD_RESCUE_RADIUS);
			say("i am standing in " + hazard + " and there is nowhere safe to step");
			return;
		}
		hazardRescues++;
		this.teleportTo(safe[0] + 0.5, safe[1], safe[2] + 0.5);
		this.setDeltaMovement(Vec3.ZERO);
		Cobbie.LOGGER.info("Cobbie hazard rescue #{}: out of {} at {} to {} {} {}", hazardRescues, hazard,
				describe(from), safe[0], safe[1], safe[2]);
		say("i was standing in " + hazard + ", so i got myself out"
				+ (stoppedThePlan ? " and stopped what i was doing" : ""));
	}

	/**
	 * The thing his body is actually in, in the word he would use for it, or null.
	 *
	 * <p>Asked of the blocks his bounding box overlaps, not of the two blocks his feet and head are
	 * centred in: a cactus hurts when his shoulder is in it, and a campfire hurts when he is standing in
	 * the half-block the game leaves you standing in. The first version of this asked whether a cactus was
	 * in the next column and teleported him away from one he was never touching — a rule written for the
	 * case someone imagined rather than for the one the game has.
	 */
	private String hazardOnBody() {
		AABB body = this.getBoundingBox().inflate(0.02);
		for (BlockPos at : BlockPos.betweenClosed(
				Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
				Mth.floor(body.maxX), Mth.floor(body.maxY), Mth.floor(body.maxZ))) {
			String word = hazardWord(level().getBlockState(at));
			if (word != null) return word;
		}
		return null;
	}

	/** Does this block take health from a body in it — the question the escape is actually about. */
	private String hazardWord(BlockState state) {
		if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA)) return "lava";
		if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return "fire";
		if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return "a campfire";
		if (state.is(Blocks.MAGMA_BLOCK)) return "hot ground";
		if (state.is(Blocks.SWEET_BERRY_BUSH)) return "a berry bush";
		if (state.is(Blocks.WITHER_ROSE)) return "a wither rose";
		if (state.is(Blocks.CACTUS)) return "a cactus";
		return null;
	}

	/**
	 * Is this a square he could stand in for a moment without being hurt? The ground under it counts too:
	 * a square over magma is free space that costs health, which is the same mistake as stepping out of a
	 * fire into a campfire.
	 */
	private boolean blockHurts(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (!level().isLoaded(pos)) return true;   // unknown ground is not somewhere to put him
		return hazardWord(level().getBlockState(pos)) != null;
	}

	/** Is he in a fight right now — a swinging step, a target, or one on the way? */
	private boolean isFighting() {
		return hunted != null || this.getTarget() != null
				|| (active != null && ActionVocabulary.ATTACK.equals(active.action()));
	}

	/** A registry id as words a person reads: "minecraft:oak_log" is "oak log" when he says it out loud. */
	private static String wordsFor(String id) {
		String short_ = Narrator.shortTarget(id);
		return short_ == null ? id : short_;
	}

	/** Does the block at these coordinates stop a body? Unloaded ground counts as solid, so nothing aims at it. */
	private boolean solidAt(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (!level().isLoaded(pos)) return true;
		return !level().getBlockState(pos).getCollisionShape(level(), pos).isEmpty();
	}

	public WorldSnapshot perceive() {
		String result = lastActionResult;
		if (pickedUpSinceReport > 0) {
			// He physically walked over something. Saying so is what stops him answering "I have
			// nothing" two seconds after picking it up.
			String note = "picked up " + pickedUpSinceReport + " item(s): " + String.join(", ", pickedUpIds);
			result = (result == null || result.isBlank()) ? note : note + " (before: " + result + ")";
			pickedUpSinceReport = 0;
			pickedUpIds.clear();
		}
		return WorldPerception.snapshot(this, currentTaskDescription(),
				active == null ? "idle" : active.action(), result);
	}

	// ==================================================================
	// Tick — server side only below the guard
	// ==================================================================

	@Override
	public void tick() {
		super.tick();
		if (level() == null || level().isClientSide()) return;

		wakeTick();

		if (waveTicksLeft > 0 && --waveTicksLeft == 0) {
			this.entityData.set(DATA_WAVING, false);
		}

		spillOverflow();
		absorbExperience();
		// The game's own food tick: exhaustion drains it, high food heals him, zero food starves him,
		// and he eats what he carries when it runs low. Before the actions, so a heal from a full bar
		// lands before he decides what to do next.
		hunger.tick();
		// Keeps him findable by the server tick if his chunk ever stops ticking.
		CrossDimensionFollow.remember(this);
		watchWorldChange();
		wallRescueTick();
		hazardRescueTick();
		disengageTick();
		shieldTick();
		updateNameplate();
		followOwnerTick();
		swimTick();
		climbTick();

		ActionOutcome settled = driveActiveStep();
		if (settled != null) pendingOutcome = settled;

		tasks.tick(System.currentTimeMillis());

		// Push the finished plan's verified report to the brain (chat, memory, skill credit,
		// learning, and the model's own feedback all hang off that single delivery).
		String report = tasks.takeReport();
		if (report != null) {
			initiativePlan = false;
			com.rishi.cobbie.task.TaskPlan settledPlan = tasks.plan();
			com.rishi.cobbie.presence.ActionTiming.settled(System.currentTimeMillis(),
					settledPlan == null ? 0 : settledPlan.steps().size());
			// A plan the world took away ends with a reason, said here rather than left to the settled job
			// line: "partly done" behind it is a report, and a companion who stops without one is a
			// companion who stopped for no visible cause.
			if (settledPlan != null && !settledPlan.abortReason().isBlank()) {
				sayToOwner(settledPlan.abortReason());
			}
			com.rishi.cobbie.brain.CobbieBrain.reportPlanSettled(this, settledPlan);
		}

		// The marker is about work in flight. Once nothing is running and nothing is waiting behind it,
		// a reload would lose nothing, so a later save must not manufacture a line out of a stale fact.
		if (!interruptedPlan.isEmpty() && !tasks.hasActiveTask() && tasks.queuedCount() == 0) {
			interruptedPlan = "";
		}

		flushNarration();
		observeWhenIdle();
		memorySweepTick();

		// Pick up what is already within arm's reach. Quietly, no project attached.
		if (CobbieConfig.isCollectionAllowed() && this.tickCount - lastPickupTick >= PICKUP_INTERVAL) {
			lastPickupTick = this.tickCount;
			collectWithinReach();
		}

		// Nothing here decides what he does when he is idle: that is the opportunity cycle below, which
		// asks the model to choose among what he can actually start. The old one-in-four-hundred random
		// wander lived here and was the last place he invented a goal for himself.
		opportunityTick();
	}

	/**
	 * Re-read what he remembers against the world, every {@link com.rishi.cobbie.memory.WorldMemory#SWEEP_TICKS}
	 * ticks. A memory he is about to act on is checked at that moment; this is the other end of the same
	 * rule, so {@code /cobbie memory} stops listing a chest that was emptied an hour ago.
	 */
	private void memorySweepTick() {
		if (this.tickCount % com.rishi.cobbie.memory.WorldMemory.SWEEP_TICKS != 0) return;
		com.rishi.cobbie.memory.WorldMemory.sweep(this);
	}

	/**
	 * Ask what he could do, and let the model pick one — the whole of his autonomy, once per change.
	 *
	 * <p>Four gates, each with a reason: <b>the switch is on</b>, <b>creative autonomy only</b> (assistive
	 * stays assistive), <b>nothing else is running</b>, and <b>the owner has been quiet</b> for
	 * {@link #INITIATIVE_QUIET_TICKS} — starting work while being spoken to is worse than waiting.
	 *
	 * <p>Everything after the gates is {@link com.rishi.cobbie.opportunity.OpportunityCycle}: the cadence
	 * decides whether this scene is worth a question at all (an unchanged scene is not, until the slow
	 * heartbeat), the assessor lists what can start right now, and the model's answer — or its rejection —
	 * becomes the plan. <b>Nothing here falls back to a candidate when the model does not answer</b>: a
	 * silent brain means WAIT, because the alternative is the assessor deciding, which is the picker this
	 * slice replaced.
	 */
	private void opportunityTick() {
		if (!CobbieConfig.isInitiativeAllowed()) return;
		if (!CobbieConfig.isCreative()) return;
		if (isBusy() || active != null || pendingOutcome != null) return;
		if (this.tickCount - lastOwnerContactTick < INITIATIVE_QUIET_TICKS) return;
		// The fingerprint scans the neighbourhood for hostiles and the bag for what he carries, so it is
		// read twice a second rather than every tick. Half a second of latency on "a change arrived" is
		// nothing to a companion, and the alternative is an entity query in every tick of every body.
		if (this.tickCount % SITUATION_INTERVAL_TICKS != 0) return;
		if (com.rishi.cobbie.opportunity.OpportunitySelector.busy()) return;

		com.rishi.cobbie.opportunity.Situation scene = com.rishi.cobbie.opportunity.Situation.of(this);
		if (!opportunityCadence.shouldAsk(this.tickCount, scene)) return;

		com.rishi.cobbie.opportunity.Shortlist list = com.rishi.cobbie.opportunity.OpportunityAssessor.assess(this);
		// Recorded even when the list is empty: the situation was examined, and re-examining an unchanged
		// one on the next tick would be a poll. The heartbeat is what tries again.
		opportunityCadence.noted(this.tickCount, scene);
		if (list.isEmpty()) {
			com.rishi.cobbie.opportunity.OpportunityCycle.nothingOffered(this, opportunityCadence.why(this.tickCount, scene));
			return;
		}
		net.minecraft.server.MinecraftServer server = level().getServer();
		if (server == null) return;
		com.rishi.cobbie.opportunity.OpportunityCycle.begin(this, list, server);
	}

	private record DeferredDrop(ServerLevel level, BlockPos at, List<ItemStack> stacks) {}

	private static final List<DeferredDrop> PENDING_DROPS =
			java.util.Collections.synchronizedList(new ArrayList<>());

	/** Called once per server tick: put a dead companion's bag on the ground where he fell. */
	public static void tickDeferredDrops() {
		if (PENDING_DROPS.isEmpty()) return;
		List<DeferredDrop> batch;
		synchronized (PENDING_DROPS) {
			batch = new ArrayList<>(PENDING_DROPS);
			PENDING_DROPS.clear();
		}
		int placed = 0;
		for (DeferredDrop drop : batch) {
			for (ItemStack stack : drop.stacks()) {
				if (stack.isEmpty()) continue;
				ItemEntity pile = new ItemEntity(drop.level(), drop.at().getX() + 0.5, drop.at().getY() + 0.2,
						drop.at().getZ() + 0.5, stack.copy());
				pile.setDefaultPickUpDelay();
				if (drop.level().addFreshEntity(pile)) placed++;
			}
		}
		Cobbie.LOGGER.info("Cobbie corpse: {} stack(s) placed on the ground", placed);
	}

	/**
	 * One word for what he is doing right now, for the tag above his head.
	 *
	 * <p>The order matters and used to be wrong: a queued thought outranked the body, so a companion
	 * standing perfectly still read "thinking…" for as long as any request was out — including the
	 * one he used to make after every job just to comment on it. Now the body wins, a model call only
	 * shows while he is genuinely waiting on an answer, and "following you" is reserved for the
	 * moments he is actually walking after you rather than for the whole time he is on follow.
	 */
	public String statusWord() {
		if (active != null) {
			return switch (active.action()) {
				case ActionVocabulary.WALK_TO -> this.isInWater() ? "swimming" : "walking";
				case ActionVocabulary.MINE -> "mining";
				case ActionVocabulary.PLACE -> "building";
				case ActionVocabulary.LIGHT -> "lighting";
				case ActionVocabulary.ATTACK -> "fighting";
				case ActionVocabulary.COLLECT -> "picking up";
				case ActionVocabulary.DROP -> "dropping";
				case ActionVocabulary.EQUIP -> "gearing up";
				case ActionVocabulary.GIVE -> "handing over";
				case ActionVocabulary.EXPLORE -> "exploring";
				case ActionVocabulary.CRAFT -> "crafting";
				default -> "busy";
			};
		}
		if (tasks != null && tasks.hasActiveTask()) return "working";
		if (isBusy()) return "working";
		if (com.rishi.cobbie.brain.CobbieBrain.inFlightCount() > 0) return "thinking…";
		if (following && !getNavigation().isDone()) return this.isInWater() ? "swimming" : "following you";
		return "idle";
	}

	/**
	 * Keep the tag above his head honest. Only the connected nameplate is extended — "Connecting…"
	 * and "(LLM offline)" belong to the spawn/connection code and are left alone.
	 */
	private void updateNameplate() {
		if (this.tickCount % 10 != 0 || level() == null || level().isClientSide()) return;
		Component current = this.getCustomName();
		String text = current == null ? "" : current.getString();
		String prefix = "§e" + companionName() + " §8· §7";
		if (!text.startsWith("§e" + companionName())) return;
		String target = prefix + statusWord();
		if (!target.equals(text)) {
			showNameplate(target);
		}
	}

	/**
	 * Shift-right-click opens his panel: who he is, what he is doing, the job and its steps, and his
	 * actual bag with icons and counts.
	 *
	 * <p>The chat readout is kept, but only as the fallback for a client that cannot receive the
	 * panel packet — a dedicated-server client running the mod is the case that matters.
	 */
	@Override
	protected net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!level().isClientSide() && player.isShiftKeyDown() && player instanceof ServerPlayer viewer) {
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
					.canSend(viewer, CobbiePanelPayload.TYPE)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(viewer, panelData());
			} else {
				viewer.sendSystemMessage(Component.literal("§e[Cobbie] §f" + describeWork()));
			}
			return net.minecraft.world.InteractionResult.SUCCESS;
		}
		return super.mobInteract(player, hand);
	}

	/** Everything the panel draws, assembled on the server where his bag actually lives. */
	public CobbiePanelPayload panelData() {
		TaskPlan plan = tasks == null ? null : tasks.plan();
		List<String> steps = new ArrayList<>();
		List<Integer> states = new ArrayList<>();
		String job = "";
		if (plan != null) {
			job = plan.request();
			for (TaskStep step : plan.steps()) {
				steps.add(Narrator.stepLabel(step.intent()));
				states.add(switch (step.status()) {
					case RUNNING -> CobbiePanelPayload.RUNNING;
					case DONE -> CobbiePanelPayload.DONE;
					case FAILED -> CobbiePanelPayload.FAILED;
					case SKIPPED -> CobbiePanelPayload.SKIPPED;
					default -> CobbiePanelPayload.PENDING;
				});
			}
		}
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			items.add(inventory.getItem(i).copy());
		}
		// The held tool has been raised out of the bag, so it would otherwise look as if he is
		// carrying nothing while mining. It is also marked, so the panel can show which slot is in
		// his hand rather than making you guess.
		ItemStack held = this.getMainHandItem();
		int heldIndex = -1;
		if (!held.isEmpty()) {
			for (int i = 0; i < items.size(); i++) {
				ItemStack stack = items.get(i);
				if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, held)) {
					heldIndex = i;
					break;
				}
			}
			if (heldIndex < 0) {
				items.add(held.copy());
				heldIndex = items.size() - 1;
			}
		}
		return new CobbiePanelPayload(this.getId(), companionName(), statusWord(), job, steps, states, items,
				heldIndex,
				experienceLevel, Math.round(experienceProgress), experienceNeeded(), hunger.describe());
	}

	/** Everything the owner asked for: current job, its steps, and anything waiting behind it. */
	public String describeWork() {
		StringBuilder sb = new StringBuilder();
		sb.append("Right now: ").append(statusWord());
		com.rishi.cobbie.task.TaskPlan plan = tasks == null ? null : tasks.plan();
		if (plan == null) {
			sb.append("\nNo job. Tell me what to do.");
		} else {
			sb.append("\nJob: ").append(plan.request()).append(" [").append(plan.status().name().toLowerCase()).append("]");
			int i = 1;
			for (com.rishi.cobbie.task.TaskStep step : plan.steps()) {
				sb.append("\n ").append(i++).append(". ").append(step.intent().action());
				if (step.intent().target() != null && !step.intent().target().isBlank()) {
					sb.append(' ').append(step.intent().target());
				}
				if (ActionVocabulary.needsPosition(step.intent().action())) {
					sb.append(" @").append(step.intent().x()).append(' ').append(step.intent().y())
							.append(' ').append(step.intent().z());
				}
				sb.append(" - ").append(step.status().name().toLowerCase());
			}
			int waiting = tasks.queuedCount();
			if (waiting > 0) sb.append("\n").append(waiting).append(" more job(s) waiting.");
		}
		sb.append("\nCarrying: ").append(describeInventory());
		return sb.toString();
	}

	/**
	 * A dying companion leaves his things behind. Vanilla deletes a mob's inventory, which for a body
	 * that carries what you gave him is silent data loss.
	 *
	 * <p>Spawning happens after {@code super.die()} rather than in the death-loot hook: entity adds
	 * made while the mob is still mid-death did not survive into the world.
	 */
	@Override
	public void die(net.minecraft.world.damagesource.DamageSource source) {
		ServerLevel level = this.level() instanceof ServerLevel server ? server : null;
		ServerPlayer owner = owner();
		super.die(source);
		if (level == null) return;

		int dropped = 0;
		List<ItemStack> corpse = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			corpse.add(stack.copy());
			inventory.setItem(i, ItemStack.EMPTY);
			dropped += stack.getCount();
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack worn = this.getItemBySlot(slot);
			if (worn.isEmpty()) continue;
			corpse.add(worn.copy());
			this.setItemSlot(slot, ItemStack.EMPTY);
			dropped += worn.getCount();
		}
		int handed = 0;
		if (!corpse.isEmpty()) {
			// Items spawned from a dying entity never reach the world in this build (they are
			// accepted and then absent), so his bag goes to his owner instead of onto the floor.
			// Verified through the owner's inventory, which is the path `give` already proves.
			if (owner != null) {
				for (ItemStack stack : corpse) {
					if (owner.getInventory().add(stack)) handed += stack.getCount();
				}
			}
			if (handed < dropped) {
				Cobbie.LOGGER.warn("Cobbie died carrying {} item(s) and could only hand over {}; the rest"
						+ " could not be placed", dropped, handed);
			}
		}
		if (dropped > 0) {
			Cobbie.LOGGER.info("Cobbie died carrying {} item(s); dropped them at {}", dropped, this.blockPosition());
			if (owner != null) {
				// Say what actually happened: claiming a handover that did not happen is worse than
				// losing the items.
				// No position in this line either: what a player needs to know is whether their
				// things survived, and "at BlockPos{x=2, y=-60, z=2}" is not that.
				String where = "I died — ";
				String what = handed == dropped
						? "everything I was carrying is in your inventory."
						: handed > 0
								? "I passed you " + handed + " of my " + dropped + " items; the rest did not fit."
								: "I could not pass you anything — your inventory had no room.";
				owner.sendSystemMessage(Component.literal("§e[Cobbie] §f" + where + what));
			}
		}
	}

	/** One stack onto the ground, explicitly: the dying-entity helpers drop nothing useful here. */

	/** The one and only following mechanism. */
	private void followOwnerTick() {
		if (!following || ownerUuid == null) return;
		// The retreat drives his feet itself; two loops aiming at the owner is two repath budgets for one walk.
		if (isRetreating()) return;
		// While a task is walking him somewhere, following must not repath him back mid-job. Any
		// other kind of plan (talking, waving, a queued single step) must not freeze his feet.
		if (navigatingForAction) return;
		ServerPlayer owner = owner();
		// `getPlayerByUUID` can answer with a player who is in another dimension, and distance to them
		// is then meaningless — that is the portal case, not a reason to stand still.
		if (owner == null || owner.level() != this.level()) {
			followThroughPortal();
			return;
		}

		// Out of sight is a state with an end, not a tick that returns early: one line the first time, a deadline,
		// then he stops trying instead of asking the pathfinder for a chunk that is not there. See FollowSight.
		switch (followSight.onTick(level().isLoaded(owner.blockPosition()), this.tickCount)) {
			case SPEAK -> {
				sayToOwner(FollowSight.UNSEEN_LINE);
				return;
			}
			case WAITING, GAVE_UP -> {
				return;
			}
			case VISIBLE -> { }
		}

		double follow = Math.max(1.0, CobbieConfig.getFollowDistance());
		double distSq = this.distanceToSqr(owner);
		if (distSq <= follow * follow) {
			// Standing next to you he stops walking — but never while a task is using his feet.
			if (!isBusy() && this.tickCount % 20 == 0) getNavigation().stop();
			recallSamplePos = null;
			return;
		}

		if (recallIsWarranted(owner, distSq)) return;

		if (this.tickCount - followRepathTick < FOLLOW_REPATH_INTERVAL) return;
		followRepathTick = this.tickCount;

		// You are above him: the pathfinder cannot plan up a ladder, so aim at the ladder itself and let
		// the climb assist do the rest. Without this the follow loop keeps asking for the top of a wall
		// and gets nothing, which is exactly "he stands at the bottom while you are up there".
		if (owner.getY() > this.getY() + 1.0) {
			BlockPos climb = climbableNextTo();
			if (climb == null) climb = climbableToward(owner.blockPosition());
			if (climb != null) {
				followMisses = getNavigation().moveTo(climb.getX() + 0.5, climb.getY(), climb.getZ() + 0.5, 1.0)
						? 0 : 1;
				return;
			}
		}
		if (distSq <= FOLLOW_DIRECT_RANGE * FOLLOW_DIRECT_RANGE) {
			followMisses = getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 0.9) ? 0 : 1;
			return;
		}
		// Too far for one plan: walk to the next point along the line instead, and re-aim as it
		// moves. Re-path failure at this distance is normal and not a reason to give up.
		BlockPos step = stepToward(owner, followStepLength());
		if (step == null) return;
		if (getNavigation().moveTo(step.getX(), step.getY(), step.getZ(), 1.0)) {
			followMisses = 0;
		} else {
			followMisses = Math.min(4, followMisses + 1);
		}
	}

	/**
	 * A shorter aim after each refusal. A steep hill between you and him can make the 24-block
	 * waypoint unreachable while the six-block one is fine, so the reach collapses toward him
	 * instead of hammering the same impossible plan.
	 */
	private double followStepLength() {
		return Math.max(6.0, FOLLOW_STEP / (1.0 + followMisses * 0.5));
	}

	/** The out-of-sight state, so a caller that has to report it can. */
	public FollowSight.Step followSight() {
		return followSight.last();
	}

	/**
	 * You are in another dimension: find a portal and walk into it.
	 *
	 * <p>He is not teleported — the game's own portal timing carries him, the arrival is the game's, and
	 * the nether's hazards apply to him because he is really there. If there is no portal within reach he
	 * says so once rather than pacing at nothing in silence.
	 */
	private void followThroughPortal() {
		ServerPlayer away = ownerAnywhere();
		if (away == null) return;                                    // offline: wait where he is
		if (away.level() == this.level()) return;                    // same world after all
		if (this.isOnPortalCooldown() || this.isPassenger()) return;

		BlockPos portal = nearestPortal();
		if (portal == null) {
			if (!portalNoticeShown) {
				portalNoticeShown = true;
				away.sendSystemMessage(Component.literal("§e[Cobbie] §fI cannot find a portal to follow"
						+ " you through — come back, or leave one open where I can reach it."));
			}
			return;
		}
		portalNoticeShown = false;
		// A portal block is not a node the pathfinder will plan to, so aim at the floor in front of it
		// and then step in — the game takes over once he is inside the frame.
		if (this.blockPosition().distSqr(portal) <= 6) {
			Vec3 motion = this.getDeltaMovement();
			double dx = portal.getX() + 0.5 - this.getX();
			double dz = portal.getZ() + 0.5 - this.getZ();
			double length = Math.sqrt(dx * dx + dz * dz);
			if (length > 0.05) {
				this.setDeltaMovement(dx / length * 0.14, motion.y, dz / length * 0.14);
			}
			return;
		}
		if (this.tickCount - followRepathTick < FOLLOW_REPATH_INTERVAL) return;
		followRepathTick = this.tickCount;
		int towardX = Integer.signum(this.blockPosition().getX() - portal.getX());
		int towardZ = Integer.signum(this.blockPosition().getZ() - portal.getZ());
		BlockPos approach = portal.offset(towardX, 0, towardZ);
		if (!level().getBlockState(approach).isAir()) approach = portal;
		getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 1.0);
	}

	/** The nearest portal block within a short walk, or null. */
	private BlockPos nearestPortal() {
		BlockPos base = this.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos probe : BlockPos.betweenClosed(base.offset(-24, -8, -24), base.offset(24, 8, 24))) {
			if (!level().isLoaded(probe)) continue;
			if (!level().getBlockState(probe).is(Blocks.NETHER_PORTAL)) continue;
			double d = this.distanceToSqr(probe.getX() + 0.5, probe.getY(), probe.getZ() + 0.5);
			if (d < bestDist) {
				bestDist = d;
				best = probe.immutable();
			}
		}
		return best;
	}

	/**
	 * A point {@code distance} blocks from him along the straight line toward the owner, snapped to
	 * whatever he can stand on or swim in there.
	 */
	private BlockPos stepToward(ServerPlayer owner, double distance) {
		double dx = owner.getX() - this.getX();
		double dz = owner.getZ() - this.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.01) return owner.blockPosition();
		double take = Math.min(length - 1.0, distance);
		if (take <= 0) return owner.blockPosition();

		int x = Mth.floor(this.getX() + dx / length * take);
		int z = Mth.floor(this.getZ() + dz / length * take);
		int surface = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos at = new BlockPos(x, surface, z);
		// Heightmap counts water as blocking, so over a lake it points at the air above the surface.
		// Aim at the water itself: he swims there, he cannot walk there.
		if (level().getFluidState(at.below()).is(FluidTags.WATER)) at = at.below();
		return level().isLoaded(at) ? at : null;
	}

	/**
	 * The last resort, and only ever that: it is off by default, needs an explicit
	 * {@code recall_distance}, and he says out loud that he did it. A companion that silently
	 * teleports is a companion you cannot trust to be anywhere.
	 *
	 * @return true when he was recalled, so the caller stops pathing this tick
	 */
	private boolean recallIsWarranted(ServerPlayer owner, double distSq) {
		int limit = CobbieConfig.getRecallDistance();
		if (limit <= 0 || distSq <= (double) limit * limit) {
			recallSamplePos = null;
			return false;
		}
		// He has to be far AND making no progress: distance alone is not a failure, a walk takes
		// time. Sample his position every few seconds and only act on a window with no movement.
		if (recallSamplePos == null) {
			recallSamplePos = this.blockPosition();
			recallSampleTick = this.tickCount;
			return false;
		}
		if (this.tickCount - recallSampleTick < RECALL_PROGRESS_TICKS) return false;

		boolean moved = Math.sqrt(recallSamplePos.distSqr(this.blockPosition())) >= RECALL_PROGRESS_BLOCKS;
		recallSamplePos = this.blockPosition();
		recallSampleTick = this.tickCount;
		if (moved) return false;
		if (!getNavigation().isDone()) return false; // still has a path to follow: not stuck yet

		BlockPos beside = standingSpotNear(owner.blockPosition());
		if (beside == null) beside = owner.blockPosition();
		this.teleportTo((ServerLevel) level(), beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5,
				Set.of(), this.getYRot(), this.getXRot(), true);
		getNavigation().stop();
		recallSamplePos = null;
		followRepathTick = this.tickCount;
		String line = "I could not find a way to you from " + (int) Math.sqrt(distSq)
				+ " blocks out, so I came the quick way. Tell me if you would rather I kept walking.";
		owner.sendSystemMessage(Component.literal("§e[Cobbie] §f" + line));
		noteSaid(line);
		Cobbie.LOGGER.info("Cobbie recalled to his owner from {} blocks away", (int) Math.sqrt(distSq));
		return true;
	}

	/**
	 * A wander with a real radius and a way home. The target is picked between about half and all of
	 * {@code exploration_radius} from the owner, on ground he can stand on, and the plan carries a
	 * second step back toward where the owner is — so "explore" never ends with a companion who
	 * walked off and stayed gone.
	 */
	private void exploreSomewhereNearby() {
		int radius = Math.max(8, CobbieConfig.getExplorationRadius());
		ServerPlayer owner = owner();
		BlockPos centre = owner != null ? owner.blockPosition() : this.blockPosition();

		BlockPos target = null;
		for (int attempt = 0; attempt < 12 && target == null; attempt++) {
			double angle = this.random.nextDouble() * Math.PI * 2;
			double distance = radius * (0.55 + this.random.nextDouble() * 0.45);
			int x = centre.getX() + (int) Math.round(Math.cos(angle) * distance);
			int z = centre.getZ() + (int) Math.round(Math.sin(angle) * distance);
			int surface = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos stand = new BlockPos(x, surface, z);
			if (!level().isLoaded(stand)) continue;
			if (level().getFluidState(stand).is(FluidTags.WATER)) continue; // not the sea floor
			if (level().getBlockState(stand.below()).isAir()) continue;      // needs ground
			if (stand.distSqr(this.blockPosition()) < 64) continue;          // actually go somewhere
			target = stand;
		}
		if (target == null) return;

		List<ActionIntent> steps = new ArrayList<>();
		steps.add(exploreWalk(target));
		if (centre.distSqr(target) > 16 && level().isLoaded(centre)) steps.add(exploreWalk(centre));
		offerPlan("looking around a bit", steps);
	}

	private static ActionIntent exploreWalk(BlockPos pos) {
		return new ActionIntent(ActionVocabulary.WALK_TO, "", pos.getX(), pos.getY(), pos.getZ(), EXPLORE_WALK);
	}

	// ==================================================================
	// Swimming
	// ==================================================================

	/**
	 * Vanilla gives a land mob water physics and no idea what to do with them: it sinks, drifts and
	 * drowns its speed in drag. This adds the two things a swimmer actually needs — thrust toward
	 * where he is going and buoyancy at the surface — on top of the navigator's own path, which is
	 * allowed to plan through water now that {@code canFloat} is on.
	 */
	private void swimTick() {
		if (!this.isInWater()) return;
		Vec3 motion = this.getDeltaMovement();
		double vx = motion.x;
		double vy = motion.y;
		double vz = motion.z;

		BlockPos goal = swimGoal();
		double horizontal = 0;
		if (goal != null) {
			double dx = goal.getX() + 0.5 - this.getX();
			double dz = goal.getZ() + 0.5 - this.getZ();
			horizontal = Math.sqrt(dx * dx + dz * dz);
			if (horizontal > 0.35) {
				vx += dx / horizontal * SWIM_PUSH;
				vz += dz / horizontal * SWIM_PUSH;
			}
		}

		double speed = Math.sqrt(vx * vx + vz * vz);
		if (speed > SWIM_MAX_HORIZONTAL) {
			vx *= SWIM_MAX_HORIZONTAL / speed;
			vz *= SWIM_MAX_HORIZONTAL / speed;
		}

		double surface = waterSurfaceY();
		if (this.getY() + 1.2 < surface) {
			vy = Math.min(vy + SWIM_RISE, SWIM_MAX_RISE);       // under the surface: come up
		} else if (this.getY() + 1.0 > surface) {
			vy = Math.max(vy - 0.04, -0.12);                    // riding too high: settle
		}

		// Climbing out. Floating at the waterline puts his feet a fraction below the bank, so he
		// presses against the grass and bobs there forever — the shoreline oscillation this test
		// caught. A hop and a shove toward the bank is what a player does, and it works because the
		// bank's own column tells us exactly where to aim.
		BlockPos bank = bankAhead(goal);
		if (bank != null && this.tickCount % 6 == 0) {
			vy = Math.max(vy, 0.36);
			double ex = bank.getX() + 0.5 - this.getX();
			double ez = bank.getZ() + 0.5 - this.getZ();
			double length = Math.sqrt(ex * ex + ez * ez);
			if (length > 0.05) {
				vx += ex / length * 0.12;
				vz += ez / length * 0.12;
			}
		}

		this.setDeltaMovement(vx, vy, vz);
	}

	/**
	 * A standable, dry column next to him that is further toward where he is going than he is. The
	 * direction test is what stops him hopping back out the side he came from.
	 */
	private BlockPos bankAhead(BlockPos goal) {
		BlockPos feet = this.blockPosition();
		BlockPos[] around = {
				feet.north(), feet.south(), feet.east(), feet.west(),
				feet.north().east(), feet.north().west(), feet.south().east(), feet.south().west()
		};
		double hereToGoal = goal == null ? 0
				: Math.sqrt(goal.distToCenterSqr(this.getX(), this.getY(), this.getZ()));
		for (BlockPos column : around) {
			if (!level().isLoaded(column)) continue;
			BlockState ground = level().getBlockState(column);
			if (ground.isAir() || !level().getFluidState(column).isEmpty()) continue; // needs dry ground
			BlockPos standAt = column.above();
			if (!level().getBlockState(standAt).isAir()) continue;
			if (!level().getBlockState(standAt.above()).isAir()) continue;
			if (goal != null) {
				double thereToGoal = Math.sqrt(goal.distToCenterSqr(
						standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5));
				if (thereToGoal >= hereToGoal - 0.5) continue; // the wrong bank
			}
			return standAt;
		}
		return null;
	}

	/**
	 * Ladders and vines.
	 *
	 * <p>The pathfinder has no "climb" node, so it will not plan up a ladder, and a mob that simply
	 * walks into one stands at its foot forever. A player climbs by holding forward against it; this
	 * does the same — an upward push while he is on a climbable and his goal is above him, and a
	 * downward one when it is below.
	 */
	private void climbTick() {
		BlockPos goal = climbGoal();
		if (goal == null) return;
		if (!this.onClimbable()) {
			// Standing beside a ladder with the goal above him is not "not climbing yet" — it is the
			// state he would sit in forever. Step into the rungs and let the assist take over.
			BlockPos beside = climbableNextTo();
			if (beside == null || goal.getY() <= this.getY() + 0.9) return;
			double dx = beside.getX() + 0.5 - this.getX();
			double dz = beside.getZ() + 0.5 - this.getZ();
			this.setDeltaMovement(dx * 0.2, 0.12, dz * 0.2);
			return;
		}
		double up = goal.getY() - this.getY();
		Vec3 motion = this.getDeltaMovement();
		double sideways = 0.4; // hold him to the ladder rather than letting him drift off it
		if (up > 0.6) {
			this.setDeltaMovement(motion.x * sideways, CLIMB_UP, motion.z * sideways);
		} else if (up < -0.6) {
			this.setDeltaMovement(motion.x * sideways, CLIMB_DOWN, motion.z * sideways);
		} else {
			this.setDeltaMovement(motion.x * sideways, Math.max(0.0, motion.y * 0.5), motion.z * sideways);
		}
	}

	/** Where a climb should aim: the walk in flight, the navigator's target, or you. */
	private BlockPos climbGoal() {
		if (navigatingForAction && activeTarget != null) return activeTarget;
		BlockPos nav = getNavigation().isDone() ? null : getNavigation().getTargetPos();
		if (nav != null) return nav;
		ServerPlayer owner = owner();
		return following && owner != null ? owner.blockPosition() : null;
	}

	/** The climbable block he is in or pressed against, or null. */
	private BlockPos climbableNextTo() {
		BlockPos feet = this.blockPosition();
		for (BlockPos probe : new BlockPos[]{feet, feet.north(), feet.south(), feet.east(), feet.west()}) {
			if (!level().isLoaded(probe)) continue;
			if (level().getBlockState(probe).is(net.minecraft.tags.BlockTags.CLIMBABLE)) return probe.immutable();
		}
		return null;
	}

	/**
	 * The nearest ladder or vine that goes up toward {@code target}: the foot of it, so the navigator has
	 * something walkable to aim at before the climb assist takes over.
	 */
	private BlockPos climbableToward(BlockPos target) {
		BlockPos base = this.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos probe : BlockPos.betweenClosed(base.offset(-4, -1, -4), base.offset(4, 5, 4))) {
			if (!level().isLoaded(probe)) continue;
			if (!level().getBlockState(probe).is(net.minecraft.tags.BlockTags.CLIMBABLE)) continue;
			// Only a ladder that actually leads toward where you are.
			if (probe.getY() > target.getY() + 1) continue;
			double d = this.distanceToSqr(probe.getX() + 0.5, this.getY(), probe.getZ() + 0.5);
			if (d < bestDist) {
				bestDist = d;
				best = probe.immutable();
			}
		}
		return best;
	}

	/** Where the swimming should head: the navigator's target, the walk in flight, or you. */
	private BlockPos swimGoal() {
		if (!getNavigation().isDone()) {
			BlockPos nav = getNavigation().getTargetPos();
			if (nav != null) return nav;
		}
		if (navigatingForAction && activeTarget != null) return activeTarget;
		ServerPlayer owner = owner();
		if (following && owner != null) return owner.blockPosition();
		return null;
	}

	/** Y of the top of the water column he is in, or his own feet when he is somehow dry. */
	private double waterSurfaceY() {
		BlockPos base = this.blockPosition();
		for (int dy = 3; dy >= -4; dy--) {
			BlockPos probe = base.above(dy);
			if (level().getFluidState(probe).is(FluidTags.WATER)) return probe.getY() + 0.9;
		}
		return base.getY() + 1.0;
	}

	// ==================================================================
	// TaskRunner.Executor
	// ==================================================================

	@Override
	public ActionOutcome begin(ActionIntent intent) {
		if (intent == null) return ActionOutcome.fail("wait", "no action given");
		String verb = intent.action() == null ? "" : intent.action();

		// Execution-time permission check. The parser already did this once; repeating it here is
		// the point — a plan queued before the owner flipped a switch off must not outrank that.
		if (!ActionVocabulary.isAllowed(verb)) {
			return ActionOutcome.fail(verb, "that is not something I can do");
		}
		if (!CobbieConfig.permits(verb)) {
			return ActionOutcome.fail(verb, "my owner has not allowed that");
		}
		if (active != null) {
			return ActionOutcome.fail(verb, "still busy with " + active.action());
		}
		// Anything else means he is done riding: a companion who cannot get out of a boat is stuck in
		// the water for good.
		if (this.isPassenger() && !ActionVocabulary.BOARD.equals(verb)) {
			this.stopRiding();
		}
		if (!ActionVocabulary.ANSWER.equals(verb) && !ActionVocabulary.WAIT.equals(verb)) {
			com.rishi.cobbie.presence.ActionTiming.firstStep(System.currentTimeMillis());
		}

		try {
			return dispatch(verb, intent);
		} catch (RuntimeException e) {
			// A handler that throws must not leave a step in flight. `active` staying set is how a companion
			// becomes unusable: every later instruction answers "still busy with <verb>" for ever, and the
			// only cure is a reload. The class name is said instead of the message because an exception's
			// message is not a sentence he can say.
			interrupt();
			Cobbie.LOGGER.warn("Cobbie {} threw while starting: {}", verb, e.toString());
			return ActionOutcome.fail(verb, "something went wrong on my side (" + e.getClass().getSimpleName() + ")");
		}
	}

	/** The verb table, in one place. Any handler in here may throw; the caller turns that into a failure. */
	private ActionOutcome dispatch(String verb, ActionIntent intent) {
		return switch (verb) {
			case ActionVocabulary.ANSWER, ActionVocabulary.WAIT ->
					ActionOutcome.ok(verb, "nothing to do", ActionEvidence.ownAct("nothing asked of the world"));
			case ActionVocabulary.CHAT -> speak(intent.target());
			case ActionVocabulary.WAVE -> {
				wave();
				yield ActionOutcome.ok(verb, "waved hello", ActionEvidence.ownAct("i waved, and nothing in the world had to change"));
			}
			case ActionVocabulary.FOLLOW -> {
				startFollowing();
				yield ActionOutcome.ok(verb, "following you again", ActionEvidence.ownAct("i set off after you"));
			}
			case ActionVocabulary.STAY -> {
				stopFollowing();
				yield ActionOutcome.ok(verb, "staying put", ActionEvidence.ownAct("i stopped where i was"));
			}
			case ActionVocabulary.EXPLORE -> {
				exploreSomewhereNearby();
				yield ActionOutcome.ok(verb, "wandering a little", ActionEvidence.ownAct("i picked somewhere to wander"));
			}
			case ActionVocabulary.WALK_TO -> startWalk(intent);
			case ActionVocabulary.LIGHT -> startPlace(new ActionIntent(ActionVocabulary.PLACE,
					"minecraft:torch", intent.x(), intent.y(), intent.z(), ""));
			case ActionVocabulary.GIVE -> giveToOwner(intent);
			case ActionVocabulary.DROP -> dropFromBag(intent);
			case ActionVocabulary.EQUIP -> equipFromBag(intent);
			case ActionVocabulary.CRAFT -> startCraft(intent);
			case ActionVocabulary.SMELT -> startSmelt(intent);
			case ActionVocabulary.TAKE -> startTake(intent);
			case ActionVocabulary.PUT -> startPut(intent);
			case ActionVocabulary.USE -> startUse(intent);
			case ActionVocabulary.REPAIR -> repairFromBag(intent);
			case ActionVocabulary.DYE -> dyeFromBag(intent);
			case ActionVocabulary.ENCHANT -> enchantFromBag(intent);
			case ActionVocabulary.ANVIL -> anvilFromBag(intent);
			case ActionVocabulary.BOARD -> boardCraft(intent);
			case ActionVocabulary.BREW -> startBrew(intent);
			case ActionVocabulary.SMITH -> smithFromBag(intent);
			case ActionVocabulary.GRIND -> grindFromBag(intent);
			case ActionVocabulary.WEAVE -> weaveFromBag(intent);
			case ActionVocabulary.MAP -> mapFromBag(intent);
			case ActionVocabulary.FARM -> farmAround(intent);
			case ActionVocabulary.BREED -> breedNearby(intent);
			case ActionVocabulary.TRADE -> tradeWithVillager(intent);
			case ActionVocabulary.REMEMBER -> rememberWhatOwnerSaid(intent);
			case ActionVocabulary.MINE -> startMine(intent);
			case ActionVocabulary.PLACE -> startPlace(intent);
			case ActionVocabulary.WRITE -> startWrite(intent);
			case ActionVocabulary.COLLECT -> startCollect(intent);
			case ActionVocabulary.ATTACK -> startAttack(intent);
			default -> ActionOutcome.fail(verb, "unhandled verb");
		};
	}

	@Override
	public ActionOutcome poll() {
		if (pendingOutcome != null) {
			ActionOutcome done = pendingOutcome;
			pendingOutcome = null;
			return done;
		}
		if (active == null) return null;
		return driveActiveStep();
	}

	@Override
	public void interrupt() {
		narrator.clear();
		hunted = null;
		huntedName = null;
		active = null;
		brew = null;
		activeTarget = null;
		workTarget = null;
		navigatingForAction = false;
		pendingOutcome = null;
		if (level() != null && !level().isClientSide()) {
			getNavigation().stop();
			this.setTarget(null);
		}
	}

	/**
	 * Advance the in-flight step. Non-null return means "this step has settled".
	 *
	 * <p>A throw from any handler ends the step that threw and nothing else: the in-flight state is cleared
	 * and the failure is reported in words. Without this the step stays active for ever and he answers
	 * "still busy" to every instruction afterwards.
	 */
	private ActionOutcome driveActiveStep() {
		if (active == null) return null;
		String verb = active.action();
		try {
			return driveStep(verb);
		} catch (RuntimeException e) {
			interrupt();
			Cobbie.LOGGER.warn("Cobbie {} threw while running: {}", verb, e.toString());
			return ActionOutcome.fail(verb, "something went wrong on my side (" + e.getClass().getSimpleName() + ")");
		}
	}

	private ActionOutcome driveStep(String verb) {

		if (this.tickCount > activeDeadlineTick) {
			interrupt();
			return ActionOutcome.fail(verb, "gave up, it was taking too long");
		}

		// Phase 1: walk into interaction range, then act.
		if (navigatingForAction) {
			if (activeTarget == null) {
				interrupt();
				return ActionOutcome.fail(verb, "lost track of the target");
			}
			if (this.blockPosition().distSqr(activeTarget) <= arriveRadiusSq) {
				navigatingForAction = false;
				// The walk took time and the world did not wait for it: the block he was sent to can be
				// broken, the chest emptied, the spot filled by the time he gets there. Asked here, at the
				// hand-over, a changed world is reported as what it is — not as a failure of the action that
				// was about to touch it, and never as its success.
				//
				// Only a step he walked somewhere *else* for: on a walk the arrival is the proof, and asking
				// whether he can stand where he is standing answers nothing.
				StepCheck arrived = workTarget != null && !workTarget.equals(activeTarget)
						? checkStep(active) : StepCheck.ready();
				if (arrived.isSatisfied() || arrived.isBroken()) {
					interrupt();
					return ActionOutcome.fail(verb, arrived.changed());
				}
				return performNow();
			}
			if (getNavigation().isDone()) {
				// The navigator has nothing to walk. That is not proof the target is unreachable: on
				// the tick a walk starts it has not computed anything yet, and a path can be dropped
				// mid-walk. Give it time, then repair the path a bounded number of times, and only
				// call the target unreachable when it keeps coming back empty.
				if (this.tickCount - walkStartTick <= WALK_GRACE_TICKS) {
					return null;
				}
				if (walkRepaths < MAX_WALK_REPATHS) {
					walkRepaths++;
					walkStartTick = this.tickCount;
					getNavigation().moveTo(activeTarget.getX(), activeTarget.getY(), activeTarget.getZ(), 0.9);
					return null;
				}
				// A target above a ladder is not unreachable — the pathfinder simply cannot plan the
				// climb. If he is beside a climbable and the goal is above him, let the climb assist
				// work and try the path again once he is higher.
				//
				// Counted, because this used to clear the repath budget and hand the loop a fresh three
				// tries every time it came back empty: the only thing between it and forever was the
				// step deadline. A bound that resets is not a bound.
				if (climbableNextTo() != null && activeTarget.getY() > this.blockPosition().getY() + 0.9
						&& climbAssists < MAX_CLIMB_ASSISTS) {
					climbAssists++;
					walkStartTick = this.tickCount;
					walkRepaths = 0;
					return null;
				}
				// Read the target before interrupt(): interrupt() clears it, and the failure line then
				// said "could not get close enough to ?" — a sentence that names nothing.
				String unreachable = describe(activeTarget) + " from " + describe(this.blockPosition())
						+ (climbAssists >= MAX_CLIMB_ASSISTS ? " (and it is too high to climb to)" : "")
						+ dropNote(activeTarget);
				interrupt();
				return ActionOutcome.fail(verb, "could not get close enough to " + unreachable);
			}
			return null; // still walking
		}

		// Phase 2: multi-tick verbs.
		if (ActionVocabulary.ATTACK.equals(verb)) return attackTick();
		if (ActionVocabulary.CRAFT.equals(verb) || ActionVocabulary.SMELT.equals(verb)) return workTick();
		if (ActionVocabulary.BREW.equals(verb)) return brewTick();

		// Loud, for the same reason performNow's default is: something is in flight and nobody wrote down what
		// finishing it looks like. Answering "done" here is the tail of the same lie as answering "arrived" —
		// a step that has no way to be finished cannot be finished.
		interrupt();
		return ActionOutcome.fail(verb, "i have no way to finish " + verb + " a bit at a time");
	}

	/** Reached the target position: do the thing we walked there for. */
	private ActionOutcome performNow() {
		String verb = active.action();
		BlockPos work = workTarget != null ? workTarget : activeTarget;
		return switch (verb) {
			case ActionVocabulary.WALK_TO -> {
				clearActive();
				double stood = Math.sqrt(this.blockPosition().distSqr(work));
				yield ActionOutcome.ok(verb, "arrived at " + describe(work),
						ActionEvidence.world("i am standing " + oneDecimal(stood) + " block(s) from "
								+ describe(work)));
			}
			case ActionVocabulary.MINE -> {
				ActionOutcome outcome = mineBlock(work);
				clearActive();
				yield outcome;
			}
			case ActionVocabulary.PLACE -> {
				ActionOutcome outcome = placeBlock(active.target(), work);
				clearActive();
				yield outcome;
			}
			case ActionVocabulary.COLLECT -> {
				ActionOutcome outcome = collectAt(work);
				clearActive();
				yield outcome;
			}
			case ActionVocabulary.TAKE -> {
				ActionOutcome outcome = takeFrom(work, active);
				clearActive();
				yield outcome;
			}
			case ActionVocabulary.PUT -> {
				ActionOutcome outcome = putInto(work, active);
				clearActive();
				yield outcome;
			}
			case ActionVocabulary.USE -> {
				ActionOutcome outcome = useAt(work, true, active);
				clearActive();
				yield outcome;
			}
			// The write itself, not the arrival. Until 0.79.0 there was no case here, so a "write" to a
			// sign further away than his arm walked there and then fell through the default, which
			// answered "arrived" as a success — a plan completed over a sign with nothing written on it.
			// Arriving is where a verb starts, never what it claims to have done.
			case ActionVocabulary.WRITE -> {
				ActionOutcome outcome = writeAt(work, active.target());
				clearActive();
				yield outcome;
			}
			// Loud, not quiet. A verb that walks to its work and has no case here is a verb whose work
			// nobody wrote; answering "arrived" turns that gap into a success, which is the one lie this
			// file exists to prevent.
			default -> {
				clearActive();
				yield ActionOutcome.fail(verb, "i got there but do not know how to " + verb);
			}
		};
	}

	private void clearActive() {
		active = null;
		activeTarget = null;
		workTarget = null;
		navigatingForAction = false;
	}

	/**
	 * How far through the weapon's cooldown his next swing is, on the game's own arithmetic: the ticks
	 * since the last swing over {@link #attackDelayTicks()}, the way a player's attack strength fills.
	 *
	 * <p>1.0 is a full-strength hit, and that is the number worth waiting for: the game scales a
	 * player's damage by {@code 0.2 + strength² × 0.8}, so a swing half way through the cooldown
	 * costs 40% of what it should and looks exactly like a full one. He has no ticker of his own
	 * because a mob has none — the game only fills one for players — so this is that same fill,
	 * computed from the tick he last swung.
	 */
	private float attackStrength() {
		return Math.min(1.0f, (this.tickCount - lastSwingTick) / attackDelayTicks());
	}

	/** The game's own delay for the item he is holding: 20 ticks over the attack speed it gives him. */
	private float attackDelayTicks() {
		AttributeInstance speed = this.getAttribute(Attributes.ATTACK_SPEED);
		double perSecond = speed == null ? 4.0 : speed.getValue();
		return (float) (20.0 / Math.max(0.5, perSecond));
	}

	/**
	 * One swing, if the game would let a player make one now: in reach for the item in his hand, able
	 * to see the target, and past the weapon's cooldown. The melee goal and the attack verb both come
	 * through here, so there is one answer to "may he swing" and it is the weapon's.
	 *
	 * @return whether a hit landed — false when the weapon is still coming back, the target is out of
	 *         reach or a wall is in the way, which are all "not yet", never "missed"
	 */
	boolean trySwingAt(LivingEntity target) {
		if (target == null || !isLegalTarget(target)) return false;
		// The cheap answer first: a weapon is only worth reaching for when the swing is otherwise
		// ready, so the bag is read once per cooldown instead of once per tick.
		if (attackStrength() < 1.0f) return false;
		raiseBestWeapon();
		if (attackStrength() < 1.0f) return false;   // a weapon he just picked up needs its own cooldown
		if (!this.hasLineOfSight(target)) return false;
		if (!this.getAttackRangeWith(this.getMainHandItem())
				.isInRange(this, target.getBoundingBox(), 0.0)) return false;
		if (!(level() instanceof ServerLevel serverLevel)) return false;
		lastSwingTick = this.tickCount;
		this.swingForAttack(InteractionHand.MAIN_HAND);
		boolean landed = this.doHurtTarget(serverLevel, target);
		// A landed hit costs what the game charges one in Player.attack; a swing that lands nothing
		// costs nothing. One line, so the combat slice keeps the rest of this method.
		if (landed) hunger.hit();
		return landed;
	}

	/**
	 * What the game says this stack is worth as a weapon in the main hand: the attack-damage modifiers
	 * it carries for that slot, and the attack speed it brings.
	 *
	 * <p>Read off the item's own component data rather than a table of ours, so an item added in a
	 * later version is ranked without anything here being edited — and so a pickaxe is judged by the
	 * number the game gives it, not by being a pickaxe.
	 */
	private static double[] weaponFigure(ItemStack stack) {
		double[] damage = {0.0};
		double[] speed = {0.0};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) return;
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) damage[0] += modifier.amount();
			else if (attribute.value() == Attributes.ATTACK_SPEED.value()) speed[0] += modifier.amount();
		});
		return new double[] {damage[0], speed[0]};
	}

	/**
	 * The stated order among weapons the game rates the same on both figures: a sword, then an axe,
	 * then a pickaxe. It is a preference, not a damage figure — the damage is the game's, and this
	 * only breaks a tie it leaves. A sword is the tool for a fight; a pickaxe is what he digs with.
	 */
	private static int weaponRung(ItemStack stack) {
		if (stack.is(ItemTags.SWORDS)) return 0;
		if (stack.is(ItemTags.AXES)) return 1;
		if (stack.is(ItemTags.PICKAXES)) return 2;
		return 3;
	}

	/** Whether {@code challenger} is the better weapon: the game's damage, then its speed, then the rung. */
	private static boolean betterWeapon(ItemStack challenger, ItemStack incumbent) {
		double[] a = weaponFigure(challenger);
		double[] b = weaponFigure(incumbent);
		if (a[0] != b[0]) return a[0] > b[0];
		if (a[1] != b[1]) return a[1] > b[1];
		return weaponRung(challenger) < weaponRung(incumbent);
	}

	/** The bag slot of the best weapon he carries, or -1 when he is carrying none. */
	private int bestWeaponSlot() {
		int best = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || weaponFigure(stack)[0] <= 0.0) continue;
			if (best < 0 || betterWeapon(stack, inventory.getItem(best))) best = i;
		}
		return best;
	}

	/**
	 * Put the best weapon he carries into his hand when it beats what is already there.
	 *
	 * <p>Called before every swing attempt, which is once per weapon cooldown in practice — a scan
	 * every tick would be a scan for nothing. Swapping restarts the cooldown, as it does for a player
	 * who changes item, so he never lands a full-strength hit with a weapon he just picked up.
	 */
	private void raiseBestWeapon() {
		int slot = bestWeaponSlot();
		if (slot < 0) return;
		ItemStack inHand = this.getMainHandItem();
		if (!inHand.isEmpty() && weaponFigure(inHand)[0] > 0.0 && !betterWeapon(inventory.getItem(slot), inHand)) {
			return;
		}
		ItemStack taken = inventory.getItem(slot).copyWithCount(1);
		ItemStack rest = inventory.getItem(slot).copy();
		rest.shrink(1);
		inventory.setItem(slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
		this.setItemInHand(InteractionHand.MAIN_HAND, taken);
		if (!inHand.isEmpty()) addItem(inHand);
		lastSwingTick = this.tickCount;
	}

	private double reachSquared() {
		double reach = Math.max(1.5, CobbieConfig.getInteractionReach());
		return reach * reach;
	}

	private void beginWalking(ActionIntent intent, BlockPos standAt, BlockPos workAt) {
		active = intent;
		activeTarget = standAt;
		workTarget = workAt;
		navigatingForAction = true;
		// Always a walking tolerance, never interaction reach. The standing spot sits next to the
		// block, so stopping merely "within reach" of the spot can leave the block itself out of
		// reach — which is what made mine fail with "is out of reach" after he had walked there.
		arriveRadiusSq = ARRIVE_RADIUS * ARRIVE_RADIUS;
		activeDeadlineTick = this.tickCount + STEP_DEADLINE_TICKS;
		walkStartTick = this.tickCount;
		walkRepaths = 0;
		climbAssists = 0;
		getNavigation().moveTo(standAt.getX(), standAt.getY(), standAt.getZ(), 0.9);
	}

	// ---- individual verbs ----

	private ActionOutcome speak(String text) {
		if (text == null || text.isBlank()) return ActionOutcome.fail("chat", "nothing to say");
		String line = text.length() > 200 ? text.substring(0, 200) : text;
		speakNearby(line);
		narrator.noteSpokenAt(System.currentTimeMillis());
		return ActionOutcome.ok("chat", "said: " + line, ActionEvidence.ownAct("i said it: " + line));
	}

	/** In-world speech: heard by whoever is standing nearby, never a dimension-wide broadcast. */
	private void speakNearby(String line) {
		if (line == null || line.isBlank() || level() == null) return;
		String text = line.length() > 200 ? line.substring(0, 200) : line;
		noteSaid(text);
		for (Player p : level().getEntitiesOfClass(Player.class,
				new AABB(this.blockPosition()).inflate(32.0))) {
			p.sendSystemMessage(Component.literal("§e[Cobbie] §f" + text));
			// The listeners' own machines speak it; a client with voice off ignores the payload.
			if (p instanceof net.minecraft.server.level.ServerPlayer listener) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(listener,
						new com.rishi.cobbie.net.CobbieSpeechPayload(text));
			}
		}
	}

	/**
	 * Offer a line for the tag-side voice. It is said on a later tick, at most one every
	 * {@code chat_cooldown_ms}, and a newer line replaces an older unsaid one — so narrating a
	 * five-step job that finishes in two seconds produces two lines, not ten.
	 */
	public void narrate(String line) {
		narrator.offer(line);
	}

	/** The brain answered a player itself; keep the next narrated line off its heels. */
	public void noteSpokenNow() {
		narrator.noteSpokenAt(System.currentTimeMillis());
	}

	private void flushNarration() {
		String line = narrator.next(System.currentTimeMillis(), CobbieConfig.getChatCooldownMs());
		if (line != null) speakNearby(line);
	}

	/** With nothing to do he says what he can see. Nothing to do is the precondition, not a mood. */
	private void observeWhenIdle() {
		if (isBusy() || active != null || pendingOutcome != null) return;
		if (com.rishi.cobbie.brain.CobbieBrain.inFlightCount() > 0) return;
		if (this.tickCount - lastObservationTick < OBSERVE_INTERVAL_TICKS) return;
		lastObservationTick = this.tickCount;
		narrator.offer(Sight.observe(WorldPerception.snapshot(this)));
	}

	@Override
	public void onStepStarted(TaskStep step) {
		if (step == null) return;
		narrator.offer(Narrator.stepStart(step.intent()));
	}

	@Override
	public void onStepSettled(TaskStep step, ActionOutcome outcome) {
		if (step == null) return;
		narrator.offer(Narrator.stepResult(step.intent(),
				outcome != null && outcome.success(), outcome == null ? null : outcome.summary()));
	}

	private ActionOutcome startWalk(ActionIntent intent) {
		BlockPos target = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!level().isLoaded(target)) return ActionOutcome.fail("walk-to", "that area is not loaded");
		// Somewhere his body does not fit is not a place to walk to, whatever the caller thought it was:
		// the pathfinder plans to blocks, the body arrives with a height, and the difference is a step
		// that drives him into stone. Refused here so every caller is covered, not just one test path.
		if (!BodySpace.fits(this::solidAt, target.getX(), target.getY(), target.getZ())) {
			return ActionOutcome.fail("walk-to", "i cannot stand there");
		}
		double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
		if (EXPLORE_WALK.equals(intent.data())) {
			navLimit = Math.max(navLimit, CobbieConfig.getExplorationRadius());
		}
		if (this.blockPosition().distSqr(target) > navLimit * navLimit) {
			return ActionOutcome.fail("walk-to", describe(target) + " is further than I will walk ("
					+ (int) navLimit + " blocks)");
		}
		if (this.blockPosition().closerThan(target, 1.5)) {
			return ActionOutcome.ok("walk-to", "already at " + describe(target),
					ActionEvidence.world("i am standing " + oneDecimal(Math.sqrt(this.blockPosition().distSqr(target)))
							+ " block(s) from " + describe(target)));
		}
		beginWalking(intent, target, target);
		return null; // asynchronous: arrival is verified in poll()
	}

	private ActionOutcome startMine(ActionIntent intent) {
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!level().isLoaded(pos)) return ActionOutcome.fail("mine", "that block is not loaded");
		BlockState state = level().getBlockState(pos);
		if (state.isAir()) return ActionOutcome.fail("mine", "there is nothing at " + describe(pos));
		if (PROTECTED.contains(state.getBlock())) {
			return ActionOutcome.fail("mine", "I will not break " + blockId(state));
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("mine", "nowhere to stand near " + describe(pos));
			double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
			if (this.blockPosition().distSqr(stand) > navLimit * navLimit) {
				return ActionOutcome.fail("mine", describe(pos) + " is too far away for me to reach");
			}
			beginWalking(intent, stand, pos);
			return null;
		}
		return mineBlock(pos);
	}

	/**
	 * Break one block with real harvesting semantics: tool-aware loot straight out of the block's
	 * own loot table, protected-block refusal, durability wear, and a full bag that drops items on
	 * the ground instead of deleting them.
	 */
	private ActionOutcome mineBlock(BlockPos pos) {
		if (!(level() instanceof ServerLevel serverLevel)) {
			return ActionOutcome.fail("mine", "no server world");
		}
		BlockState state = serverLevel.getBlockState(pos);
		if (state.isAir()) return ActionOutcome.fail("mine", "nothing there any more");
		if (PROTECTED.contains(state.getBlock())) {
			return ActionOutcome.fail("mine", "I will not break " + blockId(state));
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			return ActionOutcome.fail("mine", describe(pos) + " is out of reach");
		}
		// The block under his feet is the block holding him up. A shaft straight down over lava, or a
		// ledge over a long fall, is a dig that ends with his body at the bottom of it, and the answer is
		// the game's own: the fall distance against the health he has. Refused, with the reason, before
		// the swing — not attempted and regretted.
		if (pos.equals(this.blockPosition().below())) {
			String fatal = fatalDigNote(pos);
			if (fatal != null) return ActionOutcome.fail("mine", fatal);
		}

		String id = blockId(state);
		float hardness = state.getDestroySpeed(serverLevel, pos);
		if (hardness < 0) return ActionOutcome.fail("mine", id + " cannot be broken");

		// What he needs a tool for is the drop, not the break: a log comes off with bare hands in
		// survival and anything comes off in creative, while stone yields nothing without a pickaxe.
		String refusal = breakRefusal(state, id, hardness);
		if (refusal != null) return ActionOutcome.fail("mine", refusal);
		ItemStack tool = equipBestToolFor(state);

		// Loot comes from the game with the tool actually in hand. Never fabricated.
		List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, null, this, tool);

		boolean fits = fitsInInventory(drops);
		if (!serverLevel.destroyBlock(pos, !fits, this, 3)) {
			return ActionOutcome.fail("mine", "could not break " + id);
		}
		wearTool(tool);
		// The square is read back on both paths, not just the one that keeps the drops. Until 0.80.0 the
		// full-bag path took destroyBlock's answer as the fact of the break, so the one branch that cannot
		// check its own loot was also the one that never looked at the world.
		if (!serverLevel.getBlockState(pos).isAir()) {
			return ActionOutcome.fail("mine", id + " is still standing at " + describe(pos));
		}
		// One block broken costs one block's worth of hunger, the number the game charges in
		// Block.playerDestroy — not a guess about hardness.
		hunger.mined();

		if (fits) {
			int stored = 0;
			for (ItemStack drop : drops) {
				if (!drop.isEmpty()) stored += drop.getCount();
			}
			for (ItemStack drop : drops) {
				add(drop.copy(), inventory);
			}
			return ActionOutcome.ok("mine", "mined " + id + " at " + describe(pos)
					+ (stored > 0 ? ", stored " + stored + " item(s)" : " (nothing dropped)"),
					ActionEvidence.world(describe(pos) + " is air now" + (stored > 0
							? ", and " + stored + " item(s) of it went into my bag"
							: ", and it dropped nothing")));
		}

		// Not enough room: drop them where they fall rather than deleting the player's goods.
		return ActionOutcome.ok("mine", "broke " + id + " at " + describe(pos)
				+ ", but my bag is full so the items are on the ground",
				ActionEvidence.world(describe(pos) + " is air now, and my bag was full so its drops are"
						+ " on the ground beside me"));
	}

	/**
	 * "netherite_pickaxe" and "minecraft:netherite_pickaxe" are the same item. Models write both, so
	 * matching on the full registry id alone made him deny carrying things he was holding.
	 */
	private boolean matchesItem(String wanted, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		if (wanted == null || wanted.isBlank()) return true;
		String id = itemId(stack);
		String ask = wanted.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (ask.contains(":")) return ask.equals(id);
		return id.equals(ask) || id.endsWith(":" + ask);
	}

	/**
	 * Wear or hold something from his bag. Armour goes to the slot the game says it belongs in, any
	 * other item to the hand; whatever was there before goes back in the bag rather than vanishing,
	 * and the result is verified by reading the slot back.
	 */
	/** One row of his bag as a description can see it, with the slot it came from. */
	private com.rishi.cobbie.action.ItemQuery.Candidate candidateOf(ItemStack stack) {
		List<String> enchantments = new ArrayList<>();
		var held = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
		if (held != null) {
			held.entrySet().forEach(entry -> enchantments.add(
					entry.getKey().getRegisteredName().replace("minecraft:", "") + " " + entry.getIntValue()));
		}
		String customName = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME) == null
				? "" : stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString();
		return new com.rishi.cobbie.action.ItemQuery.Candidate(itemId(stack), customName, stack.getCount(),
				enchantments, stack.getDamageValue(), stack.getMaxDamage());
	}

	/** A resolved stack, or the reason it could not be resolved — the question when two of them match. */
	private record Pick(ItemStack stack, String refusal) {
		boolean ok() {
			return stack != null && !stack.isEmpty();
		}
	}

	/**
	 * The single place an item-taking verb turns words into a stack. Every verb that accepts an item calls this,
	 * never its own id lookup: a second path beside the first is how "diamond sword" came to mean "the first one"
	 * in one verb and "which one?" in another.
	 *
	 * @param eligible what this verb would accept at all, so "the sharpness book" cannot resolve to a sword
	 */
	private Pick pick(String description, java.util.function.Predicate<ItemStack> eligible) {
		return pick(description, eligible, "I have nothing here that would do");
	}

	/**
	 * @param noneEligible what to say when nothing he carries would suit this verb. Each verb keeps its own
	 *     sentence: an eligibility filter that answers every refusal with one generic line is how a reason turns
	 *     into "I have nothing here that would do", which tells the owner nothing about what was wrong.
	 */
	private Pick pick(String description, java.util.function.Predicate<ItemStack> eligible, String noneEligible) {
		List<ItemStack> stacks = new ArrayList<>();
		List<com.rishi.cobbie.action.ItemQuery.Candidate> candidates = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !eligible.test(stack)) continue;
			stacks.add(stack);
			candidates.add(candidateOf(stack));
		}
		if (candidates.isEmpty()) return new Pick(ItemStack.EMPTY, noneEligible);
		if (description == null || description.isBlank()) return new Pick(stacks.get(0), null);
		var result = com.rishi.cobbie.action.ItemQuery.resolve(description.trim(), candidates);
		if (!result.ok()) {
			boolean carriedAtAll = resolveDescribed(description.trim()).ok();
			return new Pick(ItemStack.EMPTY, carriedAtAll ? noneEligible : result.refusal());
		}
		for (int i = 0; i < candidates.size(); i++) {
			if (candidates.get(i).equals(result.chosen())) return new Pick(stacks.get(i), null);
		}
		return new Pick(ItemStack.EMPTY, "I lost track of the " + description.trim());
	}

	/** Whether the game says every enchantment on this book applies to this item. */
	private boolean bookFits(ItemStack book, ItemStack item) {
		var stored = book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
		if (stored == null || stored.isEmpty()) return false;
		for (var entry : stored.entrySet()) {
			if (!entry.getKey().value().canEnchant(item)) return false;
		}
		return true;
	}

	/** How much a book would add: a new enchantment beats a duplicate, then a higher level wins. */
	private int bookScore(ItemStack book, ItemStack item) {
		var stored = book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
		if (stored == null || stored.isEmpty()) return 0;
		var already = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentsForCrafting(item);
		int score = 0;
		for (var entry : stored.entrySet()) {
			boolean carried = already.getLevel(entry.getKey()) > 0;
			score += entry.getIntValue() * 10 + (carried ? 0 : 1);
		}
		return score;
	}

	/** A book named the way a person names it: what it holds. */
	private String bookLabel(ItemStack book) {
		var stored = book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
		if (stored == null || stored.isEmpty()) return itemId(book);
		StringBuilder sb = new StringBuilder();
		for (var entry : stored.entrySet()) {
			if (sb.length() > 0) sb.append(" and ");
			sb.append(entry.getKey().value().description().getString().toLowerCase(Locale.ROOT))
					.append(' ').append(entry.getIntValue());
		}
		return sb.toString();
	}

	/** Every enchanted book he carries, named, for a refusal that tells him what he does have. */
	private String bookList() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !stack.is(Items.ENCHANTED_BOOK)) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(bookLabel(stack));
		}
		return sb.length() == 0 ? "no enchanted books at all" : sb.toString();
	}

	/** The first stack he carries of this item that now carries an enchantment, or null. */
	private ItemStack carriedWithEnchantments(String registryId) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !itemId(stack).equals(registryId)) continue;
			if (stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS) != null) return stack;
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty() && itemId(hand).equals(registryId)
				&& hand.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS) != null) {
			return hand;
		}
		return null;
	}

	/** Whether the owner is playing in creative — the world's rule, not the companion's autonomy setting. */
	private boolean worldIsCreative() {
		return owner() != null && owner().isCreative();
	}

	/** The slot holding this exact stack, or -1. */
	private int slotOfStack(ItemStack stack) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i) == stack) return i;
		}
		return -1;
	}

	private record BagEntry(com.rishi.cobbie.action.ItemQuery.Candidate candidate, int slot) {}

	/**
	 * Resolve an item the owner described rather than named — "my sharpest book", "the worst pickaxe" — against
	 * his own bag. Ambiguity is a refusal that lists the candidates, never a silent pick.
	 */
	private com.rishi.cobbie.action.ItemQuery.Result resolveDescribed(String description) {
		List<com.rishi.cobbie.action.ItemQuery.Candidate> candidates = new ArrayList<>();
		ItemStack inHand = this.getMainHandItem();
		if (!inHand.isEmpty()) candidates.add(candidateOf(inHand));
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			List<String> enchantments = new ArrayList<>();
			var held = stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
			if (held != null) {
				held.entrySet().forEach(entry -> enchantments.add(
						entry.getKey().getRegisteredName().replace("minecraft:", "") + " " + entry.getIntValue()));
			}
			String customName = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME) == null
					? "" : stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString();
			candidates.add(new com.rishi.cobbie.action.ItemQuery.Candidate(itemId(stack), customName,
					stack.getCount(), enchantments, stack.getDamageValue(), stack.getMaxDamage()));
		}
		return com.rishi.cobbie.action.ItemQuery.resolve(description, candidates);
	}

	/**
	 * Is this stack in one of the two places a thing he made can be: his bag, or the ground at his feet?
	 *
	 * <p>Ten call sites used to answer this by trying the bag and then discarding the answer of
	 * {@code addFreshEntity}, so a world that refused the drop meant the item existed nowhere and the
	 * sentence still said "mended", "brewed", "bought". A stack that is nowhere is not a success with a
	 * footnote; it is the one failure a player notices immediately.
	 */
	private Stored store(ItemStack made) {
		if (made == null || made.isEmpty()) return Stored.NOWHERE;
		if (add(made, inventory)) return Stored.BAG;
		ItemEntity pile = new ItemEntity(level(), getX(), getY() + 0.4, getZ(), made);
		pile.setDefaultPickUpDelay();
		if (level().addFreshEntity(pile)) return Stored.GROUND;
		return Stored.NOWHERE;
	}

	/**
	 * Where a thing he made ended up. Three answers rather than a boolean, because the success message and
	 * the evidence sentence are both written from it: "mended two into one, and the mended one is on the
	 * ground at my feet" is a report about the world, and a boolean could only ever say "true".
	 */
	private enum Stored {
		BAG("in my bag"),
		GROUND("on the ground at my feet"),
		NOWHERE("nowhere");

		private final String where;

		Stored(String where) {
			this.where = where;
		}

		String describe() {
			return where;
		}
	}

	/** How many of this item another player is carrying, read off their inventory rather than a call's answer. */
	private static int carrying(Player who, net.minecraft.world.item.Item what) {
		int total = 0;
		for (int i = 0; i < who.getInventory().getContainerSize(); i++) {
			ItemStack stack = who.getInventory().getItem(i);
			if (!stack.isEmpty() && stack.is(what)) total += stack.getCount();
		}
		return total;
	}

	/** The honest ending when something he made exists in no place at all. */
	private ActionOutcome lostIt(String verb, ItemStack made) {
		return ActionOutcome.fail(verb,
				"I made " + itemId(made) + " and then lost it — the world would not take it");
	}

	/** The slot holding the item a description resolved to, or -1 when the bag moved under us. */
	private int slotOf(com.rishi.cobbie.action.ItemQuery.Candidate chosen) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			// The whole identity, not three of its six fields. Matching on id, count and damage alone
			// meant two swords that share those and differ in their enchantments answered to each other's
			// description, so "wield my sharpest sword" could pick up the wrong one.
			if (candidateOf(stack).equals(chosen)) return i;
		}
		return -1;
	}

	private ActionOutcome equipFromBag(ActionIntent intent) {
		String wanted = intent.target() == null ? "" : intent.target().trim();

		// One route for an id and for a description: "iron_axe" and "my worst pickaxe" answer to the same
		// rules, so two different stacks that both match are a question either way. Picking the first of
		// two swords because the words happened to spell an id is exactly the silent guess to avoid.
		ItemStack chosen = ItemStack.EMPTY;
		var resolved = resolveDescribed(wanted);
		if (!resolved.ok()) {
			return ActionOutcome.fail("equip", wanted.isEmpty() ? "I have nothing to put on" : resolved.refusal());
		}
		int chosenSlot = slotOf(resolved.chosen());
		// A description can name the thing already in his hand. It is not in the bag, so a slot search can
		// never find it — and "I lost track of my pickaxe" about the pickaxe he is holding is a lie about
		// his own hand. What is in the hand is a known place; it just is not a bag slot.
		boolean fromHand = chosenSlot < 0;
		if (fromHand) {
			if (!candidateOf(this.getMainHandItem()).equals(resolved.chosen())) {
				return ActionOutcome.fail("equip", "I lost track of the " + wanted);
			}
			chosen = this.getMainHandItem();
		} else {
			chosen = inventory.getItem(chosenSlot);
		}

		EquipmentSlot slot = EquipmentSlot.MAINHAND;
		var equippable = chosen.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable != null && equippable.slot() != null) slot = equippable.slot();

		String id = itemId(chosen);
		ItemStack toWear = chosen.copyWithCount(1);
		ItemStack previous = this.getItemBySlot(slot).copy();

		if (fromHand && slot == EquipmentSlot.MAINHAND) {
			// Already where it needs to be. Moving it to itself would report a change that did not happen.
			return ActionOutcome.ok("equip", "i am already holding the " + id,
					ActionEvidence.world("my main hand is holding " + id));
		}
		if (fromHand) {
			this.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		} else if (!consumeItem(id, 1)) {
			return ActionOutcome.fail("equip", "I could not take the " + id + " out of my bag");
		}
		this.setItemSlot(slot, toWear);
		if (!previous.isEmpty()) addItem(previous);

		ItemStack now = this.getItemBySlot(slot);
		if (now.isEmpty() || !now.is(toWear.getItem())) {
			// Put the world back the way it was: a failed attempt must not cost an item.
			if (!toWear.isEmpty()) addItem(toWear);
			this.setItemSlot(slot, previous);
			return ActionOutcome.fail("equip", "the game would not let me wear the " + id);
		}
		return ActionOutcome.ok("equip", "equipped " + id + " in " + slot.getName() + ", verified",
				ActionEvidence.world("my " + slot.getName() + " slot holds " + itemId(now)));
	}

	/**
	 * Put items on the ground from his bag. "Drop your diamonds" means the whole stack unless the
	 * request names a number, and the drop is verified from both sides: the bag shrinks and a real
	 * item entity lands in the world. Nothing is deleted if either half fails.
	 */
	private ActionOutcome dropFromBag(ActionIntent intent) {
		String wanted = intent.target() == null ? "" : intent.target().trim();
		ItemStack from = ItemStack.EMPTY;
		for (int i = 0; i < inventory.getContainerSize() && from.isEmpty(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (matchesItem(wanted, stack)) {
				from = stack;
			}
		}
		if (from.isEmpty()) {
			return ActionOutcome.fail("drop", wanted.isEmpty() ? "I am not carrying anything" : "I have no " + wanted);
		}

		String id = itemId(from);
		int count = from.getCount();
		String data = intent.data() == null ? "" : intent.data().trim().toLowerCase(Locale.ROOT);
		if (!data.isEmpty() && !data.contains("all")) {
			String digits = data.replaceAll("[^0-9]", "");
			if (!digits.isEmpty()) count = Math.min(count, Integer.parseInt(digits));
		}
		if (count <= 0) count = 1;

		BlockPos at = new BlockPos(intent.x(), intent.y(), intent.z());
		if (intent.x() == 0 && intent.y() == 0 && intent.z() == 0) at = this.blockPosition();
		if (!level().isLoaded(at)) return ActionOutcome.fail("drop", "that spot is not loaded");

		int heldBefore = countItem(id);
		ItemStack pile = from.copyWithCount(count);
		if (!consumeItem(id, count)) return ActionOutcome.fail("drop", "I could not let go of the " + id);

		ItemEntity dropped = new ItemEntity(level(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, pile);
		dropped.setDefaultPickUpDelay();
		if (!level().addFreshEntity(dropped)) {
			addItem(pile);
			return ActionOutcome.fail("drop", "the world would not take the " + id + ", so I kept it");
		}
		int heldAfter = countItem(id);
		if (heldAfter != heldBefore - count) {
			return ActionOutcome.fail("drop", "my bag says " + heldAfter + " " + id
					+ " but it should say " + (heldBefore - count));
		}
		return ActionOutcome.ok("drop", "dropped " + count + " " + id + " at " + describe(at),
				ActionEvidence.world("my bag went from " + heldBefore + " to " + heldAfter + " " + id
						+ ", and the pile is on the ground at " + describe(at)));
	}

	/**
	 * Hand one item from his bag to the owner, verified from both sides: the stack leaves his bag and
	 * lands in the owner's inventory. If it will not fit, the item goes back — a gift that vanishes is
	 * worse than a gift refused.
	 */
	private ActionOutcome giveToOwner(ActionIntent intent) {
		ServerPlayer owner = owner();
		if (owner == null) return ActionOutcome.fail("give", "I cannot see you right now");

		String wanted = intent.target() == null ? "" : intent.target().trim();
		ItemStack gift = ItemStack.EMPTY;
		for (int i = 0; i < inventory.getContainerSize() && gift.isEmpty(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (matchesItem(wanted, stack)) {
				gift = stack.copyWithCount(1);
			}
		}
		if (gift.isEmpty()) {
			return ActionOutcome.fail("give", wanted.isEmpty() ? "I have nothing to give you" : "I have no " + wanted);
		}

		String id = itemId(gift);
		// Named before the hand-over: add() empties the stack it is given, and a count taken afterwards
		// asks about minecraft:air and reads zero every time — which failed a give that had worked.
		net.minecraft.world.item.Item given = gift.getItem();
		int yoursBefore = carrying(owner, given);
		if (!consumeItem(id, 1)) return ActionOutcome.fail("give", "I could not let go of the " + id);
		if (!owner.getInventory().add(gift)) {
			addItem(gift);
			return ActionOutcome.fail("give", "your bag is full, so I kept the " + id);
		}
		// The hand-over is read off your bag, not off add()'s answer: until 0.80.0 "the inventory took it"
		// was the whole proof, and a gift that merged into nothing would still have been reported given.
		int yoursNow = carrying(owner, given);
		if (yoursNow != yoursBefore + 1) {
			// Not a failure of the hand-over so much as a count he cannot reconcile — and a gift he cannot
			// confirm is not a gift he reports as given.
			return ActionOutcome.fail("give", "your bag should hold " + (yoursBefore + 1) + " " + id
					+ " now and it says " + yoursNow);
		}
		return ActionOutcome.ok("give", "gave you 1 " + id,
				ActionEvidence.world("your bag went from " + yoursBefore + " to " + yoursNow + " " + id));
	}

	/**
	 * Make something out of what he is carrying, through the game's own recipe table.
	 *
	 * <p>The chain is resolved before anything is spent: if the result needs planks, and the planks
	 * need logs, every step is known and accounted for first ({@link CraftChain}). Steps that can be
	 * done by hand are instant; a step that needs a furnace becomes a multi-tick job (see {@link Work}),
	 * which is what makes "make me an iron pickaxe" plan through raw ore without being told.
	 */
	private ActionOutcome startCraft(ActionIntent intent) {
		// Crafting and stations are separate switches: a furnace in the chain needs the container
		// switch as well, and that refusal is raised at the furnace step, where it is the real reason.
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("craft", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("craft", "no server world");

		String wanted = resolveItemId(intent.target());
		if (wanted == null) {
			String asked = intent.target() == null ? "" : intent.target().trim();
			return ActionOutcome.fail("craft", asked.isEmpty()
					? "tell me what to craft"
					: "there is no item called " + asked);
		}
		return beginWork(intent, wanted, craftQuantity(intent.data()), null);
	}

	/**
	 * Cooking something on purpose: "smelt that iron ore". The ore names the recipe, the result comes
	 * out of the same planner as everything else, and the furnace is the one you pointed him at.
	 */
	private ActionOutcome startSmelt(ActionIntent intent) {
		if (!CobbieConfig.isContainersAllowed()) {
			return ActionOutcome.fail("smelt", "I am not allowed to use furnaces — /cobbie permit containers on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("smelt", "no server world");

		BlockPos station = new BlockPos(intent.x(), intent.y(), intent.z());
		Container furnace = Containers.resolve(serverLevel, station, owner(), CobbieConfig.isEnderChestAllowed());
		if (furnace == null || !Containers.isFurnace(furnace)) {
			return ActionOutcome.fail("smelt", "there is no furnace at " + describe(station));
		}
		String input = resolveItemId(intent.target());
		String result = smeltResultFor(serverLevel, input);
		if (result == null) {
			return ActionOutcome.fail("smelt", input == null
					? "tell me what to cook"
					: "nothing I know how to cook from " + input);
		}
		return beginWork(intent, result, craftQuantity(intent.data()), station);
	}

	/** What a furnace turns this item into, or null when it does not cook it. */
	private String smeltResultFor(ServerLevel level, String inputId) {
		if (!(level.recipeAccess() instanceof net.minecraft.world.item.crafting.RecipeManager manager)) return null;
		var type = net.minecraft.world.item.crafting.RecipeType.SMELTING;
		List<String> candidates = inputId != null ? List.of(inputId) : new ArrayList<>(carriedByItem().keySet());
		for (String candidate : candidates) {
			ItemStack sample = sampleOf(candidate);
			if (sample.isEmpty()) continue;
			var input = new net.minecraft.world.item.crafting.SingleRecipeInput(sample);
			var found = manager.getRecipeFor(type, input, level);
			if (found.isEmpty()) continue;
			ItemStack made = found.get().value().assemble(input);
			if (!made.isEmpty()) return itemId(made);
		}
		return null;
	}

	/** One of an item, for asking the recipe table questions. */
	private ItemStack sampleOf(String registryId) {
		Identifier id;
		try {
			id = Identifier.parse(registryId);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
		return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item, 1)).orElse(ItemStack.EMPTY);
	}

	/** The recipe table as the planner asks for it, remembering each match so the step can run it. */
	private CraftChain.Book recipeBook(ServerLevel serverLevel, Map<String, CraftRecipes.Match> matches) {
		return itemId -> {
			List<CraftChain.Recipe> recipes = new ArrayList<>();
			for (CraftRecipes.Match match : CraftRecipes.allFor(serverLevel, itemId)) {
				matches.put(match.recipe().id(), match);
				recipes.add(match.recipe());
			}
			return recipes;
		};
	}

	/**
	 * Why a craft the owner just asked for cannot be done at all, or null when it can.
	 *
	 * <p>Asked <b>before the plan exists</b>, because an instruction that cannot be satisfied should be refused
	 * rather than announced and then failed: a project that appears and dies leaves the owner with a chat message
	 * about a job nobody was able to start. Only a plan made entirely of crafts is judged this way — a mixed plan is
	 * still a sequence of steps, each with its own reason — and smelting is left to its own step, because a
	 * furnace's target is the ore, not the result.
	 */
	private String unsatisfiableCraft(List<ActionIntent> intents) {
		if (!(level() instanceof ServerLevel serverLevel) || intents == null || intents.isEmpty()) return null;
		for (ActionIntent intent : intents) {
			if (!ActionVocabulary.CRAFT.equals(intent.action())) return null;
		}
		for (ActionIntent intent : intents) {
			String wanted = resolveItemId(intent.target());
			if (wanted == null) return null;                      // an unknown item is the step's own refusal
			int quantity = craftQuantity(intent.data());
			if (countItem(wanted) >= quantity) continue;          // he already has it: "I already have N" stands
			CraftChain.Plan plan = CraftChain.plan(wanted, quantity,
					recipeBook(serverLevel, new LinkedHashMap<>()), carriedByItem(), CRAFT_CHAIN_DEPTH);
			if (!plan.possible()) return plan.failure();
		}
		return null;
	}

	/** Build the plan and hand it to the tick loop. */
	private ActionOutcome beginWork(ActionIntent intent, String wanted, int quantity, BlockPos fixedStation) {
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("craft", "no server world");
		Map<String, CraftRecipes.Match> matches = new LinkedHashMap<>();
		CraftChain.Book book = recipeBook(serverLevel, matches);
		String verb = intent.action();
		CraftChain.Plan plan = CraftChain.plan(wanted, quantity, book, carriedByItem(), CRAFT_CHAIN_DEPTH);
		if (!plan.possible()) return ActionOutcome.fail(verb, plan.failure());
		if (plan.steps().isEmpty()) {
			int already = countItem(wanted);
			return ActionOutcome.ok(verb, "I already have " + already + " " + wanted,
					ActionEvidence.world("my bag already holds " + already + " " + wanted));
		}

		Work job = new Work(wanted, plan, countItem(wanted));
		job.matches.putAll(matches);
		job.station = fixedStation;
		job.waitingSince = this.tickCount;
		this.work = job;
		active = intent;
		activeTarget = null;
		navigatingForAction = false;
		activeDeadlineTick = this.tickCount + STATION_STEP_TICKS;
		return null; // runs across ticks in workTick()
	}

	/** One tick of the job in flight: hand steps run here, furnace steps advance their cycle. */
	private ActionOutcome workTick() {
		Work job = this.work;
		if (job == null) {
			interrupt();
			return ActionOutcome.fail("craft", "lost track of the job");
		}
		while (job.step < job.plan.steps().size()) {
			CraftChain.Step step = job.plan.steps().get(job.step);
			CraftRecipes.Match match = job.matches.get(step.recipeId());
			if (match == null) return abortWork("craft", "I lost the recipe for " + step.result());
			if (step.needsFurnace()) return furnaceStepTick(job, step);
			if (step.needsStonecutter() && !hasStationNearby(job, true)) {
				return abortWork("craft", "I need a stonecutter for " + step.result()
						+ " and there is none near me");
			}

			for (String ingredient : step.consumes()) {
				if (!consumeItem(ingredient, 1)) {
					return abortWork("craft", "I ran out of " + ingredient + " while making " + step.result());
				}
			}
			ItemStack made = match.output().copyWithCount(match.output().getCount() * step.runs());
			String refusal = storeOrDrop(job, made);
			if (refusal != null) return abortWork("craft", refusal);
			noteChain(job, step);
			job.step++;
			job.collectedForStep = 0;
		}
		return finishWork(job);
	}

	/**
	 * One cycle at a furnace. Take what is ready, keep it fuelled, keep the ore in it, and wait —
	 * the block entity does the cooking, so there is nothing to do between results but look.
	 */
	private ActionOutcome furnaceStepTick(Work job, CraftChain.Step step) {
		if (!CobbieConfig.isContainersAllowed()) {
			return abortWork("craft", "I need a furnace to make " + step.result()
					+ " and I am not allowed to use one — /cobbie permit containers on");
		}
		if (job.station == null) {
			job.station = findFurnace();
			if (job.station == null) {
				return abortWork("craft", "I need a furnace for " + step.result() + " and there is none near me");
			}
		}
		Container furnace = Containers.resolve((ServerLevel) level(), job.station, owner(),
				CobbieConfig.isEnderChestAllowed());
		if (furnace == null || !Containers.isFurnace(furnace)) {
			job.station = null;
			return abortWork("craft", "the furnace I was using is gone");
		}

		int want = step.produced();
		int took = Containers.extract(furnace, step.result(), want - job.collectedForStep, inventory);
		if (took > 0) {
			job.collectedForStep += took;
			job.stored += took;
			job.waitingSince = this.tickCount;
		}
		if (job.collectedForStep >= want) {
			noteChain(job, step);
			job.step++;
			job.collectedForStep = 0;
			job.waitingSince = this.tickCount;
			return null;
		}

		ItemStack fuel = furnace.getItem(Containers.FURNACE_FUEL);
		if (fuel.isEmpty()) {
			ItemStack fromBag = takeFuelFromBag();
			if (fromBag.isEmpty()) return abortWork("craft", "I have no fuel for the furnace");
			if (Containers.insert(furnace, fromBag) <= 0) {
				addItem(fromBag);
				return abortWork("craft", "the furnace would not take my fuel");
			}
			job.waitingSince = this.tickCount;
		}

		ItemStack inFurnace = furnace.getItem(Containers.FURNACE_INPUT);
		boolean inputRight = !inFurnace.isEmpty() && step.consumes().contains(itemId(inFurnace));
		if (!inputRight) {
			String ingredient = step.consumes().isEmpty() ? null : step.consumes().get(0);
			if (ingredient == null) return abortWork("craft", "I do not know what to put in the furnace");
			ItemStack one = takeOne(ingredient);
			if (one.isEmpty()) return abortWork("craft", "I have no " + ingredient + " left to cook");
			if (!inFurnace.isEmpty()) {
				// Something else is in there; put it back in the bag rather than destroying it.
				furnace.setItem(Containers.FURNACE_INPUT, ItemStack.EMPTY);
				addItem(inFurnace);
			}
			furnace.setItem(Containers.FURNACE_INPUT, one);
			furnace.setChanged();
			job.waitingSince = this.tickCount;
		}

		if (this.tickCount - job.waitingSince > STATION_STALL_TICKS) {
			return abortWork("craft", "the furnace is not making " + step.result()
					+ " — it may be out of fuel or too slow for that item");
		}
		return null;
	}

	/** What he is carrying, as copies: the panel shows it and a test can look at what he holds. */
	public java.util.List<ItemStack> bagContents() {
		java.util.List<ItemStack> out = new ArrayList<>(inventory.getContainerSize());
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			out.add(inventory.getItem(i).copy());
		}
		return out;
	}

	/** His level, for the panel and for anything that has to spend one. */
	public int experienceLevel() {
		return experienceLevel;
	}

	public float experienceProgress() {
		return experienceProgress;
	}

	/** How much the next level costs, the same curve a player climbs. */
	public int experienceNeeded() {
		return xpNeededFor(experienceLevel);
	}

	/**
	 * The experience one level costs.
	 *
	 * <p><b>This is a copy of {@code Player.getXpNeededForNextLevel()}</b>, which is not callable from a
	 * mob. It is the only place these numbers live in this mod, so if the curve ever changes in a new
	 * Minecraft version, this is the one function to bring back in line: otherwise his levels drift from
	 * a player's and nothing says so until an enchant costs the wrong amount.
	 */
	private static int xpNeededFor(int level) {
		if (level >= 30) return 112 + (level - 30) * 9;
		if (level >= 15) return 37 + (level - 15) * 5;
		return 7 + level * 2;
	}

	/**
	 * Orbs are how experience exists in the world, and only a {@code Player}'s tick absorbs them. He
	 * takes them the way a player would — the game's own value, a small radius, a short delay so an orb
	 * he just earned is not consumed mid-animation — but without a phantom pickup: the orb is discarded
	 * by him rather than touched by the actor, so no other mod sees a second hand in it.
	 */
	private void absorbExperience() {
		if (this.tickCount % 4 != 0) return;
		List<net.minecraft.world.entity.ExperienceOrb> orbs = level().getEntitiesOfClass(
				net.minecraft.world.entity.ExperienceOrb.class,
				new AABB(this.blockPosition()).inflate(1.5),
				orb -> orb.isAlive() && orb.tickCount > 10);
		int gained = 0;
		for (var orb : orbs) {
			gained += orb.getValue();
			orb.discard();
		}
		if (gained > 0) addExperience(gained);
	}

	/** Put his experience at a given level — for a save that is being restored, or an admin. */
	public void setExperience(int level, float progress) {
		this.experienceLevel = Math.max(0, level);
		this.experienceProgress = Math.max(0.0f, progress);
	}

	/** Add experience the way a player does: fill the bar, level up, carry the remainder. */
	public void addExperience(int amount) {
		if (amount <= 0) return;
		this.experienceProgress += amount;
		while (this.experienceProgress >= xpNeededFor(this.experienceLevel)) {
			this.experienceProgress -= xpNeededFor(this.experienceLevel);
			this.experienceLevel++;
		}
	}

	/** Put saved items whose slots no longer exist on the ground where he stands. Runs once. */
	private void spillOverflow() {
		if (pendingSpill.isEmpty()) return;
		for (ItemStack stack : pendingSpill) {
			ItemEntity pile = new ItemEntity(level(), getX(), getY() + 0.4, getZ(), stack);
			pile.setDefaultPickUpDelay();
			if (!level().addFreshEntity(pile)) addItem(stack);
		}
		pendingSpill.clear();
	}

	/** A fuel item out of his bag, or empty. Judged by the component the furnace itself reads. */
	private ItemStack takeFuelFromBag() {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!Containers.isFuel(stack)) continue;
			ItemStack one = stack.split(1);
			if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
			return one;
		}
		return ItemStack.EMPTY;
	}

	/** One item of a registry id out of his bag, components and all. */
	private ItemStack takeOne(String registryId) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !registryId.equalsIgnoreCase(itemId(stack))) continue;
			ItemStack one = stack.split(1);
			if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
			return one;
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty() && registryId.equalsIgnoreCase(itemId(hand))) {
			ItemStack one = hand.split(1);
			if (hand.isEmpty()) this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return one;
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Is there a station of the kind the step needs within reach? The stonecutter is remembered on the
	 * job so it is not searched for again on every step, the same way the furnace is.
	 */
	private boolean hasStationNearby(Work job, boolean stonecutter) {
		if (job.station != null) return true;
		job.station = findStonecutter();
		return job.station != null;
	}

	private BlockPos findStonecutter() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockState(probe).getBlock() instanceof net.minecraft.world.level.block.StonecutterBlock) {
				return probe.immutable();
			}
		}
		return null;
	}

	private BlockPos findFurnace() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockEntity(probe) instanceof AbstractFurnaceBlockEntity) return probe.immutable();
		}
		return null;
	}

	private void noteChain(Work job, CraftChain.Step step) {
		if (job.chain.length() > 0) job.chain.append(", then ");
		job.chain.append(step.runs()).append("x ").append(step.recipeId());
	}

	/** Into the bag, or onto the ground at his feet when there is no room. Never deleted. */
	private String storeOrDrop(Work job, ItemStack made) {
		ItemStack leftover = made.copy();
		if (add(leftover, inventory)) job.stored += made.getCount() - leftover.getCount();
		if (leftover.isEmpty()) return null;
		ItemEntity pile = new ItemEntity(level(), getX(), getY() + 0.4, getZ(), leftover);
		pile.setDefaultPickUpDelay();
		if (!level().addFreshEntity(pile)) {
			addItem(leftover);
			return "my bag is full and the world would not take what I made";
		}
		job.spilled.merge(itemId(made), leftover.getCount(), Integer::sum);
		return null;
	}

	private ActionOutcome finishWork(Work job) {
		int expected = 0;
		for (CraftChain.Step step : job.plan.steps()) {
			if (step.result().equals(job.wanted)) expected += step.produced();
		}
		int gained = countItem(job.wanted) - job.bagBefore;
		int dropped = job.spilled.getOrDefault(job.wanted, 0);
		if (gained + dropped != expected) {
			return abortWork("craft", "my bag says " + gained + " new " + job.wanted
					+ " but the recipes should have made " + expected);
		}
		ActionOutcome outcome = ActionOutcome.ok("craft", "crafted " + gained + " " + job.wanted
				+ " (" + job.chain + ")" + (dropped > 0 ? ", " + dropped + " on the ground beside me" : ""),
				ActionEvidence.world("my bag gained " + gained + " " + job.wanted
						+ (dropped > 0 ? " and " + dropped + " more are on the ground beside me" : "")
						+ ", which is what the recipes said they would make"));
		this.work = null;
		clearActive();
		return outcome;
	}

	private ActionOutcome abortWork(String verb, String reason) {
		this.work = null;
		clearActive();
		return ActionOutcome.fail(verb, reason);
	}

	// ---- containers ----

	private ActionOutcome startTake(ActionIntent intent) {
		if (!CobbieConfig.isContainersAllowed()) {
			return ActionOutcome.fail("take", "I am not allowed to open containers — /cobbie permit containers on");
		}
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("take", "no server world");
		if (!serverLevel.isLoaded(pos)) return ActionOutcome.fail("take", "that area is not loaded");
		if (Containers.resolve(serverLevel, pos, owner(), CobbieConfig.isEnderChestAllowed()) == null) {
			return ActionOutcome.fail("take", "there is nothing to take from at " + describe(pos));
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("take", "nowhere to stand at " + describe(pos));
			beginWalking(intent, stand, pos);
			return null;
		}
		return takeFrom(pos, intent);
	}

	/**
	 * Write down what the owner just told him: a place when they gave coordinates, their wish when they
	 * did not. The claim is about the owner's words, not about the world — {@code ActionEvidence.ownAct}
	 * — because nothing here was read off the world. The world gets its say later, when the memory is
	 * re-read before he acts on it.
	 */
	private ActionOutcome rememberWhatOwnerSaid(ActionIntent intent) {
		UUID ownerId = getOwner();
		if (ownerId == null) return ActionOutcome.fail("remember", "I have no owner to remember things for");
		String subject = intent.target() == null ? "" : intent.target().trim();
		if (subject.isBlank()) {
			return ActionOutcome.fail("remember", "tell me what this is about, like \"home\" or \"the iron\"");
		}
		String fact = intent.data() == null ? "" : intent.data().trim();
		boolean at = intent.x() != 0 || intent.y() != 0 || intent.z() != 0;
		com.rishi.cobbie.memory.WorldMemory.learnFromOwner(this, ownerId, subject, fact, at,
				intent.x(), intent.y(), intent.z());
		// Said without the numbers: what the owner needs to hear is that it was written down, not a
		// coordinate they just gave him.
		String line = at ? "noted where " + subject + " is"
				: "noted " + subject + (fact.isBlank() ? "" : ": " + fact);
		return ActionOutcome.ok("remember", line, ActionEvidence.ownAct(
				"I wrote down what you told me about " + subject));
	}

	private ActionOutcome takeFrom(BlockPos pos, ActionIntent intent) {
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("take", "no server world");
		Container container = Containers.resolve(serverLevel, pos, owner(), CobbieConfig.isEnderChestAllowed());
		if (container == null) return ActionOutcome.fail("take", "there is nothing to take from there");
		String what = intent.target() == null ? "" : intent.target().trim();
		int limit = intent.data() == null || intent.data().isBlank() ? 64 : craftQuantity(intent.data());

		int bagBefore = bagTotal();
		int moved = Containers.extract(container, what, limit, inventory);
		if (moved <= 0) {
			return ActionOutcome.fail("take", what.isBlank()
					? "there was nothing in that " + Containers.nameOf(serverLevel, pos)
					: "there is no " + what + " in that " + Containers.nameOf(serverLevel, pos));
		}
		int gained = bagTotal() - bagBefore;
		if (gained != moved) {
			return ActionOutcome.fail("take", "I moved " + moved + " item(s) but my bag grew by " + gained);
		}
		com.rishi.cobbie.memory.WorldMemory.observeContainer(this, pos);
		return ActionOutcome.ok("take", "took " + moved + (what.isBlank() ? " item(s)" : " " + what)
				+ " from the " + Containers.nameOf(serverLevel, pos),
				ActionEvidence.world("my bag grew by exactly the " + moved + " that left the "
						+ Containers.nameOf(serverLevel, pos) + " at " + describe(pos)));
	}

	private ActionOutcome startPut(ActionIntent intent) {
		if (!CobbieConfig.isContainersAllowed()) {
			return ActionOutcome.fail("put", "I am not allowed to open containers — /cobbie permit containers on");
		}
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("put", "no server world");
		if (!serverLevel.isLoaded(pos)) return ActionOutcome.fail("put", "that area is not loaded");
		if (Containers.resolve(serverLevel, pos, owner(), false) == null) {
			return ActionOutcome.fail("put", "there is nothing to put things in at " + describe(pos));
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("put", "nowhere to stand at " + describe(pos));
			beginWalking(intent, stand, pos);
			return null;
		}
		return putInto(pos, intent);
	}

	private ActionOutcome putInto(BlockPos pos, ActionIntent intent) {
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("put", "no server world");
		Container container = Containers.resolve(serverLevel, pos, owner(), CobbieConfig.isEnderChestAllowed());
		if (container == null) return ActionOutcome.fail("put", "there is nothing to put things in there");
		String what = intent.target() == null ? "" : intent.target().trim();
		int limit = intent.data() == null || intent.data().isBlank() ? 64 : craftQuantity(intent.data());

		int bagBefore = bagTotal();
		int moved = 0;
		for (int i = 0; i < inventory.getContainerSize() && moved < limit; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !matchesItem(what, stack)) continue;
			int take = Math.min(limit - moved, stack.getCount());
			ItemStack trial = stack.copyWithCount(take);
			int put = Containers.insert(container, trial);
			if (put <= 0) break;
			stack.shrink(put);
			if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
			moved += put;
		}
		if (moved <= 0) {
			return ActionOutcome.fail("put", what.isBlank()
					? "I am not carrying anything that fits"
					: "I have no " + what + " to put in there");
		}
		int lost = bagBefore - bagTotal();
		if (lost != moved) {
			return ActionOutcome.fail("put", "I moved " + moved + " item(s) but my bag shrank by " + lost);
		}
		com.rishi.cobbie.memory.WorldMemory.observeContainer(this, pos);
		return ActionOutcome.ok("put", "put " + moved + (what.isBlank() ? " item(s)" : " " + what)
				+ " in the " + Containers.nameOf(serverLevel, pos),
				ActionEvidence.world("my bag shrank by exactly the " + moved + " that went into the "
						+ Containers.nameOf(serverLevel, pos) + " at " + describe(pos)));
	}

	/**
	 * Mend two damaged things of the same kind into one, the way a player does it on a grid.
	 *
	 * <p>The grid-reading recipes cannot go through the planner: what a repair produces depends on the
	 * stacks it consumes, and planning that ahead would mean inventing the result. So this builds a real
	 * two-item {@link net.minecraft.world.item.crafting.CraftingInput}, lets the game's own
	 * {@code RepairItemRecipe} decide whether it is a legal repair and what comes out, and only then
	 * spends anything.
	 */
	private ActionOutcome repairFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("repair", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("repair", "no server world");
		String wanted = intent.target() == null || intent.target().isBlank() ? null : resolveItemId(intent.target());

		int first = -1;
		int second = -1;
		for (int i = 0; i < inventory.getContainerSize() && second < 0; i++) {
			ItemStack a = inventory.getItem(i);
			if (!isRepairable(a) || (wanted != null && !wanted.equals(itemId(a)))) continue;
			for (int j = i + 1; j < inventory.getContainerSize(); j++) {
				ItemStack b = inventory.getItem(j);
				if (!isRepairable(b) || !ItemStack.isSameItemSameComponents(a.copyWithCount(1), b.copyWithCount(1))) {
					continue;
				}
				first = i;
				second = j;
				break;
			}
		}
		if (second < 0) {
			return ActionOutcome.fail("repair", wanted == null
					? "I need two of the same damaged thing to mend one"
					: "I need two damaged " + wanted + " to mend one");
		}

		ItemStack a = inventory.getItem(first);
		ItemStack b = inventory.getItem(second);
		ItemStack result = assembleGrid(serverLevel, List.of(a.copyWithCount(1), b.copyWithCount(1)));
		if (result.isEmpty()) return ActionOutcome.fail("repair", "that is not something the game will mend");

		inventory.setItem(first, ItemStack.EMPTY);
		inventory.setItem(second, ItemStack.EMPTY);
		// Named before the bag swallows it: add() empties the stack it is given, and reading the id
		// afterwards produces "there is one minecraft:air where two damaged ones were".
		String mended = itemId(a);
		ItemStack leftover = result.copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("repair", leftover);
		return ActionOutcome.ok("repair", "mended two " + mended + " into one",
				ActionEvidence.world("my two damaged " + mended + " are gone and there is one mended "
						+ mended + " " + kept.describe()));
	}

	/**
	 * Dye something he is carrying. The colour names the dye, and {@code data} is what to dye — the game's
	 * own {@code DyeRecipe} decides whether that pair is legal and what colours come out.
	 */
	private ActionOutcome dyeFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("dye", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("dye", "no server world");
		String colour = intent.target() == null ? "" : intent.target().trim().toLowerCase(Locale.ROOT)
				.replace(' ', '_');
		if (colour.isEmpty()) return ActionOutcome.fail("dye", "tell me what colour");
		String dyeId = resolveItemId(colour.endsWith("_dye") ? colour : colour + "_dye");
		if (dyeId == null) return ActionOutcome.fail("dye", "there is no " + colour + " dye");

		String whatId = intent.data() == null || intent.data().isBlank() ? null : resolveItemId(intent.data());
		int dyeSlot = -1;
		int targetSlot = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (dyeSlot < 0 && dyeId.equals(itemId(stack))) dyeSlot = i;
			else if (targetSlot < 0 && (whatId == null ? matchesItem("", stack) : whatId.equals(itemId(stack)))
					&& !dyeId.equals(itemId(stack))) {
				targetSlot = i;
			}
		}
		if (dyeSlot < 0) return ActionOutcome.fail("dye", "I have no " + colour + " dye");
		if (targetSlot < 0) {
			return ActionOutcome.fail("dye", whatId == null ? "I have nothing to dye" : "I have no " + whatId);
		}
		ItemStack dye = inventory.getItem(dyeSlot);
		ItemStack target = inventory.getItem(targetSlot);
		ItemStack result = assembleGrid(serverLevel, List.of(dye.copyWithCount(1), target.copyWithCount(1)));
		if (result.isEmpty()) return ActionOutcome.fail("dye", "the game will not dye that with " + dyeId);

		inventory.setItem(dyeSlot, ItemStack.EMPTY);
		inventory.setItem(targetSlot, ItemStack.EMPTY);
		String dyed = itemId(target);
		ItemStack leftover = result.copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("dye", leftover);
		return ActionOutcome.ok("dye", "dyed " + dyed + " with " + dyeId,
				ActionEvidence.world("the " + dyed + " is " + kept.describe() + ", and it took the " + dyeId));
	}

	/**
	 * Enchant something at a real enchanting table, spending his own levels and lapis.
	 *
	 * <p>This is the first thing in the mod that has to drive a *menu*: `EnchantmentMenu` takes a
	 * {@code Player.Inventory}, so the actor from 0.21.0 holds the item and the lapis, his level goes
	 * into the actor before {@code clickMenuButton} and comes back out afterwards, and the cost is read
	 * from the menu's own {@code costs[]} before anything is spent. Nothing here invents a price.
	 */
	private ActionOutcome enchantFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("enchant", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("enchant", "no server world");

		BlockPos table = findEnchantingTable();
		if (table == null) return ActionOutcome.fail("enchant", "I need an enchanting table and there is none near me");

		// Which of his things to enchant.
		String wanted = intent.target() == null || intent.target().isBlank() ? null : resolveItemId(intent.target());
		int slot = -1;
		for (int i = 0; i < inventory.getContainerSize() && slot < 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (wanted == null ? stack.isEnchantable() : wanted.equals(itemId(stack))) slot = i;
		}
		if (slot < 0) {
			return ActionOutcome.fail("enchant", wanted == null
					? "I have nothing that can be enchanted"
					: "I have no " + wanted + " to enchant");
		}

		int tier = enchantTier(intent.data());
		int lapisNeeded = tier + 1;
		if (countItem("minecraft:lapis_lazuli") < lapisNeeded) {
			return ActionOutcome.fail("enchant", "I need " + lapisNeeded + " lapis for that and I have "
					+ countItem("minecraft:lapis_lazuli"));
		}

		com.rishi.cobbie.actor.FakeActor.EnchantOutcome used = com.rishi.cobbie.actor.FakeActor.enchant(this,
				serverLevel, table, inventory.getItem(slot).copy(), lapisNeeded, experienceLevel,
				experienceProgress, tier);
		if (used == null) return ActionOutcome.fail("enchant", "the enchanting table would not take it");
		if (!used.success()) return ActionOutcome.fail("enchant", used.reason());

		// Spend what the table charged, keep the enchanted item, consume the lapis.
		this.experienceLevel = used.levelAfter();
		this.experienceProgress = used.progressAfter();
		consumeItem("minecraft:lapis_lazuli", lapisNeeded);
		inventory.setItem(slot, ItemStack.EMPTY);
		ItemStack leftover = used.remaining();
		// Before store(), which empties the stack it is handed: "enchanted it" was this name read late.
		String name = leftover.isEmpty() ? "it" : itemId(leftover);
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("enchant", leftover);
		return ActionOutcome.ok("enchant", "enchanted " + name + " for " + used.levelsSpent()
				+ " level(s) and " + lapisNeeded + " lapis",
				ActionEvidence.world("the enchanted " + name + " is " + kept.describe()));
	}

	/**
	 * Put an enchanted book onto something he is carrying, at a real anvil.
	 *
	 * <p>The book and the item go into a real {@code AnvilMenu}, and the take is a real click on its
	 * result slot — so the level charge, the refusal when he cannot afford it and the merging of
	 * enchantments are the game's, not mine.
	 */
	private ActionOutcome anvilFromBag(ActionIntent intent) {
		if (!CobbieConfig.permits("anvil")) {
			return ActionOutcome.fail("anvil", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("anvil", "no server world");
		BlockPos anvil = findAnvil();
		if (anvil == null) return ActionOutcome.fail("anvil", "I need an anvil and there is none near me");

		// A book goes on anything the game says books go on — the ENCHANTABLE component, not
		// `isEnchantable()`, which also demands the item carry no enchantment yet. That second half is
		// the table's question ("is this worth offering you"), and asking it here meant a second book on
		// a sword that already had one was refused with "nothing I carry that a book would improve is
		// called diamond_sword" — a false sentence about a sword the anvil would happily improve.
		Pick work = pick(intent.target(),
				stack -> stack.has(net.minecraft.core.component.DataComponents.ENCHANTABLE)
						&& !stack.is(Items.ENCHANTED_BOOK),
				intent.target() == null || intent.target().isBlank()
						? "I have nothing here that a book would improve"
						: "nothing I carry that a book would improve is called \"" + intent.target().trim() + "\"");
		if (!work.ok()) return ActionOutcome.fail("anvil", work.refusal());
		int itemSlot = slotOfStack(work.stack());
		if (itemSlot < 0) return ActionOutcome.fail("anvil", "I lost track of what to work on");
		ItemStack item = inventory.getItem(itemSlot);
		// The book is chosen by what the game says will go on this item — never "the first enchanted book in the
		// bag", which is how the anvil came to refuse work it had just been handed.
		List<Integer> fitting = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack candidate = inventory.getItem(i);
			if (candidate.isEmpty() || !candidate.is(Items.ENCHANTED_BOOK)) continue;
			if (candidate.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS) == null) continue;
			if (bookFits(candidate, item)) fitting.add(i);
		}
		if (fitting.isEmpty()) {
			return ActionOutcome.fail("anvil", "none of my books will go on the "
					+ item.getHoverName().getString().toLowerCase(Locale.ROOT) + " — I have " + bookList());
		}
		int bookSlot;
		if (intent.data() != null && !intent.data().isBlank()) {
			Pick named = pick(intent.data(), stack -> stack.is(Items.ENCHANTED_BOOK)
					&& stack.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS) != null,
					"I have no enchanted book like \"" + intent.data().trim() + "\"");
			if (!named.ok()) return ActionOutcome.fail("anvil", named.refusal());
			bookSlot = slotOfStack(named.stack());
			if (bookSlot < 0) return ActionOutcome.fail("anvil", "I lost track of the book");
			if (!bookFits(inventory.getItem(bookSlot), item)) {
				return ActionOutcome.fail("anvil", "\"" + bookLabel(inventory.getItem(bookSlot))
						+ "\" will not go on the " + item.getHoverName().getString().toLowerCase(Locale.ROOT));
			}
		} else if (fitting.size() == 1) {
			bookSlot = fitting.get(0);
		} else {
			// More than one would work: prefer a new enchantment over a duplicate, then the strongest. A real tie
			// is the owner's choice, not something to guess.
			fitting.sort((a, b) -> Integer.compare(bookScore(inventory.getItem(b), item),
					bookScore(inventory.getItem(a), item)));
			if (bookScore(inventory.getItem(fitting.get(0)), item)
					== bookScore(inventory.getItem(fitting.get(1)), item)) {
				StringBuilder which = new StringBuilder();
				for (int slot : fitting) {
					if (which.length() > 0) which.append(", ");
					which.append(bookLabel(inventory.getItem(slot)));
				}
				return ActionOutcome.fail("anvil", "more than one of my books would go on the "
						+ item.getHoverName().getString().toLowerCase(Locale.ROOT) + " — which one? " + which);
			}
			bookSlot = fitting.get(0);
		}
		ItemStack book = inventory.getItem(bookSlot);
		com.rishi.cobbie.actor.FakeActor.AnvilOutcome used = com.rishi.cobbie.actor.FakeActor.applyBook(this,
				serverLevel, anvil, item.copy(), book.copyWithCount(1), experienceLevel, experienceProgress);
		if (used == null || !used.success()) {
			return ActionOutcome.fail("anvil", used == null ? "the anvil would not take it" : used.reason());
		}

		this.experienceLevel = used.levelAfter();
		this.experienceProgress = used.progressAfter();
		inventory.setItem(itemSlot, ItemStack.EMPTY);
		inventory.setItem(bookSlot, ItemStack.EMPTY);
		ItemStack leftover = used.remaining();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("anvil", leftover);
		// Success is read off what he now carries, not off the actor's return value: the anvil's output is taken
		// from the menu after it has been cleared, so "remaining" can be air on a run that worked perfectly.
		// The same lesson as the item frame — the receipt is the world, never the interaction's answer.
		ItemStack finished = carriedWithEnchantments(itemId(item));
		if (finished == null) {
			return ActionOutcome.fail("anvil", "the anvil returned nothing enchanted");
		}
		com.rishi.cobbie.memory.WorldMemory.observeStation(this, anvil);
		return ActionOutcome.ok("anvil", "put an enchantment on " + itemId(finished) + " for "
				+ used.levelsSpent() + " level(s)",
				ActionEvidence.world("i am carrying an enchanted " + itemId(finished) + ", "
						+ kept.describe()));
	}

	/**
	 * Get into a boat or a minecart and ride it.
	 *
	 * <p>Riding, not driving. A fake player cannot even board one — `startRiding` refuses and the boat
	 * ends with no passengers — so steering is impossible by construction rather than unimplemented, and
	 * what a companion can honestly do is sit in the craft and come along.
	 */
	private ActionOutcome boardCraft(ActionIntent intent) {
		if (this.isPassenger()) {
			Entity riding = this.getVehicle();
			return ActionOutcome.ok("board", "already in " + (riding == null ? "something" : riding.getName().getString()),
					ActionEvidence.world("i am one of the passengers of what i am riding"));
		}
		Entity craft = nearestCraft(intent.target());
		if (craft == null) {
			return ActionOutcome.fail("board", "there is no boat or minecart near me to get into");
		}
		if (!this.startRiding(craft, true, true)) {
			return ActionOutcome.fail("board", "the " + craft.getName().getString() + " would not take me");
		}
		// Verified by the craft, not by the call: is he actually one of its passengers?
		if (!craft.getPassengers().contains(this)) {
			return ActionOutcome.fail("board", "I got in but the " + craft.getName().getString()
					+ " is not carrying me");
		}
		return ActionOutcome.ok("board", "in the " + craft.getName().getString(),
				ActionEvidence.world("i am one of the passengers of the " + craft.getName().getString()));
	}

	/** The nearest boat or minecart, honouring a hint of which kind he was asked for. */
	private Entity nearestCraft(String hint) {
		String wanted = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
		boolean boatsOnly = wanted.contains("boat") || wanted.contains("raft");
		boolean cartsOnly = wanted.contains("cart");
		List<Entity> found = level().getEntitiesOfClass(Entity.class,
				new AABB(this.blockPosition()).inflate(10.0),
				e -> e.isAlive() && !e.isRemoved() && e.getPassengers().isEmpty()
						&& (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
								|| e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart)
						&& (!boatsOnly || e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat)
						&& (!cartsOnly || e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart));
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity candidate : found) {
			double d = candidate.distanceToSqr(this);
			if (d < bestDist) {
				bestDist = d;
				best = candidate;
			}
		}
		return best;
	}

	private BlockPos findAnvil() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			// Every anvil is an anvil: a used one chips and then dents, and asking for minecraft:anvil
			// by name meant a companion standing at your chipped anvil said "I need an anvil and there
			// is none near me". The three blocks are one class, which is the question worth asking.
			if (level().getBlockState(probe).getBlock() instanceof net.minecraft.world.level.block.AnvilBlock) {
				return probe.immutable();
			}
		}
		return null;
	}

	/** "low", "mid", "high" (or blank for the cheapest on offer) to a menu button index. */
	private static int enchantTier(String data) {
		String v = data == null ? "" : data.trim().toLowerCase(Locale.ROOT);
		if (v.contains("high") || v.contains("3") || v.contains("best")) return 2;
		if (v.contains("mid") || v.contains("2") || v.contains("middle")) return 1;
		return 0;
	}

	private BlockPos findEnchantingTable() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockState(probe).is(Blocks.ENCHANTING_TABLE)) return probe.immutable();
		}
		return null;
	}

	/**
	 * Brew in a real stand, on the game's own brewing recipes.
	 *
	 * <p>A brewing stand is not the anvil's shape. It is a container that ticks itself: the bottles go
	 * into its own three slots, one reagent into the fourth, fuel into the fifth, and
	 * {@code serverTick} runs the twenty-second brew and rewrites the bottles. So this is the
	 * furnace's shape — load it, let it work, take the results out — and the menu would only put a
	 * {@code Player} between him and a machine that does not need one.
	 *
	 * <p>Everything is settled before anything leaves his bag: which bottles the named reagent
	 * actually acts on, whether the stand has fuel, and how many of his bottles fit. A pair the game
	 * will not brew is refused with the reason, not attempted.
	 */
	private ActionOutcome startBrew(ActionIntent intent) {
		if (!CobbieConfig.isContainersAllowed()) {
			return ActionOutcome.fail("brew", "I am not allowed to use a brewing stand — /cobbie permit containers on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("brew", "no server world");

		BlockPos where = new BlockPos(intent.x(), intent.y(), intent.z());
		net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand = Brewing.at(serverLevel, where);
		if (stand == null) {
			where = findBrewingStand();
			stand = Brewing.at(serverLevel, where);
		}
		if (stand == null) return ActionOutcome.fail("brew", "I need a brewing stand and there is none near me");

		String asked = intent.target() == null || intent.target().isBlank() ? null : intent.target().trim();
		int reagentSlot = -1;
		if (asked != null) {
			Pick reagent = pick(asked, stack -> true, "I have no " + asked);
			if (!reagent.ok()) return ActionOutcome.fail("brew", reagent.refusal());
			reagentSlot = slotOfStack(reagent.stack());
			if (!stand.canPlaceItem(Brewing.INGREDIENT_SLOT, inventory.getItem(reagentSlot))) {
				return ActionOutcome.fail("brew", "a brewing stand will not take " + asked);
			}
		} else {
			for (int i = 0; i < inventory.getContainerSize() && reagentSlot < 0; i++) {
				ItemStack candidate = inventory.getItem(i);
				if (candidate.isEmpty() || !stand.canPlaceItem(Brewing.INGREDIENT_SLOT, candidate)) continue;
				if (brewsSomething(serverLevel, candidate)) reagentSlot = i;
			}
		}
		if (reagentSlot < 0) {
			return ActionOutcome.fail("brew", hasBottle(serverLevel)
					? "I have nothing here the stand would brew into a potion"
					: "I need a potion to brew into first — a water bottle");
		}
		ItemStack reagent = inventory.getItem(reagentSlot);

		// Which of his bottles this reagent acts on, in the stand's free slots. A stand already
		// holding someone's bottle is used for what is free rather than emptied.
		List<Integer> bagSlots = new ArrayList<>();
		List<ItemStack> expected = new ArrayList<>();
		List<Integer> standSlots = new ArrayList<>();
		for (int slot = 0; slot < Brewing.BOTTLE_SLOTS; slot++) {
			if (!stand.getItem(slot).isEmpty()) continue;
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				if (bagSlots.contains(i)) continue;
				ItemStack bottle = inventory.getItem(i);
				if (!Brewing.isBottle(serverLevel, bottle)) continue;
				var made = Brewing.resultOf(serverLevel, bottle, reagent);
				if (made.isEmpty()) continue;
				bagSlots.add(i);
				expected.add(made.get());
				standSlots.add(slot);
				break;
			}
			if (bagSlots.size() >= Brewing.BOTTLE_SLOTS) break;
		}
		if (bagSlots.isEmpty()) {
			return ActionOutcome.fail("brew", hasBottle(serverLevel)
					? "a " + (asked != null ? asked : intent.target()) + " does not act on the potions I have"
						+ " — that needs a different potion first"
					: "I need a potion to brew into first — a water bottle");
		}

		// The stand will not run a batch with no fuel banked. One blaze powder is twenty brews, so a
		// refill here is not wasted by a single batch.
		int fuelSlot = -1;
		if (!Brewing.isFuel(stand.getItem(Brewing.FUEL_SLOT))) {
			for (int i = 0; i < inventory.getContainerSize() && fuelSlot < 0; i++) {
				if (i != reagentSlot && Brewing.isFuel(inventory.getItem(i))) fuelSlot = i;
			}
			if (fuelSlot < 0) return ActionOutcome.fail("brew", "I need blaze powder to heat the stand");
			ItemStack fuel = inventory.getItem(fuelSlot).copyWithCount(1);
			inventory.getItem(fuelSlot).shrink(1);
			if (inventory.getItem(fuelSlot).isEmpty()) inventory.setItem(fuelSlot, ItemStack.EMPTY);
			stand.setItem(Brewing.FUEL_SLOT, fuel);
		}

		// Load the stand. One unit of the reagent is what the stand spends for the whole batch,
		// however many bottles are in it.
		for (int b = 0; b < bagSlots.size(); b++) {
			int bag = bagSlots.get(b);
			ItemStack one = inventory.getItem(bag).copyWithCount(1);
			inventory.getItem(bag).shrink(1);
			if (inventory.getItem(bag).isEmpty()) inventory.setItem(bag, ItemStack.EMPTY);
			stand.setItem(standSlots.get(b), one);
		}
		ItemStack oneReagent = reagent.copyWithCount(1);
		inventory.getItem(reagentSlot).shrink(1);
		if (inventory.getItem(reagentSlot).isEmpty()) inventory.setItem(reagentSlot, ItemStack.EMPTY);
		stand.setItem(Brewing.INGREDIENT_SLOT, oneReagent);
		stand.setChanged();

		int[] slots = standSlots.stream().mapToInt(Integer::intValue).toArray();
		this.brew = new Brew(where, slots, expected, itemId(reagent), this.tickCount);
		active = intent;
		activeTarget = where;
		navigatingForAction = false;
		activeDeadlineTick = this.tickCount + STATION_STEP_TICKS;
		return null; // the stand works while brewTick watches it
	}

	/** Does this reagent change any bottle he is carrying? */
	private boolean brewsSomething(ServerLevel serverLevel, ItemStack reagent) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack bottle = inventory.getItem(i);
			if (Brewing.isBottle(serverLevel, bottle) && Brewing.resultOf(serverLevel, bottle, reagent).isPresent()) {
				return true;
			}
		}
		return false;
	}

	private boolean hasBottle(ServerLevel serverLevel) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (Brewing.isBottle(serverLevel, inventory.getItem(i))) return true;
		}
		return false;
	}

	/**
	 * One tick of brewing: done is the stand holding what the recipe said it would, not a count of
	 * ticks that passed. A stand that swallowed the reagent and produced nothing says so instead of
	 * waiting out the whole timeout.
	 */
	private ActionOutcome brewTick() {
		Brew job = this.brew;
		if (job == null) {
			interrupt();
			return ActionOutcome.fail("brew", "lost track of the brewing");
		}
		if (!(level() instanceof ServerLevel serverLevel)) {
			return endBrew(ActionOutcome.fail("brew", "no server world"));
		}
		net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand = Brewing.at(serverLevel, job.stand);
		if (stand == null) {
			return endBrew(ActionOutcome.fail("brew", "the brewing stand is gone, and my bottles were in it"));
		}

		boolean ready = true;
		for (int i = 0; i < job.slots.length; i++) {
			ItemStack now = stand.getItem(job.slots[i]);
			if (now.isEmpty() || !ItemStack.isSameItemSameComponents(now, job.expected.get(i))) {
				ready = false;
				break;
			}
		}
		if (ready) return collectBrewed(stand, job);

		int waited = this.tickCount - job.startedTick;
		if (waited > STATION_STEP_TICKS) {
			return endBrew(ActionOutcome.fail("brew", "the stand never finished the " + job.reagent + " batch"));
		}
		// The reagent is spent at the moment the batch completes, so this only counts as a refusal
		// well after the stand would have had it.
		if (waited > 60 && stand.getItem(Brewing.INGREDIENT_SLOT).isEmpty()) {
			return endBrew(ActionOutcome.fail("brew", "the stand took the " + job.reagent + " and brewed nothing"));
		}
		return null;
	}

	/**
	 * Finish a brew, outcome and all.
	 *
	 * <p>The job has to end in the same way every other verb does, or the entity stays "busy with
	 * brew" after reporting success: the next step of the plan is then either refused outright ("still
	 * busy with brew") or handed the previous step's stale outcome. Measured, not feared — a
	 * two-step plan of walk-then-anvil failed both steps this way, the first with "lost track of the
	 * brewing", because a finished batch left {@code active} pointing at a job that no longer existed.
	 */
	private ActionOutcome endBrew(ActionOutcome outcome) {
		this.brew = null;
		clearActive();
		return outcome;
	}

	private ActionOutcome collectBrewed(net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand,
			Brew job) {
		int taken = 0;
		String potion = null;
		Stored brewedWhere = Stored.NOWHERE;
		for (int slot : job.slots) {
			ItemStack made = stand.getItem(slot);
			if (made.isEmpty()) continue;
			potion = Brewing.potionName(made);
			ItemStack copy = made.copy();
			stand.setItem(slot, ItemStack.EMPTY);
			taken += copy.getCount();
			Stored kept = store(copy);
			if (kept == Stored.NOWHERE) return lostIt("brew", copy);
			brewedWhere = kept;
		}
		stand.setChanged();
		if (taken == 0) return endBrew(ActionOutcome.fail("brew", "nothing came out of the stand"));
		return endBrew(ActionOutcome.ok("brew", "brewed " + taken + " " + (potion == null ? "potions" : potion),
				ActionEvidence.world(taken + " " + (potion == null ? "potion(s)" : potion + "(s)")
						+ " came off the stand and are " + brewedWhere.describe())));
	}

	private BlockPos findBrewingStand() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockState(probe).is(Blocks.BREWING_STAND)) return probe.immutable();
		}
		return null;
	}

	/**
	 * Upgrade something at a real smithing table — the netherite path, and armour trims.
	 *
	 * <p>The table's menu is three input slots and a result slot whose whole job is
	 * {@code getRecipeFor(SMITHING, input, level)} then {@code assemble(input)}, with no charge and no
	 * menu-side state, so this is that lookup and nothing else. The result is
	 * {@code TransmuteRecipe.createWithOriginalComponents}: it rewrites the base item and carries the
	 * base's components — enchantments, name, damage — onto the result. That is what makes netherite
	 * an upgrade of <em>his</em> pickaxe instead of a fresh one, and it is why the base goes in as the
	 * real stack.
	 */
	private ActionOutcome smithFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("smith", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("smith", "no server world");
		BlockPos table = Smithing.tableNear(serverLevel, this.blockPosition(), FURNACE_SEARCH);
		if (table == null) return ActionOutcome.fail("smith", "I need a smithing table and there is none near me");

		// The base and the addition may be described ("my best sword", "the netherite ingot"), so both go
		// through the one resolver; the template is whatever the table accepts.
		Pick basePick = pick(intent.target(), stack -> Smithing.isBase(serverLevel, stack),
				intent.target() == null || intent.target().isBlank()
						? "I have nothing the table would upgrade"
						: "I have nothing the table would upgrade called \"" + intent.target().trim() + "\"");
		if (!basePick.ok()) return ActionOutcome.fail("smith", basePick.refusal());
		Pick additionPick = pick(intent.data(), stack -> Smithing.isAddition(serverLevel, stack),
				intent.data() == null || intent.data().isBlank()
						? "I have no template or ingot the table would take"
						: "I have nothing the table would take called \"" + intent.data().trim() + "\"");
		if (!additionPick.ok()) return ActionOutcome.fail("smith", additionPick.refusal());
		String wantedBase = itemId(basePick.stack());
		String wantedAddition = itemId(additionPick.stack());

		// Only the slots the table itself would accept are worth trying, which takes 45^3 recipe
		// searches down to a handful of candidate trios.
		List<Integer> bases = new ArrayList<>();
		List<Integer> templates = new ArrayList<>();
		List<Integer> additions = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (Smithing.isBase(serverLevel, stack)) bases.add(i);
			if (Smithing.isTemplate(serverLevel, stack)) templates.add(i);
			if (Smithing.isAddition(serverLevel, stack)) additions.add(i);
		}

		int baseSlot = -1;
		int templateSlot = -1;
		int additionSlot = -1;
		net.minecraft.world.item.crafting.SmithingRecipe recipe = null;
		// An upgrade changes the item and a trim only decorates one. Both are smithing recipes, so
		// without this preference "make my pickaxe netherite" could resolve to a trim on a chestplate
		// that happened to match first.
		for (int pass = 0; pass < 2 && recipe == null; pass++) {
			for (int b : bases) {
				if (wantedBase != null && !wantedBase.equals(itemId(inventory.getItem(b)))) continue;
				for (int t : templates) {
					if (t == b) continue;
					for (int a : additions) {
						if (a == b || a == t) continue;
						if (wantedAddition != null && !wantedAddition.equals(itemId(inventory.getItem(a)))) continue;
						var match = Smithing.match(serverLevel, inventory.getItem(t), inventory.getItem(b),
								inventory.getItem(a));
						if (match.isEmpty()) continue;
						if (pass == 0 && !Smithing.isUpgrade(match.get().value())) continue;
						recipe = match.get().value();
						baseSlot = b;
						templateSlot = t;
						additionSlot = a;
						break;
					}
					if (recipe != null) break;
				}
				if (recipe != null) break;
			}
		}
		if (recipe == null) {
			if (wantedBase != null && !inventoryHas(wantedBase)) {
				return ActionOutcome.fail("smith", "I have no " + wantedBase);
			}
			return ActionOutcome.fail("smith", wantedBase == null
					? "I have nothing a smithing table would change"
					: "I have no template and material that would upgrade " + wantedBase);
		}

		ItemStack base = inventory.getItem(baseSlot);
		ItemStack template = inventory.getItem(templateSlot);
		ItemStack addition = inventory.getItem(additionSlot);
		ItemStack made = Smithing.assemble(recipe, template.copyWithCount(1), base.copy(), addition.copyWithCount(1));
		if (made.isEmpty()) return ActionOutcome.fail("smith", "the table made nothing out of those");

		// The menu spends exactly one of each on a take; so does this.
		shrinkSlot(baseSlot, 1);
		shrinkSlot(templateSlot, 1);
		shrinkSlot(additionSlot, 1);
		ItemStack leftover = made.copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("smith", leftover);
		String did = Smithing.isUpgrade(recipe) ? "upgraded " + itemId(base) + " into " : "put a trim on ";
		return ActionOutcome.ok("smith", did + itemId(made),
				ActionEvidence.world("the table's " + itemId(made) + " is " + kept.describe()));
	}

	/** Take {@code amount} out of one bag slot, emptying it rather than leaving a zero-count stack. */
	private void shrinkSlot(int slot, int amount) {
		ItemStack stack = inventory.getItem(slot);
		if (stack.isEmpty()) return;
		stack.shrink(amount);
		if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
	}

	private boolean inventoryHas(String registryId) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (registryId.equals(itemId(inventory.getItem(i)))) return true;
		}
		return false;
	}

	/**
	 * Take the enchantments off something at a real grindstone.
	 *
	 * <p>The grindstone's menu holds no XP in 26.3 — its {@code removed} only clears the input slots —
	 * so there is no cost to price and nothing to invent: the item goes into a real
	 * {@code GrindstoneMenu}, {@code computeResult} decides whether it is something that can be
	 * stripped, and the take is a real click on the result slot. What comes back is verified to have
	 * no enchantments on it, because "the menu offered something" and "the enchantments are gone" are
	 * not the same claim.
	 */
	private ActionOutcome grindFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("grind", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("grind", "no server world");
		BlockPos stone = findGrindstone();
		if (stone == null) return ActionOutcome.fail("grind", "I need a grindstone and there is none near me");

		Pick toStrip = pick(intent.target(),
				stack -> net.minecraft.world.item.enchantment.EnchantmentHelper.hasAnyEnchantments(stack),
				intent.target() == null || intent.target().isBlank()
						? "I have nothing with an enchantment on it to take off"
						: "I have no enchanted " + intent.target().trim());
		if (!toStrip.ok()) return ActionOutcome.fail("grind", toStrip.refusal());
		int slot = slotOfStack(toStrip.stack());
		if (slot < 0) return ActionOutcome.fail("grind", "I lost track of what to strip");

		ItemStack item = inventory.getItem(slot);
		String itemName = itemId(item);
		com.rishi.cobbie.actor.FakeActor.Outcome used = com.rishi.cobbie.actor.FakeActor.grind(this, serverLevel,
				stone, item.copy());
		if (used == null || !used.result().consumesAction() || used.remaining().isEmpty()) {
			return ActionOutcome.fail("grind", "the grindstone will not take the enchantments off " + itemId(item));
		}
		if (net.minecraft.world.item.enchantment.EnchantmentHelper.hasAnyEnchantments(used.remaining())) {
			return ActionOutcome.fail("grind", "that came back still enchanted, so I left it alone");
		}

		shrinkSlot(slot, 1);
		ItemStack leftover = used.remaining().copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("grind", leftover);
		return ActionOutcome.ok("grind", "took the enchantments off " + itemName,
				ActionEvidence.world("the " + itemName + " with nothing on it is " + kept.describe()));
	}

	/**
	 * Put a banner pattern on a banner at a real loom.
	 *
	 * <p>The model names the pattern <b>item</b> and this finds it by that name, so the choice that
	 * matters is his rather than an index's: the loom derives its pattern list from the item, and with
	 * one item in the slot the list has one entry. A pattern he cannot name is a pattern he cannot ask
	 * for, and the refusal says so instead of picking one for him.
	 */
	private ActionOutcome weaveFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("weave", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("weave", "no server world");
		BlockPos loom = findStation(Blocks.LOOM);
		if (loom == null) return ActionOutcome.fail("weave", "I need a loom and there is none near me");

		int bannerSlot = -1;
		for (int i = 0; i < inventory.getContainerSize() && bannerSlot < 0; i++) {
			if (inventory.getItem(i).getItem() instanceof net.minecraft.world.item.BannerItem) bannerSlot = i;
		}
		if (bannerSlot < 0) return ActionOutcome.fail("weave", "I have no banner to work on");

		String asked = intent.target() == null || intent.target().isBlank() ? null : resolveItemId(intent.target());
		int patternSlot = -1;
		for (int i = 0; i < inventory.getContainerSize() && patternSlot < 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || i == bannerSlot) continue;
			if (asked != null ? !asked.equals(itemId(stack)) : !itemId(stack).contains("banner_pattern")) continue;
			patternSlot = i;
		}
		if (patternSlot < 0) {
			String held = String.join(", ", carriedByItem().keySet().stream()
					.filter(id -> id.contains("banner_pattern")).toList());
			return ActionOutcome.fail("weave", asked != null
					? "I have no " + asked
					: held.isEmpty() ? "I have no banner pattern to put on it"
							: "tell me which pattern — I have " + held);
		}

		String colour = intent.data() == null ? "" : intent.data().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		String dyeId = colour.isEmpty() ? null : resolveItemId(colour.endsWith("_dye") ? colour : colour + "_dye");
		int dyeSlot = -1;
		for (int i = 0; i < inventory.getContainerSize() && dyeSlot < 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || i == bannerSlot || i == patternSlot) continue;
			if (dyeId != null ? dyeId.equals(itemId(stack))
					: stack.getItem() instanceof net.minecraft.world.item.DyeItem) {
				dyeSlot = i;
			}
		}
		if (dyeSlot < 0) {
			return ActionOutcome.fail("weave", dyeId != null ? "I have no " + dyeId : "I have no dye to colour it");
		}

		ItemStack banner = inventory.getItem(bannerSlot);
		ItemStack dye = inventory.getItem(dyeSlot);
		ItemStack pattern = inventory.getItem(patternSlot);
		// Named before anything is spent: the stacks are emptied by the time the report is written,
		// and "wove minecraft:air onto the banner" is what reading them afterwards produces.
		String patternName = itemId(pattern) + " onto the banner in " + itemId(dye);
		com.rishi.cobbie.actor.FakeActor.MadeOutcome made = com.rishi.cobbie.actor.FakeActor.weave(this,
				serverLevel, loom, banner.copy(), dye.copy(), pattern.copy());
		if (made == null || !made.success()) {
			return ActionOutcome.fail("weave", made == null ? "the loom would not take it" : made.reason());
		}

		// Verify what the loom made, before it goes into the bag. Checking the stack afterwards is
		// checking a stack that add() has already emptied: the pattern and the dye get consumed, the
		// banner lands, and the step then reports failure for an action that worked.
		if (made.result().get(net.minecraft.core.component.DataComponents.BANNER_PATTERNS) == null) {
			return ActionOutcome.fail("weave", "the banner came back with no pattern on it");
		}
		shrinkSlot(bannerSlot, 1);
		shrinkSlot(dyeSlot, 1);
		shrinkSlot(patternSlot, 1);
		ItemStack leftover = made.result().copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("weave", leftover);
		return ActionOutcome.ok("weave", "wove " + patternName,
				ActionEvidence.world("the patterned banner is " + kept.describe()));
	}

	/**
	 * Work a map at a real cartography table: paper zooms it out, an empty map copies it, a glass
	 * pane locks it. The table decides which a pair is; a pair it will not do is refused by it.
	 */
	private ActionOutcome mapFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) {
			return ActionOutcome.fail("map", "making things is switched off — /cobbie permit crafting on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("map", "no server world");
		BlockPos table = findStation(Blocks.CARTOGRAPHY_TABLE);
		if (table == null) {
			return ActionOutcome.fail("map", "I need a cartography table and there is none near me");
		}

		int mapSlot = -1;
		for (int i = 0; i < inventory.getContainerSize() && mapSlot < 0; i++) {
			if (inventory.getItem(i).is(Items.FILLED_MAP)) mapSlot = i;
		}
		if (mapSlot < 0) return ActionOutcome.fail("map", "I have no map to work on");

		String asked = intent.data() == null || intent.data().isBlank() ? null : resolveItemId(intent.data());
		int additionSlot = -1;
		// Paper zooms, an empty map copies, a glass pane locks. Without a word from him, zoom first:
		// it is what "make my map bigger" means and paper is what he is most likely to be carrying.
		for (String want : asked != null ? List.of(asked)
				: List.of("minecraft:paper", "minecraft:map", "minecraft:glass_pane")) {
			for (int i = 0; i < inventory.getContainerSize() && additionSlot < 0; i++) {
				if (i != mapSlot && want.equals(itemId(inventory.getItem(i)))) additionSlot = i;
			}
			if (additionSlot >= 0) break;
		}
		if (additionSlot < 0) {
			return ActionOutcome.fail("map", "I have no " + (asked != null ? asked : "paper to zoom it with"));
		}

		ItemStack map = inventory.getItem(mapSlot);
		ItemStack addition = inventory.getItem(additionSlot);
		com.rishi.cobbie.actor.FakeActor.MadeOutcome made = com.rishi.cobbie.actor.FakeActor.cartography(this,
				serverLevel, table, map.copy(), addition.copy());
		if (made == null || !made.success()) {
			return ActionOutcome.fail("map", made == null ? "the table would not take it" : made.reason());
		}

		// Before the slot is shrunk: the local stack is the slot's own, and a last one leaves it air.
		String withWhat = itemId(addition);
		shrinkSlot(mapSlot, 1);
		shrinkSlot(additionSlot, 1);
		ItemStack leftover = made.result().copy();
		Stored kept = store(leftover);
		if (kept == Stored.NOWHERE) return lostIt("map", leftover);
		return ActionOutcome.ok("map", "worked the map with " + withWhat,
				ActionEvidence.world("the worked map is " + kept.describe()));
	}

	/**
	 * Buy something from a villager, paying out of his own bag.
	 *
	 * <p>A probe settled the mechanism: {@code MerchantMenu} does build against the fake actor and fills
	 * its result slot, and — more usefully — {@code Merchant.notifyTrade} runs with **no trading player
	 * at all** (uses 0 → 1, +5 villager xp). So no menu is needed. The villager's own bookkeeping counts
	 * the use, pays its xp and drives its restock; this only spends what the offer asks for and hands
	 * over what it gives.
	 *
	 * <p>The owner's constraint, in code: nothing is spent until the price has been read off the offer
	 * and counted against his bag, and a refusal says what it would have cost. A trade the villager will
	 * not take is refused naming the half that failed — the price, the stock, or the item not being sold
	 * at all — never retried.
	 */
	private ActionOutcome tradeWithVillager(ActionIntent intent) {
		if (!CobbieConfig.permits("trade")) {
			return ActionOutcome.fail("trade", "I am not allowed to use villagers — /cobbie permit containers on");
		}
		net.minecraft.world.item.trading.Merchant merchant = null;
		for (net.minecraft.world.entity.Entity e : level().getEntitiesOfClass(
				net.minecraft.world.entity.Entity.class, this.getBoundingBox().inflate(BREED_RADIUS),
				e -> e instanceof net.minecraft.world.item.trading.Merchant)) {
			merchant = (net.minecraft.world.item.trading.Merchant) e;
			break;
		}
		if (merchant == null) return ActionOutcome.fail("trade", "there is no villager near me to trade with");

		String wanted = intent.target() == null || intent.target().isBlank() ? null : resolveItemId(intent.target());
		if (wanted == null) {
			return ActionOutcome.fail("trade", "the villager offers: " + offersText(merchant));
		}

		net.minecraft.world.item.trading.MerchantOffer chosen = null;
		boolean soldOut = false;
		for (net.minecraft.world.item.trading.MerchantOffer offer : merchant.getOffers()) {
			if (!wanted.equals(itemId(offer.getResult()))) continue;
			if (offer.getUses() >= offer.getMaxUses()) {
				soldOut = true;
				continue;
			}
			chosen = offer;
			break;
		}
		if (chosen == null) {
			return ActionOutcome.fail("trade", soldOut
					? "the villager has sold out of " + wanted + " for now"
					: "the villager does not sell " + wanted + " — it offers: " + offersText(merchant));
		}

		// What one trade costs, read from the offer itself so a discounted price is the price.
		ItemStack costA = chosen.getCostA();
		ItemStack costB = chosen.getCostB();
		int haveA = countItem(itemId(costA));
		int haveB = costB.isEmpty() ? Integer.MAX_VALUE : countItem(itemId(costB));
		if (haveA < costA.getCount() || haveB < costB.getCount()) {
			return ActionOutcome.fail("trade", "that costs " + describeCost(costA, costB)
					+ " and I have " + haveA + " " + itemId(costA)
					+ (costB.isEmpty() ? "" : " and " + haveB + " " + itemId(costB)));
		}

		// One trade at a time, and only as many as the stock allows: the villager's own counter is the
		// authority on how many are left, so it is re-read rather than assumed.
		int trades = Math.min(intent.data() == null ? 1 : Math.max(1, craftQuantity(intent.data())),
				chosen.getMaxUses() - chosen.getUses());
		int bought = 0;
		ItemStack last = ItemStack.EMPTY;
		// Named before it is stored: add() empties the stack, and "bought 4 minecraft:air" is what
		// reading the id afterwards produces.
		String resultName = null;
		Stored boughtWhere = Stored.NOWHERE;
		for (int i = 0; i < trades; i++) {
			ItemStack priceA = chosen.getCostA();
			ItemStack priceB = chosen.getCostB();
			if (countItem(itemId(priceA)) < priceA.getCount()
					|| (!priceB.isEmpty() && countItem(itemId(priceB)) < priceB.getCount())) {
				break;
			}
			consumeItem(itemId(priceA), priceA.getCount());
			if (!priceB.isEmpty()) consumeItem(itemId(priceB), priceB.getCount());
			ItemStack result = chosen.getResult().copy();
			last = result;
			resultName = itemId(result);
			Stored kept = store(result);
			if (kept == Stored.NOWHERE) return lostIt("trade", result);
			boughtWhere = kept;
			merchant.notifyTrade(chosen);
			bought += chosen.getResult().getCount();
		}
		if (bought == 0) return ActionOutcome.fail("trade", "the villager would not complete that trade");
		return ActionOutcome.ok("trade", "bought " + bought + " " + (resultName == null ? "it" : resultName)
				+ " for " + describeCost(chosen.getCostA(), chosen.getCostB()),
				ActionEvidence.world("i paid the villager and there are now " + bought + " "
						+ (resultName == null ? "of them" : resultName) + " " + boughtWhere.describe()));
	}

	/** "1 emerald" or "1 emerald and 2 wheat", for a price that has to be named. */
	private static String describeCost(ItemStack a, ItemStack b) {
		StringBuilder sb = new StringBuilder(a.getCount() + " "
				+ BuiltInRegistries.ITEM.getKey(a.getItem()).getPath());
		if (b != null && !b.isEmpty()) {
			sb.append(" and ").append(b.getCount()).append(' ')
					.append(BuiltInRegistries.ITEM.getKey(b.getItem()).getPath());
		}
		return sb.toString();
	}

	/** What a villager sells, in one line, so a refusal is useful rather than a dead end. */
	private static String offersText(net.minecraft.world.item.trading.Merchant merchant) {
		StringBuilder sb = new StringBuilder();
		for (net.minecraft.world.item.trading.MerchantOffer offer : merchant.getOffers()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(offer.getResult().getCount()).append(' ')
					.append(BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).getPath())
					.append(" for ").append(describeCost(offer.getCostA(), offer.getCostB()));
		}
		return sb.length() == 0 ? "nothing" : sb.toString();
	}

	/**
	 * Feed two adults of the same kind and let the game make the calf.
	 *
	 * <p>A probe settled the mechanism before this was written: feeding through the actor does put both
	 * animals in love, and {@code canMate} then says yes — but no calf appeared in twelve seconds, because
	 * the pairing is normally completed by the animal's own breeding goal and that goal never runs for
	 * them here. What does run is the game's own code for the calf itself:
	 * {@code Animal.spawnChildFromBreeding} is public, and it creates the offspring, sets both parents'
	 * cooldowns and plays the sounds. So this feeds, checks the preconditions, and then asks the game to
	 * do the part it owns.
	 *
	 * <p>Every refusal names the condition that failed — no food, one adult, a baby, a cooldown — because
	 * "it did not work" teaches neither the model nor the owner anything.
	 */
	private ActionOutcome breedNearby(ActionIntent intent) {
		if (!CobbieConfig.permits("breed")) {
			return ActionOutcome.fail("breed", "I am not allowed to handle animals — /cobbie permit building on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("breed", "no server world");

		String wanted = intent.target() == null || intent.target().isBlank() ? null : intent.target()
				.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		Identifier typeId = entityId(wanted == null ? "" : wanted);

		// The adults worth pairing, and the food he has for them. isFood is the animal's own answer.
		ItemStack food = ItemStack.EMPTY;
		// Named from the request before anything is looked at, so "I cannot see any cow" can say *cow*
		// even in an empty field. It used to be filled only from an animal he had already found, which
		// is exactly the case where the refusal most needs to name one.
		String kind = typeId == null ? null : typeId.getPath();
		List<net.minecraft.world.entity.animal.Animal> adults = new ArrayList<>();
		List<net.minecraft.world.entity.animal.Animal> babies = new ArrayList<>();
		// Counted separately from the two lists, because an animal he can see but cannot use is still an
		// animal he can see: "I cannot see any cow" over two cows standing in front of him would be its
		// own kind of lie. Only what the loop actually looked at counts here.
		int seen = 0;
		boolean coolingDown = false;
		for (net.minecraft.world.entity.Entity e : level().getEntitiesOfClass(
				net.minecraft.world.entity.animal.Animal.class, this.getBoundingBox().inflate(BREED_RADIUS))) {
			if (typeId != null && !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).equals(typeId)) continue;
			net.minecraft.world.entity.animal.Animal animal = (net.minecraft.world.entity.animal.Animal) e;
			seen++;
			if (animal.isBaby()) {
				babies.add(animal);
				continue;
			}
			if (!animal.canFallInLove()) {
				coolingDown = true;
				continue;
			}
			if (food.isEmpty()) food = foodFor(animal);
			if (!food.isEmpty() && animal.isFood(food)) {
				kind = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()))
						.replace("minecraft:", "");
				adults.add(animal);
			}
		}

		if (adults.size() < 2) {
			if (seen == 0) {
				return ActionOutcome.fail("breed", "I cannot see any "
						+ (kind == null ? "animals" : kind) + " to breed");
			}
			if (food.isEmpty()) {
				return ActionOutcome.fail("breed", "I have nothing here that a "
						+ (kind == null ? "animal" : kind) + " would eat");
			}
			if (babies.size() + adults.size() >= 2 && coolingDown) {
				return ActionOutcome.fail("breed", "the " + (kind == null ? "animal" : kind)
						+ " is on a breeding cooldown — it will not be ready for a while");
			}
			return ActionOutcome.fail("breed", "I need two grown " + (kind == null ? "animals" : kind)
					+ " close together and I can see " + (adults.size() + babies.size()));
		}

		// Feed both through the actor: that is the step that sets them in love, and it is the game's own
		// mobInteract rather than a flag set behind its back.
		net.minecraft.world.entity.animal.Animal first = adults.get(0);
		net.minecraft.world.entity.animal.Animal second = adults.get(1);
		String foodId = itemId(food);
		for (net.minecraft.world.entity.animal.Animal animal : List.of(first, second)) {
			com.rishi.cobbie.actor.FakeActor.useOnEntity(this, animal, food.copyWithCount(1));
		}
		if (!first.isInLove() || !second.isInLove() || !first.canMate(second)) {
			// Report what is actually observed, not a guess at the cause. A cow that has just calved
			// still answers canFallInLove() true in this version and simply declines the wheat, so
			// claiming "cooldown" would be a diagnosis the run does not support.
			boolean inLove = first.isInLove() || second.isInLove();
			return ActionOutcome.fail("breed", inLove
					? "one of the " + kind + " is already in love with another animal"
					: "the " + kind + " would not take the " + foodId
							+ " — it may still be recovering from the last young");
		}

		int calvesBefore = level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
				this.getBoundingBox().inflate(BREED_RADIUS),
				net.minecraft.world.entity.animal.Animal::isBaby).size();
		// The calf, the parents' cooldowns and the sounds are the game's; this only asks for it.
		first.spawnChildFromBreeding(serverLevel, second);
		int calvesAfter = level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
				this.getBoundingBox().inflate(BREED_RADIUS),
				net.minecraft.world.entity.animal.Animal::isBaby).size();
		if (calvesAfter <= calvesBefore) {
			return ActionOutcome.fail("breed", "nothing came of the pairing — the " + kind + " are ready but"
					+ " no young appeared");
		}
		consumeItem(foodId, 2);
		return ActionOutcome.ok("breed", "fed a pair of " + kind + " and a young one was born",
				ActionEvidence.world("there are " + calvesAfter + " young " + kind + " here now, up from "
						+ calvesBefore));
	}

	/** The first food in his bag that this animal would eat, or empty. */
	private ItemStack foodFor(net.minecraft.world.entity.animal.Animal animal) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (animal.isFood(stack)) return stack;
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty() && animal.isFood(hand)) return hand;
		return ItemStack.EMPTY;
	}

	/**
	 * Harvest every ripe crop near him and replant it, which is what a person wants when they say
	 * "do the farm".
	 *
	 * <p>Tilling, sowing and bone meal already work through {@code use} — a hoe is a
	 * {@code BLOCK_TRANSFORMER} component and seeds are a plain {@code BlockItem}, both reached through
	 * {@code ItemStack.useOn}. A probe confirmed all three before this verb existed. What was missing is
	 * the cycle: a ripe crop is broken, its drops are his, and the same cell is sown again from his bag.
	 *
	 * <p>What replants a crop is not guessed: the seed item is the one whose {@code BlockItem} places
	 * this very block, which is the game's own link between wheat and wheat seeds. {@code
	 * CropBlock.getBaseSeedId} is protected, so that link is the way to ask.
	 *
	 * <p>A refusal names the real reason — nothing ripe, or no seed to replant with — because "it did
	 * not work" teaches nothing.
	 */
	private ActionOutcome farmAround(ActionIntent intent) {
		if (!CobbieConfig.permits("farm")) {
			return ActionOutcome.fail("farm", "my owner has not allowed building — /cobbie permit building on");
		}
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("farm", "no server world");

		String wanted = intent.target() == null || intent.target().isBlank() ? null : intent.target()
				.trim().toLowerCase(Locale.ROOT).replace(' ', '_');

		BlockPos base = this.blockPosition();
		List<BlockPos> ripe = new ArrayList<>();
		List<BlockPos> growing = new ArrayList<>();
		for (BlockPos probe : BlockPos.betweenClosed(base.offset(-FARM_RADIUS, -2, -FARM_RADIUS),
				base.offset(FARM_RADIUS, 2, FARM_RADIUS))) {
			BlockState state = level().getBlockState(probe);
			if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)) continue;
			String cropId = blockPath(crop);
			if (wanted != null && !wanted.contains(cropId.replace("crop", "")) && !cropId.contains(wanted)) continue;
			if (crop.isMaxAge(state)) ripe.add(probe.immutable());
			else growing.add(probe.immutable());
		}
		if (ripe.isEmpty()) {
			return ActionOutcome.fail("farm", growing.isEmpty()
					? "there are no crops near me to harvest"
					: "nothing is ripe yet — " + growing.size() + " crop(s) are still growing");
		}

		int harvested = 0;
		int replanted = 0;
		String what = null;
		for (BlockPos pos : ripe) {
			BlockState state = level().getBlockState(pos);
			if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)) continue;
			what = blockPath(crop);
			String seedId = replantItemFor(crop);
			boolean replant = seedId != null && countItem(seedId) > 0;
			// Drops, not a silent removal: what a crop gives is the game's business (and it varies with
			// the tool), so it drops and he picks it up the way he collects anything else.
			//
			// Counted off the world, not off the call: destroyBlock answers whether it actually removed
			// the block, and a crop still standing is not a crop harvested. Before 0.79.0 the counter went
			// up regardless, so a refusal from the world read as "harvested 2".
			level().destroyBlock(pos, true, this);
			// Counted off the square, before anything is put back on it. destroyBlock's answer is a claim
			// about the call; a crop still standing is not a crop harvested. 0.79.0 stopped discarding that
			// boolean; 0.80.0 stops asking the call at all, so the number and the world cannot disagree.
			if (level().getBlockState(pos).getBlock() == crop) continue;
			harvested++;
			if (replant && consumeItem(seedId, 1)) {
				level().setBlock(pos, crop.getStateForAge(0), 3);
				if (level().getBlockState(pos).getBlock() == crop) replanted++;
			}
		}
		collectWithinReach();

		if (harvested == 0) return ActionOutcome.fail("farm", "nothing came off the crops");
		String name = what == null ? "crops" : what;
		return ActionOutcome.ok("farm", "harvested " + harvested + " ripe " + name
				+ (replanted == harvested ? " and replanted them all"
						: replanted == 0 ? " and had no seed to replant with"
								: " and replanted " + replanted),
				ActionEvidence.world(harvested + " of the " + ripe.size() + " ripe " + name
						+ " are cut, and " + replanted + " of those squares are planted again"));
	}

	/**
	 * The item that sows this crop, or null when nothing does.
	 *
	 * <p>Found by the link the game keeps: a seed is a {@code BlockItem} that places this exact block.
	 * Asking the registry beats a table, which would silently rot when a crop is added or renamed.
	 */
	/** A block's registry path, wherever the key type puts it. */
	private static String blockPath(Block block) {
		String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	private static String replantItemFor(Block crop) {
		String name = blockPath(crop);
		for (String candidate : List.of(name + "_seeds", name)) {
			Identifier id;
			try {
				id = Identifier.parse("minecraft:" + candidate);
			} catch (Exception e) {
				continue;
			}
			var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item instanceof net.minecraft.world.item.BlockItem blockItem && blockItem.getBlock() == crop) {
				return id.toString();
			}
		}
		return null;
	}

	/** The nearest block of a kind he already knows how to walk to, or null. */
	private BlockPos findStation(Block block) {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockState(probe).is(block)) return probe.immutable();
		}
		return null;
	}

	private BlockPos findGrindstone() {
		BlockPos base = this.blockPosition();
		for (BlockPos probe : BlockPos.betweenClosed(
				base.offset(-FURNACE_SEARCH, -6, -FURNACE_SEARCH),
				base.offset(FURNACE_SEARCH, 6, FURNACE_SEARCH))) {
			if (level().getBlockState(probe).is(Blocks.GRINDSTONE)) return probe.immutable();
		}
		return null;
	}

	/**
	 * Let the game's own crafting table answer "what does this grid make": the two-item recipes that read
	 * their inputs (repair, dye) are matched and assembled by the recipe manager, not by us.
	 */
	private ItemStack assembleGrid(ServerLevel level, List<ItemStack> items) {
		if (!(level.recipeAccess() instanceof net.minecraft.world.item.crafting.RecipeManager manager)) {
			return ItemStack.EMPTY;
		}
		List<ItemStack> grid = new ArrayList<>(items);
		while (grid.size() < 9) grid.add(ItemStack.EMPTY);
		var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, grid);
		var found = manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level);
		if (found.isEmpty()) return ItemStack.EMPTY;
		ItemStack out = found.get().value().assemble(input);
		return out == null ? ItemStack.EMPTY : out;
	}

	/** A stack the game would consider mending: damageable, of the same item, and actually damaged. */
	private static boolean isRepairable(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.isDamageableItem()
				&& stack.getDamageValue() > 0 && !isBroken(stack);
	}

	/**
	 * Use what he is holding the way a player would: seeds into farmland, bone meal on a crop, a lever,
	 * a button. The block methods that implement all of that take a {@code Player}, so the actor in
	 * {@link com.rishi.cobbie.actor.FakeActor} stands in for one — see that class for what that costs.
	 */
	private ActionOutcome startUse(ActionIntent intent) {
		if (!CobbieConfig.permits("use")) {
			return ActionOutcome.fail("use", "my owner has not allowed building — /cobbie permit building on");
		}
		if (!(level() instanceof ServerLevel)) return ActionOutcome.fail("use", "no server world");
		boolean atBlock = intent.x() != 0 || intent.y() != 0 || intent.z() != 0;
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());

		if (atBlock && !level().isLoaded(pos)) return ActionOutcome.fail("use", "that area is not loaded");
		boolean emptyTarget = intent.target() == null || intent.target().isBlank();
		ItemStack held;
		if (emptyTarget) {
			held = this.getMainHandItem();
		} else if (matchesItem(intent.target().trim(), this.getMainHandItem())) {
			// What he is already holding is something he carries. The resolver searches the bag, and a stack
			// that a previous step raised into his hand is not in the bag — which is how a fishing rod he was
			// holding became "I have no fishing_rod with me".
			held = this.getMainHandItem();
		} else {
			// "use my sharpest book on the anvil": an id and a description resolve through the same rules, and
			// an ambiguous one comes back as a question rather than a guess.
			var resolved = resolveDescribed(intent.target().trim());
			if (!resolved.ok()) {
				return ActionOutcome.fail("use", resolved.refusal());
			}
			int slot = slotOf(resolved.chosen());
			if (slot < 0) {
				// The thing described may be the thing in his hand already, which no bag slot can hold.
				if (!candidateOf(this.getMainHandItem()).equals(resolved.chosen())) {
					return ActionOutcome.fail("use", "I lost track of the " + intent.target().trim());
				}
				held = this.getMainHandItem();
			} else {
				held = inventory.getItem(slot);
				if (held != this.getMainHandItem()) {
					ItemStack hand = this.getMainHandItem();
					if (!hand.isEmpty()) add(hand.copy(), inventory);
					inventory.setItem(slot, ItemStack.EMPTY);
					this.setItemInHand(InteractionHand.MAIN_HAND, held);
				}
			}
		}
		// An empty hand is a legitimate request on a hanging thing — it is how an item comes back out of
		// an item frame — so the "nothing to use" refusal waits until the entity branches have passed.
		boolean aimedAtSomething = !atBlock && intent.data() != null && !intent.data().isBlank();
		if (held.isEmpty() && !atBlock && !aimedAtSomething) {
			return ActionOutcome.fail("use", emptyTarget
					? "I am not holding anything to use"
					: "I have no " + intent.target() + " to use");
		}
		if (held.is(Items.FISHING_ROD)) return ActionOutcome.fail("use", NO_FISHING);
		if (atBlock && this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("use", "nowhere to stand at " + describe(pos));
			beginWalking(intent, stand, pos);
			return null;
		}

		// A player's other right-click: not a block, but a mob. `data` names what to use it on, so
		// shears, a bucket, a lead and everything else that acts on an animal reach the same verb.
		if (!atBlock && intent.data() != null && !intent.data().isBlank()) {
			LivingEntity other = namedEntity(intent.data());
			if (other == null) {
				// Not living, but still something a hand works on: an item frame takes an item and
				// gives it back through exactly the same gesture.
				net.minecraft.world.entity.Entity hanging = namedHanging(intent.data());
				if (hanging != null) return useOnHanging(hanging, held);
				return ActionOutcome.fail("use", "I cannot see a " + intent.data().trim() + " close enough to use this on");
			}
			return useOnEntity(other, held);
		}
		// A bare hand on a block is a real gesture — a lever, a button, a door, a bed — so the empty hand is
		// only a refusal when the intent named an item he does not have. Refusing every empty-handed block
		// use here is what turned a lever into "I am not holding anything to use".
		if (held.isEmpty() && !emptyTarget) {
			return ActionOutcome.fail("use", "I have no " + intent.target() + " to use");
		}
		return useAt(pos, atBlock, intent);
	}

	/** The nearest living thing of a named type that it is sane to point an item at. */
	private LivingEntity namedEntity(String raw) {
		Identifier id = entityId(raw);
		if (id == null) return null;
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null) return null;
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (LivingEntity other : level().getEntitiesOfClass(LivingEntity.class,
				this.getBoundingBox().inflate(ENTITY_USE_REACH))) {
			// Never himself, never the owner, and never another player: there is no item in the game
			// whose use-on-entity behaviour belongs on a person.
			if (other == this || other instanceof Player || !other.isAlive() || other.getType() != type) continue;
			double distance = other.distanceToSqr(this);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = other;
			}
		}
		return best;
	}

	/** "cow", "minecraft:cow" and "cows" all have to land on the same type. */
	private static Identifier entityId(String raw) {
		if (raw == null || raw.isBlank()) return null;
		String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		for (String candidate : List.of(name, name.endsWith("s") ? name.substring(0, name.length() - 1) : name)) {
			try {
				Identifier id = Identifier.parse(candidate.contains(":") ? candidate : "minecraft:" + candidate);
				if (BuiltInRegistries.ENTITY_TYPE.getOptional(id).isPresent()) return id;
			} catch (Exception ignored) {
				// not an id at all
			}
		}
		return null;
	}

	/**
	 * Use what he is holding on another living thing, the way a player right-clicks it.
	 *
	 * <p>A lead does not go through the actor, and the reason came out of a probe:
	 *
	 * <ul>
	 *   <li><b>A lead.</b> Handing one to the actor leashes the animal to a player who is never in
	 *       the world — the probe found the cow held by {@code FakePlayer [Cobbie]}, a ghost standing
	 *       still. Leashed to Cobbie himself the animal walks with him, which is what a lead is for.</li>
	 * </ul>
	 */
	/** The nearest hanging thing of a named type: an item frame now, a painting or armour stand later. */
	private net.minecraft.world.entity.Entity namedHanging(String raw) {
		Identifier id = entityId(raw);
		if (id == null) return null;
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null) return null;
		net.minecraft.world.entity.Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (net.minecraft.world.entity.Entity other : level().getEntitiesOfClass(
				net.minecraft.world.entity.Entity.class, this.getBoundingBox().inflate(ENTITY_USE_REACH))) {
			if (!(other instanceof net.minecraft.world.entity.decoration.HangingEntity)) continue;
			if (other.getType() != type) continue;
			double distance = other.distanceToSqr(this);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = other;
			}
		}
		return best;
	}

	/**
	 * A hand on something that is not alive. An empty hand takes the item back out of a frame; anything
	 * carried goes in. The frame's own state is the receipt, so success is read from it rather than from
	 * the interaction result.
	 */
	private ActionOutcome useOnHanging(net.minecraft.world.entity.Entity target, ItemStack held) {
		String what = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath().replace('_', ' ');
		boolean frame = target instanceof net.minecraft.world.entity.decoration.ItemFrame;
		ItemStack before = frame ? ((net.minecraft.world.entity.decoration.ItemFrame) target).getItem().copy()
				: ItemStack.EMPTY;
		// The probe answered this: an empty right-click does NOT empty an item frame in this version, and
		// the gesture that does is a punch. So an empty hand on a loaded frame takes the item out through
		// the frame's own drop, which is the same result the punch produces.
		if (frame && held.isEmpty() && !before.isEmpty()) {
			net.minecraft.world.entity.decoration.ItemFrame hanging =
					(net.minecraft.world.entity.decoration.ItemFrame) target;
			hanging.dropItem((net.minecraft.server.level.ServerLevel) level(), this);
			if (!hanging.getItem().isEmpty()) {
				return ActionOutcome.fail("use", "the " + what + " would not give up the " + itemId(before));
			}
			collectWithinReach();
			return ActionOutcome.ok("use", "took " + itemId(before) + " out of the " + what,
					ActionEvidence.world("the " + what + " is empty now, and " + itemId(before)
							+ " is not in it any more"));
		}
		com.rishi.cobbie.actor.FakeActor.Outcome outcome;
		try {
			outcome = com.rishi.cobbie.actor.FakeActor.useOnHanging(this, target, held.copy());
		} catch (RuntimeException e) {
			return ActionOutcome.fail("use", "using that on the " + what + " went wrong ("
					+ e.getClass().getSimpleName() + ")");
		}
		if (outcome == null) return ActionOutcome.fail("use", "no server world");
		if (outcome.remaining() != null) {
			this.setItemInHand(InteractionHand.MAIN_HAND,
					outcome.remaining().isEmpty() ? ItemStack.EMPTY : outcome.remaining());
		}
		ItemStack after = frame ? ((net.minecraft.world.entity.decoration.ItemFrame) target).getItem().copy()
				: ItemStack.EMPTY;
		if (ItemStack.isSameItemSameComponents(before, after)) {
			return ActionOutcome.fail("use", "the " + what + " did not change when I used "
					+ (held.isEmpty() ? "an empty hand" : itemId(held)) + " on it");
		}
		collectWithinReach();
		return ActionOutcome.ok("use", after.isEmpty()
				? "took " + (before.isEmpty() ? "nothing" : itemId(before)) + " out of the " + what
				: "put " + itemId(after) + " in the " + what,
				ActionEvidence.world("the " + what + " held " + (before.isEmpty() ? "nothing" : itemId(before))
						+ " and holds " + (after.isEmpty() ? "nothing" : itemId(after)) + " now"));
	}

	private ActionOutcome useOnEntity(LivingEntity other, ItemStack held) {
		if (held.isEmpty()) return ActionOutcome.fail("use", "I am not holding anything to use on it");
		String what = BuiltInRegistries.ENTITY_TYPE.getKey(other.getType()).getPath().replace('_', ' ');

		if (held.is(Items.LEAD)) return leashToSelf(other, what);

		com.rishi.cobbie.actor.FakeActor.Outcome outcome;
		try {
			outcome = com.rishi.cobbie.actor.FakeActor.useOnEntity(this, other, held.copy());
		} catch (RuntimeException e) {
			return ActionOutcome.fail("use", "using that on the " + what + " went wrong ("
					+ e.getClass().getSimpleName() + ")");
		}
		if (outcome == null) return ActionOutcome.fail("use", "no server world");
		ItemStack after = outcome.remaining();
		this.setItemInHand(InteractionHand.MAIN_HAND, after.isEmpty() ? ItemStack.EMPTY : after);
		if (!outcome.result().consumesAction()) {
			return ActionOutcome.fail("use", "nothing happened when I used " + itemId(held) + " on the " + what);
		}
		// Shearing drops wool at the animal's feet rather than into his hand; anything that landed
		// within reach is his, so a shear is not "I sheared it and left the wool".
		collectWithinReach();
		ItemStack inHand = this.getMainHandItem();
		if (ItemStack.isSameItemSameComponents(held, inHand)) {
			// The game says the interaction was spent and nothing about him moved: the shears did not wear,
			// the bucket did not fill, the food was not eaten. There is no world fact here to claim, so this
			// is not a success — it is the same sentence the block path already gives for a wasted click.
			return ActionOutcome.fail("use", "nothing happened when I used " + itemId(held)
					+ " on the " + what + " — my hand is exactly as it was");
		}
		String inHandName = inHand.isEmpty() ? "an empty hand" : itemId(inHand);
		return ActionOutcome.ok("use", "used " + itemId(held) + " on the " + what,
				ActionEvidence.world("my hand went from " + itemId(held) + " to " + inHandName
						+ " using it on the " + what));
	}

	/** Tie an animal to himself, with vanilla leash physics, instead of to a player who is not there. */
	private ActionOutcome leashToSelf(LivingEntity other, String what) {
		if (!(other instanceof Mob mob)) {
			return ActionOutcome.fail("use", "a lead will not go on a " + what);
		}
		if (!mob.canBeLeashed()) return ActionOutcome.fail("use", "the " + what + " will not take a lead");
		if (mob.isLeashed()) return ActionOutcome.fail("use", "the " + what + " is already on a lead");
		mob.setLeashedTo(this, true);
		if (!mob.isLeashed()) return ActionOutcome.fail("use", "the lead would not hold the " + what);
		consumeItem("minecraft:lead", 1);
		return ActionOutcome.ok("use", "put a lead on the " + what + " so it follows me",
				ActionEvidence.world("the " + what + " is on my lead now"));
	}

	/**
	 * Which face of a block a player would be clicking.
	 *
	 * <p>This is not cosmetic: seeds plant on the face they are clicked against, so a horizontal face
	 * sends them nowhere and the crop never appears. Standing at the block with air above it, a player
	 * clicks the top; from any other angle they click the side facing them.
	 */
	private Direction useFace(BlockPos pos) {
		if (level().getBlockState(pos.above()).isAir() && this.getY() > pos.getY() + 0.5) return Direction.UP;
		double dx = this.getX() - (pos.getX() + 0.5);
		double dz = this.getZ() - (pos.getZ() + 0.5);
		if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/**
	 * Raise the item to use: whatever is in his hand if it matches, otherwise the matching stack out of
	 * the bag. A blank target means "whatever I am holding".
	 */
	private ItemStack useItemFor(String target) {
		ItemStack held = this.getMainHandItem();
		if (matchesItem(target, held)) return held;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !matchesItem(target, stack)) continue;
			if (!held.isEmpty()) add(held.copy(), inventory);
			inventory.setItem(i, ItemStack.EMPTY);
			this.setItemInHand(InteractionHand.MAIN_HAND, stack);
			return stack;
		}
		return ItemStack.EMPTY;
	}

	/** Perform the use, put back what came of it, and verify that something actually happened. */
	private ActionOutcome useAt(BlockPos pos, boolean atBlock, ActionIntent intent) {
		ItemStack before = this.getMainHandItem().copy();
		BlockState stateBefore = atBlock ? level().getBlockState(pos) : null;
		ItemStack used = before.isEmpty() ? ItemStack.EMPTY : before.copyWithCount(before.getCount());
		com.rishi.cobbie.actor.FakeActor.Outcome outcome;
		try {
			if (!atBlock) {
				outcome = com.rishi.cobbie.actor.FakeActor.useInAir(this, used, null);
			} else {
				outcome = com.rishi.cobbie.actor.FakeActor.useOnBlock(this, pos, useFace(pos), used);
				// A player's single right-click is block-use first, then the item's own use. A bucket
				// is the case that needs the second half: in this version it raycasts for fluid from
				// the eye rather than using the block it was clicked on.
				if (!outcome.result().consumesAction()) {
					ItemStack again = outcome.remaining().isEmpty() ? used : outcome.remaining();
					com.rishi.cobbie.actor.FakeActor.Outcome second =
							com.rishi.cobbie.actor.FakeActor.useInAir(this, again, pos);
					if (second != null && second.result().consumesAction()) outcome = second;
				}
			}
		} catch (RuntimeException e) {
			return ActionOutcome.fail("use", "using that went wrong (" + e.getClass().getSimpleName() + ")");
		}
		if (outcome == null) return ActionOutcome.fail("use", "no server world");

		// The actor's hand is the truth after the call: spent seeds leave it empty, a bucket leaves
		// the bucket, a damaged tool comes back worn.
		ItemStack after = outcome.remaining();
		this.setItemInHand(InteractionHand.MAIN_HAND, after.isEmpty() ? ItemStack.EMPTY : after);

		boolean blockChanged = atBlock && stateBefore != null && !level().getBlockState(pos).equals(stateBefore);
		boolean handChanged = before.getCount() != after.getCount() || !ItemStack.isSameItemSameComponents(before, after);
		if (!blockChanged && !handChanged && atBlock
				&& level().getBlockState(pos).getMenuProvider(level(), pos) != null) {
			// A chest, a furnace, a table, an anvil: what the game does with a right-click on one is open a
			// screen, and a screen is for a player. The actor opens it into nothing, so "used it" would be
			// a claim about a menu that never appeared — and the verbs that really do these jobs read and
			// write the block itself. Name them instead of pretending.
			return ActionOutcome.fail("use", "a " + blockId(level().getBlockState(pos))
					+ " only opens a menu, which I cannot read — tell me to take from it, put something in"
					+ " it, or use the verb that does the job");
		}
		if (!outcome.result().consumesAction() && !blockChanged && !handChanged) {
			return ActionOutcome.fail("use", "nothing happened when I used "
					+ (before.isEmpty() ? "my hand" : itemId(before)) + " on that");
		}
		String what = before.isEmpty() ? "my hand" : itemId(before);
		String afterName = after.isEmpty() ? "an empty hand" : itemId(after);
		return ActionOutcome.ok("use", "used " + what + (atBlock ? " at " + describe(pos) : ""),
				ActionEvidence.world(blockChanged
						? "the block at " + describe(pos) + " is now " + blockId(level().getBlockState(pos))
						: "my hand went from " + what + " to " + afterName));
	}

	/** How many items he is carrying in total, bag and raised hand together. */
	private int bagTotal() {
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) total += stack.getCount();
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty()) total += hand.getCount();
		return total;
	}

	/**
	 * "oak_planks", "minecraft:oak_planks" and "sticks" all have to find the same item — the model
	 * writes all three, and a plural that resolves to nothing reads as "I have no recipe" to the
	 * owner. A short alias list covers the names people actually say.
	 */
	private static String resolveItemId(String raw) {
		if (raw == null || raw.isBlank()) return null;
		String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (name.equals("planks") || name.equals("plank") || name.equals("wood_planks")) name = "oak_planks";
		if (name.equals("logs") || name.equals("log") || name.equals("wood_log")) name = "oak_log";
		for (String candidate : List.of(name, name.endsWith("s") ? name.substring(0, name.length() - 1) : name)) {
			String full = candidate.contains(":") ? candidate : "minecraft:" + candidate;
			Identifier id;
			try {
				id = Identifier.parse(full);
			} catch (Exception e) {
				continue;
			}
			if (BuiltInRegistries.ITEM.getOptional(id).orElse(null) != null) {
				return BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.getOptional(id).orElseThrow()).toString();
			}
		}
		return null;
	}

	/** How many he should be holding at the end. The data field carries it; 1 when it does not. */
	private static int craftQuantity(String data) {
		if (data == null || data.isBlank()) return 1;
		String digits = data.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) return 1;
		try {
			return Math.max(1, Math.min(64, Integer.parseInt(digits)));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	/** Registry id → how many of it he is carrying, bag and raised hand together. */
	private Map<String, Integer> carriedByItem() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			counts.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty()) counts.merge(itemId(hand), hand.getCount(), Integer::sum);
		return counts;
	}

	/**
	 * Put words on a sign. This is the one piece of the decorative tail that needs code rather than a
	 * hand: vanilla's interaction opens an editing screen for a player, which the actor cannot use, so a
	 * sign can be placed through {@code use} and never written through it.
	 */
	private ActionOutcome startWrite(ActionIntent intent) {
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!level().isLoaded(pos)) return ActionOutcome.fail("write", "that area is not loaded");
		if (!(level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity)) {
			return ActionOutcome.fail("write", "there is no sign at " + describe(pos));
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("write", "nowhere to stand by " + describe(pos));
			beginWalking(intent, stand, pos);
			return null;
		}
		return writeAt(pos, intent.target());
	}

	/**
	 * Write to the sign standing at that square, read now.
	 *
	 * <p>One path for the sign at arm's length and the sign he walked to, so "there is no sign there"
	 * is asked again at the moment of writing. A walked step that never re-read its target is exactly
	 * how a write reported success over a sign somebody else had broken.
	 */
	private ActionOutcome writeAt(BlockPos pos, String text) {
		if (!(level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
			return ActionOutcome.fail("write", "the sign at " + describe(pos) + " is gone now");
		}
		return writeSign(sign, text, pos);
	}

	/** Vanilla's limit per line, and the number of lines a sign has. */
	private static final int SIGN_LINE_WIDTH = 15;
	private static final int SIGN_LINES = 4;

	private ActionOutcome writeSign(net.minecraft.world.level.block.entity.SignBlockEntity sign,
			String text, BlockPos pos) {
		String clean = text == null ? "" : text.replace('§', ' ').replaceAll("\s+", " ").trim();
		if (clean.isEmpty()) return ActionOutcome.fail("write", "there is nothing to write");

		List<String> wrapped = wrapForSign(clean);
		if (wrapped.size() > SIGN_LINES) {
			return ActionOutcome.fail("write", "that text is too long for a sign: it holds "
					+ SIGN_LINES + " lines of " + SIGN_LINE_WIDTH + " and this needs " + wrapped.size());
		}

		var slot = net.minecraft.world.level.block.entity.SignTextSlot.FRONT;
		net.minecraft.world.level.block.entity.SignText current = sign.getText(slot);
		java.util.List<net.minecraft.network.chat.Component> before = current.getMessages(false);
		java.util.List<net.minecraft.network.chat.Component> messages = new ArrayList<>(SIGN_LINES);
		int wrote = 0;
		for (int i = 0; i < SIGN_LINES; i++) {
			net.minecraft.network.chat.Component existing = i < before.size() ? before.get(i) : null;
			boolean empty = existing == null || existing.getString().isBlank();
			if (wrote < wrapped.size() && empty) {
				messages.add(net.minecraft.network.chat.Component.literal(wrapped.get(wrote++)));
			} else {
				messages.add(existing == null ? net.minecraft.network.chat.Component.empty() : existing);
			}
		}
		if (wrote < wrapped.size()) {
			return ActionOutcome.fail("write", "that sign is full — " + (wrapped.size() - wrote)
					+ " line(s) did not fit and I will not overwrite what is there");
		}

		sign.setText(new net.minecraft.world.level.block.entity.SignText(messages, java.util.List.of(),
				current.getColor(), current.hasGlowingText()), slot);
		java.util.List<net.minecraft.network.chat.Component> after = sign.getText(slot).getMessages(false);
		StringBuilder readBack = new StringBuilder();
		for (net.minecraft.network.chat.Component line : after) {
			String said = line.getString();
			if (said.isBlank()) continue;
			if (readBack.length() > 0) readBack.append(' ');
			readBack.append(said);
		}
		String landing = readBack.toString();
		if (landing.isBlank()) return ActionOutcome.fail("write", "the sign did not keep the text");
		return ActionOutcome.ok("write", "wrote \"" + clean + "\" on the sign at " + describe(pos),
				ActionEvidence.world("the sign at " + describe(pos) + " reads \"" + landing + "\""));
	}

	/** Word wrap at vanilla's line width, breaking a long word rather than losing it. */
	static List<String> wrapForSign(String text) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			while (word.length() > SIGN_LINE_WIDTH) {
				if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
				lines.add(word.substring(0, SIGN_LINE_WIDTH));
				word = word.substring(SIGN_LINE_WIDTH);
			}
			if (line.length() == 0) {
				line.append(word);
			} else if (line.length() + 1 + word.length() <= SIGN_LINE_WIDTH) {
				line.append(' ').append(word);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	private ActionOutcome startPlace(ActionIntent intent) {
		BlockPos pos = new BlockPos(intent.x(), intent.y(), intent.z());
		String refusal = placementRefusal(intent.target(), pos);
		if (refusal != null) return ActionOutcome.fail("place", refusal);
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			BlockPos stand = standingSpotNear(pos);
			if (stand == null) return ActionOutcome.fail("place", "nowhere to stand to build at " + describe(pos));
			double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
			if (this.blockPosition().distSqr(stand) > navLimit * navLimit) {
				return ActionOutcome.fail("place", "that spot is too far away to reach");
			}
			beginWalking(intent, stand, pos);
			return null;
		}
		return placeBlock(intent.target(), pos);
	}

	/**
	 * Validated in the order the mission demands: permissions (checked by the caller), border,
	 * loaded chunk, reach, replacement rules, support, collision, materials.
	 */
	private String placementRefusal(String blockName, BlockPos pos) {
		return placementRefusal(blockName, pos, false);
	}

	/**
	 * @param supportComing the support for this block is one his own plan will place first, so the
	 *     question "is there something underneath" is answered by a step he has already committed to
	 *     rather than by the world as it stands
	 */
	private String placementRefusal(String blockName, BlockPos pos, boolean supportComing) {
		if (blockName == null || blockName.isBlank()) return "no block name given";
		BlockState wanted = resolveState(blockName, pos);
		if (wanted == null) return "I do not know a block called " + blockName;
		if (!level().isLoaded(pos)) return "that area is not loaded";
		if (level() instanceof ServerLevel sl && !sl.getWorldBorder().isWithinBounds(pos)) {
			return "that is outside the world border";
		}
		if (this.blockPosition().distSqr(pos) > reachSquared()) {
			return null; // caller walks him closer first; reach is not a refusal
		}
		if (this.blockPosition().equals(pos) || this.blockPosition().equals(pos.above())) {
			return "I would be standing there";
		}
		BlockState existing = level().getBlockState(pos);
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return describe(pos) + " is occupied by " + blockId(existing);
		}
		if (!supportComing && !NO_SUPPORT_NEEDED.contains(wanted.getBlock())) {
			BlockPos below = pos.below();
			if (!level().isLoaded(below)) return "the ground under " + describe(pos) + " is not loaded";
			if (level().getBlockState(below).isAir()) return "nothing to support a block at " + describe(pos);
		}
		List<LivingEntity> occupants = level().getEntitiesOfClass(LivingEntity.class, new AABB(pos),
				e -> e.isAlive() && e != this);
		if (!occupants.isEmpty()) return "something is standing in " + describe(pos);
		return null;
	}

	/**
	 * Whether he could actually build here, judged by the rules {@link #placeBlock} applies plus the
	 * material in his bag — so a project he announces is one he can start.
	 */
	public boolean canPlace(String blockName, BlockPos pos) {
		return canPlace(blockName, pos, false);
	}

	/**
	 * Whether he could build this as a later step of a plan of his own, where the block under it is one
	 * the plan places first. Occupancy and bodies still count; support is his own promise, not the
	 * world's.
	 */
	public boolean canPlaceOnOwn(String blockName, BlockPos pos) {
		return canPlace(blockName, pos, true);
	}

	private boolean canPlace(String blockName, BlockPos pos, boolean supportComing) {
		if (placementRefusal(blockName, pos, supportComing) != null) return false;
		BlockState wanted = resolveState(blockName, pos);
		return wanted != null
				&& countItem(BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).toString()) >= 1;
	}

	/** How many of a block's item he is carrying, for a project that plans to spend them. */
	public int countOf(String blockName) {
		Block block = resolveBlock(blockName);
		return block == null ? 0 : countItem(BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	/**
	 * Place one block the way a player places it: a right-click on a face, with the game deciding the
	 * blockstate (see {@link com.rishi.cobbie.actor.FakeActor#placeAt}).
	 *
	 * <p>What the report rests on is the state read back at the square, properties included. A stair
	 * that faces the wrong way, a slab on the wrong half and a torch lying on the floor instead of
	 * hung on the wall are all "the block is there", and only the state tells them apart — so this is
	 * the one place in the mod where a placement counts itself done by comparing blockstates. The
	 * material is consumed only AFTER the state is verified.
	 */
	private ActionOutcome placeBlock(String blockName, BlockPos pos) {
		String refusal = placementRefusal(blockName, pos);
		if (refusal != null) return ActionOutcome.fail("place", refusal);

		Block block = resolveBlock(blockName);
		if (block == null) return ActionOutcome.fail("place", "I do not know a block called " + blockName);
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("place", "no server world");

		String itemId = BuiltInRegistries.BLOCK.getKey(block).toString();
		if (countItem(itemId) < 1) return ActionOutcome.fail("place", "I have no " + itemId + " with me");

		BlockState before = serverLevel.getBlockState(pos);
		com.rishi.cobbie.actor.FakeActor.Placed placed =
				com.rishi.cobbie.actor.FakeActor.placeAt(this, serverLevel, pos, new ItemStack(block.asItem(), 1));
		BlockState now = serverLevel.getBlockState(pos);
		if (placed == null || !placed.placed() || now.isAir() || now.equals(before)) {
			return ActionOutcome.fail("place", "the game would not put " + itemId + " at " + describe(pos)
					+ " from where I am standing, and " + describe(pos) + " reads " + stateText(now));
		}
		consumeItem(itemId, 1);
		int held = countItem(itemId);
		return ActionOutcome.ok("place", "placed " + stateText(now) + " at " + describe(pos),
				ActionEvidence.world("the world reads " + stateText(now) + " at " + describe(pos)
						+ ", and my bag holds " + held + " of " + itemId + " now"));
	}

	/** A blockstate as the world holds it: the block's id and every property it carries. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String stateText(BlockState state) {
		StringBuilder sb = new StringBuilder(blockId(state));
		var properties = state.getProperties();
		if (!properties.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (net.minecraft.world.level.block.state.properties.Property<?> property : properties) {
				if (!first) sb.append(',');
				first = false;
				sb.append(property.getName()).append('=')
						.append(((net.minecraft.world.level.block.state.properties.Property) property)
								.getName(state.getValue((net.minecraft.world.level.block.state.properties.Property) property)));
			}
			sb.append(']');
		}
		return sb.toString();
	}

	/** Orientation is not decided here any more: the game answers it from the click (see {@link #placeBlock}). */
	private BlockState resolveState(String blockName, BlockPos pos) {
		Block block = resolveBlock(blockName);
		return block == null ? null : block.defaultBlockState();
	}

	private Block resolveBlock(String blockName) {
		if (blockName == null || blockName.isBlank()) return null;
		String name = blockName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		String namespace = "minecraft";
		if (name.contains(":")) {
			String[] parts = name.split(":", 2);
			namespace = parts[0];
			name = parts[1];
		}
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(namespace, name))
				.orElse(null);
		return (block == null || block == Blocks.AIR) ? null : block;
	}

	// ---- collection ----

	private ActionOutcome startCollect(ActionIntent intent) {
		ItemEntity nearest = nearestCollectable();
		if (nearest == null) return ActionOutcome.fail("collect", "nothing to pick up around here");
		BlockPos at = nearest.blockPosition();
		if (this.blockPosition().distSqr(at) <= reachSquared()) return collectAt(at);
		double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
		if (this.blockPosition().distSqr(at) > navLimit * navLimit) {
			return ActionOutcome.fail("collect", "that item is too far away");
		}
		beginWalking(intent, at, at);
		return null;
	}

	private ActionOutcome collectAt(BlockPos pos) {
		List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class,
				new AABB(pos).inflate(2.0), ItemEntity::isAlive);
		int bagBefore = bagTotal();
		int stacks = 0;
		for (ItemEntity item : items) {
			ItemStack world = item.getItem();
			if (world.isEmpty()) continue;
			if (skippedItems.contains(item.getUUID())) continue;
			int before = world.getCount();
			ItemStack trial = world.copy();
			if (add(trial, inventory)) {
				int leftover = trial.getCount();      // what did not fit stays on the ground
				world.shrink(before - leftover);
				if (world.isEmpty()) item.discard();
				stacks++;
			} else {
				skippedItems.add(item.getUUID());
			}
		}
		if (stacks == 0) return ActionOutcome.fail("collect", "my bag has no room at " + describe(pos));
		int gained = bagTotal() - bagBefore;
		if (gained <= 0) {
			return ActionOutcome.fail("collect", "my bag did not grow at " + describe(pos)
					+ ", so nothing came off the ground");
		}
		return ActionOutcome.ok("collect", "picked up " + stacks + " stack(s) at " + describe(pos),
				ActionEvidence.world("my bag grew by " + gained + " item(s), and " + stacks
						+ " stack(s) are gone from the ground at " + describe(pos)));
	}

	/** What is already within arm's reach. No wandering, no pathfinding, no spamming. */
	private void collectWithinReach() {
		List<ItemEntity> nearby = level().getEntitiesOfClass(ItemEntity.class,
				new AABB(this.blockPosition()).inflate(1.6), ItemEntity::isAlive);
		for (ItemEntity item : nearby) {
			ItemStack world = item.getItem();
			if (world.isEmpty() || skippedItems.contains(item.getUUID())) continue;
			int before = world.getCount();
			ItemStack trial = world.copy();
			if (add(trial, inventory)) {
				int taken = before - trial.getCount();
				world.shrink(taken);
				if (world.isEmpty()) item.discard();
				if (taken > 0) {
					String id = itemId(item.getItem());
					if (lastPickupTick - pickupReportTick > 40) {
						pickupReportTick = lastPickupTick;
						pickedUpSinceReport += taken;
						pickedUpIds.add(id);
					}
				}
			} else {
				skippedItems.add(item.getUUID());
				return; // bag full; stop trying every tick
			}
		}
	}

	private ItemEntity nearestCollectable() {
		double scan = Math.max(4.0, CobbieConfig.getInteractionReach() * 2.0);
		List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class,
				new AABB(this.blockPosition()).inflate(scan), ItemEntity::isAlive);
		ItemEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (ItemEntity item : items) {
			if (item.getItem().isEmpty() || skippedItems.contains(item.getUUID())) continue;
			double d = item.distanceToSqr(this);
			if (d < bestDist) {
				bestDist = d;
				best = item;
			}
		}
		if (!skippedItems.isEmpty() && this.tickCount % 600 == 0) skippedItems.clear();
		return best;
	}

	// ---- combat ----

	/**
	 * The one rule for "which of these is the real threat", stated here once and used by both the
	 * defensive goal and the attack verb:
	 *
	 * <ol>
	 *   <li>one that is coming for the owner outranks one that is coming for him — the owner is who he
	 *       is there for;</li>
	 *   <li>then the nearer one: the closer hostile is the one whose next blow lands soonest;</li>
	 *   <li>then the harder hitter, by the game's own attack-damage figure;</li>
	 *   <li>then the more wounded, so a fight ends instead of spreading thin.</li>
	 * </ol>
	 *
	 * <p>Distance is compared in quarter-block buckets so two hostiles at the same stance tie on it
	 * rather than on two floats that came out a bit apart.
	 */
	private LivingEntity realThreat(List<LivingEntity> candidates) {
		LivingEntity best = null;
		for (LivingEntity e : candidates) {
			if (best == null || outranksAsThreat(e, best)) best = e;
		}
		return best;
	}

	private boolean outranksAsThreat(LivingEntity challenger, LivingEntity incumbent) {
		int byTier = threatTier(challenger) - threatTier(incumbent);
		if (byTier != 0) return byTier < 0;
		long bucket = Math.round(Math.sqrt(this.distanceToSqr(challenger)) * 4.0);
		long bestBucket = Math.round(Math.sqrt(this.distanceToSqr(incumbent)) * 4.0);
		if (bucket != bestBucket) return bucket < bestBucket;
		double damage = attackDamageOf(challenger);
		double bestDamage = attackDamageOf(incumbent);
		if (damage != bestDamage) return damage > bestDamage;
		return challenger.getHealth() < incumbent.getHealth();
	}

	/** 0: coming for the owner; 1: coming for him; 2: hostile in sight but not yet engaged. */
	private int threatTier(LivingEntity e) {
		if (e instanceof Mob mob) {
			ServerPlayer owner = owner();
			if (owner != null && mob.getTarget() == owner) return 0;
			if (mob.getTarget() == this) return 1;
		}
		return 2;
	}

	/** What the game says this hostile hits for. */
	private static double attackDamageOf(LivingEntity e) {
		AttributeInstance damage = e.getAttribute(Attributes.ATTACK_DAMAGE);
		return damage == null ? 0.0 : damage.getValue();
	}

	/**
	 * The hostile worth fighting right now: one already coming for him or for the owner, and one he
	 * can see. A wall is a wall — swinging at what is behind it is the flailing this rule removes —
	 * so a hostile with no line of sight is not a defensive target at all. (An owner who names a
	 * target gets to send him around the corner; that is {@link #chooseAttackTarget}.)
	 */
	LivingEntity findThreatToDefendAgainst() {
		if (!CobbieConfig.isCombatAllowed()) return null;
		List<LivingEntity> around = level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(16.0),
				e -> e.isAlive() && isLegalTarget(e) && threatTier(e) <= 1 && this.hasLineOfSight(e));
		return realThreat(around);
	}

	/** The combat safety rules, in exactly one place. */
	boolean isLegalTarget(LivingEntity e) {
		if (e == null || !e.isAlive() || e == this) return false;
		if (e instanceof Player p && isOwner(p)) return false;   // never the owner
		if (e instanceof Player) {
			return CobbieConfig.isPlayerCombatAllowed();     // players need their own separate switch
		}
		// Never a tamed pet, whoever tamed it: it belongs to someone, and a companion who kills a
		// wolf because it is a mob has taken something that was not his to take.
		if (e instanceof OwnableEntity pet && pet.getOwner() != null) return false;
		if (!CobbieConfig.isCombatAllowed()) return false;
		return e instanceof Enemy;                           // hostile mobs only; passives are untouchable
	}

	// ---- disengaging ----

	/**
	 * Is he falling back from a fight right now?
	 *
	 * <p>Asked by the melee goal and the defence goal, which is the half of this that keeps him from
	 * trading blows while he runs: a retreat that the attack goal ignores is a retreat that never happens.
	 */
	public boolean isRetreating() {
		return this.retreatUntilTick > this.tickCount;
	}

	/**
	 * Below a fraction of his health, with a hostile engaging him, he breaks off and falls back to his
	 * owner.
	 *
	 * <p>A companion who stands and trades blows until he dies is a companion you watch die. The fraction
	 * is a setting because how cautious he should be is the owner's call, and the default is deliberately
	 * low so he is not forever running from a scratch.
	 *
	 * <p>What "engaging" means is the game's own answer: an {@link Enemy} within twelve blocks whose
	 * {@code getTarget()} is him or his owner. Not "something hostile is nearby" — a zombie walking past
	 * is not a fight — and not gated on the combat permission, because being hit does not need permission.
	 */
	private void disengageTick() {
		if (isRetreating()) {
			driveRetreat();
			return;
		}
		if (this.tickCount < retreatCooldownUntil) return;
		double fraction = CobbieConfig.getRetreatHealthFraction();
		if (fraction <= 0) return;
		if (this.getHealth() >= this.getMaxHealth() * fraction) return;
		LivingEntity threat = engagingThreat();
		if (threat == null) return;

		boolean stoppedThePlan = cancelTask("i am too badly hurt to keep fighting");
		interrupt();
		this.setTarget(null);
		this.retreatUntilTick = this.tickCount + RETREAT_TICKS;
		this.retreatCooldownUntil = this.tickCount + RETREAT_COOLDOWN_TICKS;
		String line = "i am badly hurt, so i am falling back to you";
		if (!sayToOwner(line)) speakNearby(line);
		narrator.noteSpokenAt(System.currentTimeMillis());
		Cobbie.LOGGER.info("Cobbie disengaged at {} of {} health from {} (plan {})", (int) this.getHealth(),
				(int) this.getMaxHealth(), threat.getName().getString(),
				stoppedThePlan ? "cancelled" : "none in flight");
		driveRetreat();
	}

	/**
	 * A shield he carries, raised while something hostile is close enough to hit him in the next few
	 * seconds.
	 *
	 * <p>The block itself is the game's: {@code LivingEntity.applyItemBlocking} reads the shield's own
	 * {@code BLOCKS_ATTACKS} component and decides how much of the blow it stops, including the
	 * requirement that the blow arrives from in front of his head — so he also turns to face it. Two
	 * limits are stated rather than hidden: the shield takes the component's block delay (five ticks
	 * for a shield) to come up, so a blow in those five ticks is not blocked, and it only covers what
	 * arrives inside the component's horizontal angle.
	 */
	private void shieldTick() {
		if (level() == null || level().isClientSide()) return;
		if (!CobbieConfig.isCombatAllowed()) {
			lowerShield();
			return;
		}
		LivingEntity threat = nearestEngagingThreatWithin(SHIELD_THREAT_RANGE);
		if (threat == null || !raiseShieldIfCarried()) {
			lowerShield();
			return;
		}
		// The game blocks only what comes from in front of his head, so the raise is also a turn.
		this.getLookControl().setLookAt(threat, 30.0f, 30.0f);
		if (!this.isUsingItem()) this.startUsingItem(InteractionHand.OFF_HAND);
	}

	private void lowerShield() {
		if (this.isUsingItem() && this.getUsedItemHand() == InteractionHand.OFF_HAND) this.stopUsingItem();
	}

	/** Put a shield in his off hand if he carries one there or in the bag. */
	private boolean raiseShieldIfCarried() {
		if (this.getOffhandItem().is(Items.SHIELD)) return true;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.is(Items.SHIELD)) continue;
			ItemStack previous = this.getOffhandItem().copy();
			ItemStack taken = stack.copyWithCount(1);
			ItemStack rest = stack.copy();
			rest.shrink(1);
			inventory.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
			this.setItemSlot(EquipmentSlot.OFFHAND, taken);
			if (!previous.isEmpty()) addItem(previous);
			return true;
		}
		return false;
	}

	/** The nearest hostile within {@code range} that is coming for him or for his owner, and visible. */
	private LivingEntity nearestEngagingThreatWithin(double range) {
		LivingEntity best = null;
		double bestDist = range * range;
		for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(range),
				e -> e.isAlive() && isLegalTarget(e) && threatTier(e) <= 1 && this.hasLineOfSight(e))) {
			double d = e.distanceToSqr(this);
			if (d <= bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	/** The nearest hostile that is actually coming for him or for his owner. */
	private LivingEntity engagingThreat() {
		ServerPlayer owner = owner();
		LivingEntity best = null;
		double bestDist = RETREAT_THREAT_RANGE * RETREAT_THREAT_RANGE;
		for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(RETREAT_THREAT_RANGE), LivingEntity::isAlive)) {
			if (e == this || !(e instanceof Enemy) || !(e instanceof Mob mob)) continue;
			if (mob.getTarget() != this && (owner == null || mob.getTarget() != owner)) continue;
			double d = e.distanceToSqr(this);
			if (d > bestDist) continue;
			bestDist = d;
			best = e;
		}
		return best;
	}

	/**
	 * Put distance between himself and the fight, toward the owner.
	 *
	 * <p>Bounded twice: he stops when he is back at his owner's side, and he stops after
	 * {@link #RETREAT_TICKS} whatever happens. A retreat with no end is a companion who wandered off.
	 */
	private void driveRetreat() {
		if (!isRetreating()) return;
		ServerPlayer owner = owner();
		boolean withOwner = owner != null && owner.level() == this.level()
				&& this.distanceToSqr(owner) <= Math.pow(Math.max(2.0, CobbieConfig.getFollowDistance()), 2);
		if (withOwner) {
			endRetreat();
			return;
		}
		if (this.tickCount - followRepathTick < FOLLOW_REPATH_INTERVAL) return;
		followRepathTick = this.tickCount;
		if (owner != null && owner.level() == this.level()) {
			getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 1.2);
			return;
		}
		// Nobody to fall back to: break off anyway. Standing in melee range at a third of his health is
		// the one thing this behaviour exists to stop; without an owner the nearest thing to a retreat is
		// the square furthest from whatever is hitting him.
		LivingEntity threat = engagingThreat();
		if (threat == null) return;
		BlockPos away = standingSpotNear(this.blockPosition().offset(
				Integer.signum(this.blockPosition().getX() - threat.blockPosition().getX()) * 4, 0,
				Integer.signum(this.blockPosition().getZ() - threat.blockPosition().getZ()) * 4));
		if (away != null) getNavigation().moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 1.2);
	}

	private void endRetreat() {
		this.retreatUntilTick = 0;
		if (!isBusy()) getNavigation().stop();
	}

	// ---- falling ----

	/**
	 * The deepest drop the pathfinder may plan him down.
	 *
	 * <p>The game's own answer is {@code floor(health + 3)} — its "comfortable fall distance" — and by the
	 * game's own damage arithmetic that is a fall costing exactly his health: at 20 health it will plan a
	 * 23-block drop and 20 damage, which is the last fall he ever takes. This asks for the deepest fall
	 * that leaves him standing instead, so no path can walk him off a ledge that kills him. Pathing prices
	 * lava and fire already; this is the number it never had.
	 */
	@Override
	public int getMaxFallDistance() {
		float health = this.getHealth();
		if (health != fallLimitForHealth) {
			fallLimitForHealth = health;
			fallLimit = Falls.safeDrop(health, this::fallDamage);
		}
		return fallLimit;
	}

	/** What the game says a fall of this many blocks costs him. */
	private double fallDamage(double distance) {
		return this.calculateFallDamage(distance, 1.0f);
	}

	/** One level of a column of the world, for {@link Falls}. */
	private Falls.Floor floorAt(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (!level().isLoaded(pos)) return Falls.Floor.SOLID;   // an unloaded column is not somewhere to fall
		BlockState state = level().getBlockState(pos);
		if (state.getFluidState().is(FluidTags.LAVA)) return Falls.Floor.LAVA;
		if (!state.getCollisionShape(level(), pos).isEmpty()) return Falls.Floor.SOLID;
		if (!state.getFluidState().isEmpty()) return Falls.Floor.WATER;
		return Falls.Floor.AIR;
	}

	/**
	 * What happens to his body if the block under his feet turns to air. Null when the answer is "he is
	 * still standing", otherwise the sentence that refuses the dig.
	 *
	 * <p>The column is described as it will be <em>after</em> the break, which is why the mined block
	 * itself reads as air here: the question is not what is there now, it is what he would land in.
	 */
	private String fatalDigNote(BlockPos floor) {
		BlockPos feet = this.blockPosition();
		Falls.Below below = Falls.below(feet.getY(), level().getMinY(),
				y -> y == floor.getY() ? Falls.Floor.AIR : floorAt(feet.getX(), y, feet.getZ()));
		if (!Falls.fatal(below, this.getHealth(), this::fallDamage)) return null;
		return switch (below.landing()) {
			case LAVA -> "I will not break the block under my feet — it would drop me straight into lava";
			case VOID -> "I will not break the block under my feet — there is nothing below it but the void";
			case WATER -> null;   // water breaks a fall; this arm is unreachable, and saying so is cheaper than lying
			case GROUND -> "I will not break the block under my feet — it is a " + (int) below.distance()
					+ "-block drop and that would cost me " + (int) fallDamage(below.distance())
					+ " of my " + (int) this.getHealth() + " health";
		};
	}

	/**
	 * Why a target below him is out of walking reach: how far below, and what a fall of that far would cost
	 * him, in the game's own damage.
	 *
	 * <p>It says the geometry and the price, not "there is no staircase" — nothing here can see one. What it
	 * can see is that the pathfinder refused a route to a target this far beneath him, and that a drop of
	 * that size costs more health than he has left. That is the reason the refusal is honest to give.
	 */
	private String dropNote(BlockPos target) {
		int drop = this.blockPosition().getY() - target.getY();
		if (drop <= 3) return "";   // the game's first three blocks are free
		double damage = fallDamage(drop);
		if (damage < this.getHealth()) return "";
		return " (it is " + drop + " blocks below me, and a drop of that far costs " + (int) damage
				+ " of my " + (int) this.getHealth() + " health)";
	}

	private ActionOutcome startAttack(ActionIntent intent) {
		if (!CobbieConfig.isCombatAllowed()) return ActionOutcome.fail("attack", "combat is switched off");
		LivingEntity target = chooseAttackTarget(intent.target());
		if (target == null) return ActionOutcome.fail("attack", "nothing I am allowed to fight is nearby");
		this.setTarget(target);
		hunted = target.getUUID();
		huntedName = target.getName().getString();
		active = intent;
		activeTarget = target.blockPosition();
		navigatingForAction = false;
		activeDeadlineTick = this.tickCount + STEP_DEADLINE_TICKS;
		return null; // resolves across ticks in attackTick()
	}

	/**
	 * The hostile an attack was sent for, chosen by the same threat rule as a defensive fight: type
	 * hint first when one was named, then the real threat among what matches.
	 *
	 * <p>Line of sight decides between candidates: among several hostiles, one behind a wall is not
	 * the pick while a visible one is on offer. It is not required of the only candidate — walking
	 * around a corner to the thing the owner pointed at is the job — but it is required before any
	 * swing ({@link #trySwingAt}).
	 */
	private LivingEntity chooseAttackTarget(String typeHint) {
		double scan = Math.max(8, CobbieConfig.getNavigationRange());
		String hint = typeHint == null ? "" : typeHint.trim().toLowerCase(Locale.ROOT);
		List<LivingEntity> around = level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(scan), e -> {
					if (!isLegalTarget(e) || !canAttack(e)) return false;
					if (hint.isEmpty()) return true;
					String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()
							.toLowerCase(Locale.ROOT);
					return id.contains(hint);
				});
		if (around.isEmpty()) return null;
		List<LivingEntity> visible = around.stream().filter(this::hasLineOfSight).toList();
		return realThreat(visible.isEmpty() ? around : visible);
	}

	/** Chase and hit, revalidating the target immediately before every swing. */
	private ActionOutcome attackTick() {
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive()) {
			this.setTarget(null);
			clearActive();
			// Asked of the world, not of the field: the thing he was fighting either is not alive or is not
			// there any more, and which of those it is decides what he says he ended.
			net.minecraft.world.entity.Entity last = hunted == null || !(level() instanceof ServerLevel world)
					? null : world.getEntity(hunted);
			String name = target != null ? target.getName().getString()
					: huntedName == null ? "it" : huntedName;
			if (last != null && last.isAlive()) {
				interrupt();
				return ActionOutcome.fail("attack", "i lost track of the " + name + " — it is still alive");
			}
			return ActionOutcome.ok("attack", "nothing left to fight",
					ActionEvidence.world(last == null
							? "the " + name + " is not in the world any more"
							: "the " + name + " is not alive"));
		}
		if (!isLegalTarget(target)) {
			this.setTarget(null);
			clearActive();
			return ActionOutcome.fail("attack", "that target is off-limits");
		}
		if (isRetreating()) {
			// The retreat owns his feet while it runs, and trading blows during it is the death it
			// exists to avoid. The wound itself cancels this plan; this is the belt to that brace,
			// so no swing can land on the tick the wound arrives.
			this.setTarget(null);
			clearActive();
			return ActionOutcome.fail("attack", "i am too badly hurt to keep fighting");
		}
		if (trySwingAt(target)) {
			if (target.isAlive()) {
				// "Kill that zombie" used to end after the first swing landed. A fight is not one
				// hit, and a companion who stops mid-fight makes the owner finish his work.
				return null;
			}
			ActionOutcome outcome = ActionOutcome.ok("attack", "hit " + target.getName().getString()
					+ " and finished it off",
					ActionEvidence.world(target.getName().getString() + " is not alive"));
			this.setTarget(null);
			clearActive();
			return outcome;
		}
		if (!this.getAttackRangeWith(this.getMainHandItem())
				.isInRange(this, target.getBoundingBox(), 0.0)) {
			getNavigation().moveTo(target, 0.95);
		}
		return null;
	}

	// ==================================================================
	// Inventory: registry-ID identity, stacking, nothing silently lost.
	// The held tool is a bag slot that has been raised, so counts include it.
	// ==================================================================

	public Container getInventoryContainer() {
		return inventory;
	}

	private String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** Try to store {@code stack} in {@code target}, mutating what did not fit. */
	private static boolean add(ItemStack stack, Container target) {
		if (stack == null || stack.isEmpty()) return true;
		int size = target.getContainerSize();
		for (int i = 0; i < size && !stack.isEmpty(); i++) {
			ItemStack existing = target.getItem(i);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
			int space = existing.getMaxStackSize() - existing.getCount();
			if (space <= 0) continue;
			int move = Math.min(space, stack.getCount());
			existing.grow(move);
			stack.shrink(move);
		}
		for (int i = 0; i < size && !stack.isEmpty(); i++) {
			if (!target.getItem(i).isEmpty()) continue;
			ItemStack moved = stack.split(Math.min(stack.getMaxStackSize(), stack.getCount()));
			if (moved.isEmpty()) return stack.isEmpty();
			target.setItem(i, moved);
		}
		return stack.isEmpty();
	}

	/** Would all of these fit without anything being destroyed? */
	private boolean fitsInInventory(List<ItemStack> drops) {
		if (drops == null || drops.isEmpty()) return true;
		Container ghost = new SimpleContainer(inventory.getContainerSize());
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ghost.setItem(i, inventory.getItem(i).copy());
		}
		boolean all = true;
		for (ItemStack drop : drops) {
			if (drop.isEmpty()) continue;
			if (!add(drop.copy(), ghost)) all = false;
		}
		return all;
	}

	public boolean addItem(ItemStack stack) {
		return add(stack, inventory);
	}

	// ==================================================================
	// Hunger — the game's numbers, and what a person can see of them
	// ==================================================================

	/** Food points, 0..{@code FoodConstants.MAX_FOOD}. */
	public int foodLevel() {
		return hunger.foodLevel();
	}

	public float saturationLevel() {
		return hunger.saturation();
	}

	public float exhaustionLevel() {
		return hunger.exhaustion();
	}

	/** One line with all three numbers, for `/cobbie status` and his panel. */
	public String hungerLine() {
		return hunger.describe();
	}

	/** Eat the worst food he carries; false when the game would refuse or he has none. */
	public boolean eatSomething() {
		return hunger.tryEat();
	}

	/** Milk for a harmful effect, a healing potion when badly hurt; false when neither applies. */
	public boolean drinkSomething() {
		return hunger.tryDrink();
	}

	/** Whether the game's own rule lets him sprint at the food he has right now. */
	public boolean canSprintNow() {
		return hunger.maySprint();
	}

	/** What the actor behind his hunger actually sees, for a probe when the predicate disagrees. */
	public String hungerDebug() {
		return hunger.debugSprint();
	}

	/** Put the bar where a test or a debug command needs it. The game still owns what happens next. */
	public void setHunger(int food, float saturation) {
		hunger.set(food, saturation);
	}

	/** Charge exhaustion through the game's own method, exposed so a test can isolate one action. */
	public void addExhaustion(float amount) {
		hunger.addExhaustion(amount);
	}

	ItemStack bagStack(int slot) {
		return inventory.getItem(slot);
	}

	void setBagStack(int slot, ItemStack stack) {
		inventory.setItem(slot, stack == null ? ItemStack.EMPTY : stack);
	}

	int bagSize() {
		return inventory.getContainerSize();
	}

	/**
	 * The game's own sprint rule, enforced on him: a player below
	 * {@code FoodConstants.SPRINT_LEVEL} food cannot sprint, and neither can he.
	 */
	@Override
	public void setSprinting(boolean sprinting) {
		if (sprinting && !hunger.maySprint()) {
			super.setSprinting(false);
			return;
		}
		super.setSprinting(sprinting);
	}

	/** A jump costs what the game charges a player for one, sprint jump included. */
	@Override
	public void jumpFromGround() {
		super.jumpFromGround();
		hunger.jumped();
	}

	/**
	 * Damage costs the exhaustion its own source names — the same call and the same number
	 * {@code Player.hurtServer} applies — so starvation, fire and a mob's claws cost what they cost.
	 */
	@Override
	public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source,
			float amount) {
		boolean hurt = super.hurtServer(level, source, amount);
		if (amount > 0 && source != null) hunger.damaged(source);
		return hurt;
	}

	public int countItem(String registryId) {
		if (registryId == null) return 0;
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && registryId.equalsIgnoreCase(itemId(stack))) count += stack.getCount();
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty() && registryId.equalsIgnoreCase(itemId(hand))) count += hand.getCount();
		return count;
	}

	/** Consume {@code amount} of a registry id, taking from the bag then the hand. */
	public boolean consumeItem(String registryId, int amount) {
		if (amount <= 0) return true;
		if (countItem(registryId) < amount) return false;
		int remaining = amount;
		for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !registryId.equalsIgnoreCase(itemId(stack))) continue;
			int take = Math.min(stack.getCount(), remaining);
			stack.shrink(take);
			remaining -= take;
			if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
		}
		if (remaining > 0) {
			ItemStack hand = this.getMainHandItem();
			if (!hand.isEmpty() && registryId.equalsIgnoreCase(itemId(hand))) {
				int take = Math.min(hand.getCount(), remaining);
				hand.shrink(take);
				remaining -= take;
				if (hand.isEmpty()) this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			}
		}
		return remaining == 0;
	}

	public String describeInventory() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(itemId(stack)).append(" x").append(stack.getCount());
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append("(holding ").append(itemId(hand)).append(" x").append(hand.getCount()).append(')');
		}
		return sb.length() == 0 ? "empty" : sb.toString();
	}

	/**
	 * The tool he could raise for this block, without raising it — so that a promise about a block and
	 * the attempt at it are judged by one rule instead of two that can drift apart.
	 */
	private ItemStack bestToolFor(BlockState state) {
		ToolChoice.Pick pick = chooseTool(state);
		return pick.found() ? toolAt(pick.slot()) : ItemStack.EMPTY;
	}

	/**
	 * The tool to raise for this block, decided by {@link ToolChoice} out of what the game says about each
	 * tool and out of how much of the job is left. Separate from raising it so that a promise about a block
	 * and the attempt at it are judged by one rule instead of two that can drift apart.
	 */
	private ToolChoice.Pick chooseTool(BlockState state) {
		List<ToolChoice.Candidate> tools = new ArrayList<>();
		ItemStack held = this.getMainHandItem();
		if (!held.isEmpty()) tools.add(toolCandidate(ToolChoice.HAND, held, state));
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) tools.add(toolCandidate(i, stack, state));
		}
		return ToolChoice.choose(tools, blocksLeftInJob(state));
	}

	/** One row of the bag as the tool question sees it — the game's answer, not a table of names. */
	private ToolChoice.Candidate toolCandidate(int slot, ItemStack stack, BlockState state) {
		int maxDamage = stack.getMaxDamage();
		// A tool with no durability at all never wears out; everything the game ships for mining does.
		int usesLeft = maxDamage <= 0 ? Integer.MAX_VALUE : maxDamage - stack.getDamageValue();
		return new ToolChoice.Candidate(slot, itemId(stack), stack.isCorrectToolForDrops(state), maxDamage, usesLeft);
	}

	/** The stack in a slot {@link ToolChoice} named: {@link ToolChoice#HAND}, or a bag slot. */
	private ItemStack toolAt(int slot) {
		return slot == ToolChoice.HAND ? this.getMainHandItem() : inventory.getItem(slot);
	}

	/**
	 * How many blocks of this kind the job still has to break, which is what a tool has to last. One step is
	 * one block, so the answer is the mine steps still pending in the plan; a step that named no block (a
	 * tunnel, where every step is "break what is in front of me") counts, and one that named a different
	 * block does not.
	 */
	private int blocksLeftInJob(BlockState state) {
		TaskPlan plan = tasks.plan();
		if (plan == null) return 1;
		String here = blockId(state);
		int left = 0;
		for (TaskStep step : plan.steps()) {
			if (step.status() == TaskStep.Status.DONE || step.status() == TaskStep.Status.SKIPPED) continue;
			if (!ActionVocabulary.MINE.equals(step.intent().action())) continue;
			String named = step.intent().target();
			if (named == null || named.isBlank()) {
				left++;
				continue;
			}
			Block block = resolveBlock(named);
			if (block != null && blockId(block.defaultBlockState()).equals(here)) left++;
		}
		return Math.max(1, left);
	}

	/**
	 * What stops him <b>getting anything</b> out of this block, or null when the drop is his. This is
	 * the gate a project must pass, because a project promises to gather: stone bare-handed leaves a hole
	 * and nothing in the bag, so it is not worth announcing.
	 */
	private String dropRefusal(BlockState state, String id, float hardness) {
		// The drop question and nothing else: a log gives itself up to bare hands, stone does not.
		return bestToolFor(state).isEmpty() && state.requiresCorrectToolForDrops()
				? "I need a proper tool for " + id + " and have none"
				: null;
	}

	/**
	 * What stops him <b>breaking</b> this block, or null when a swing will do it. This is the gate an
	 * owner's instruction must pass, and it is a weaker question on purpose: if the owner wants the block
	 * gone — a tunnel through stone, a wall opened — an empty-handed break that yields nothing is the
	 * thing he asked for, and the report says nothing dropped. Conflating the two refused "dig through
	 * that stone" for want of a pickaxe nobody needed. Hands still do not take obsidian to pieces.
	 */
	private String breakRefusal(BlockState state, String id, float hardness) {
		// "Can my hands do it at all", which depends on the WORLD's game mode — not on the autonomy setting.
		// CobbieConfig.isCreative() is the autonomy mode, and using it here meant a creative world with ASSISTIVE
		// autonomy refused to break an oak log bare-handed: a predicate answering a different question, again.
		boolean slowByHand = hardness > 1.5f && !worldIsCreative();
		return slowByHand && bestToolFor(state).isEmpty()
				? "I need a proper tool for " + id + " and have none"
				: null;
	}

	/**
	 * Whether he could actually start on this block, judged by the rules the step itself will apply:
	 * the drop gate, somewhere to stand, and room for what comes out. An announced project that cannot
	 * take its first step is a promise made before the check.
	 */
	public boolean canStartMining(BlockPos pos) {
		if (!(level() instanceof ServerLevel serverLevel) || !serverLevel.isLoaded(pos)) return false;
		BlockState state = serverLevel.getBlockState(pos);
		if (state.isAir() || PROTECTED.contains(state.getBlock())) return false;
		float hardness = state.getDestroySpeed(serverLevel, pos);
		if (hardness < 0) return false;
		String id = blockId(state);
		if (dropRefusal(state, id, hardness) != null) return false;
		if (pos.equals(this.blockPosition().below()) && fatalDigNote(pos) != null) return false;
		if (standingSpotNear(pos) == null) return false;
		return fitsInInventory(Block.getDrops(state, serverLevel, pos, null, this, bestToolFor(state)));
	}

	// ==================================================================
	// What the opportunity assessor may read — and nothing else
	// ==================================================================
	//
	// The assessor is deterministic and lives outside this class, so it can only ask questions the body
	// already answers for itself. These are those questions, one line each: the threat rule, the loose
	// item, the ripe crops, whether a walk would start, how the bag is shaped, and what he is carrying in
	// words. Adding a second opinion to any of them here is how the two copies drift apart.

	/** The hostile already coming for him or his owner, or null. The defensive pick, in one place. */
	public LivingEntity threatToDefendAgainst() {
		return findThreatToDefendAgainst();
	}

	/** A hostile in sight that is not coming for anyone yet: worth going after, never urgent. */
	public LivingEntity idleHostileNear(int radius) {
		if (!CobbieConfig.isCombatAllowed()) return null;
		List<LivingEntity> around = level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(radius),
				e -> e.isAlive() && isLegalTarget(e) && threatTier(e) == 2 && this.hasLineOfSight(e));
		return realThreat(around);
	}

	/**
	 * The threat situation, as a state rather than a tally: <b>0</b> nothing, <b>1</b> something already
	 * coming for him or the owner, <b>2</b> something hostile merely in sight, <b>3</b> both at once.
	 *
	 * <p>A count is the wrong fingerprint for "a hostile arrived": a second zombie walking into view of
	 * the first changes a number and changes nothing about what he should do.
	 */
	public int threatsNear(int radius) {
		if (!CobbieConfig.isCombatAllowed()) return 0;
		boolean engaging = false;
		boolean sighted = false;
		for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(radius),
				e -> e.isAlive() && isLegalTarget(e) && this.hasLineOfSight(e))) {
			if (threatTier(e) <= 1) engaging = true;
			else sighted = true;
		}
		return engaging && sighted ? 3 : engaging ? 1 : sighted ? 2 : 0;
	}

	/** The nearest loose item he could walk to and pick up, or null when the ground is empty. */
	public ItemEntity nearestLooseItem() {
		return nearestCollectable();
	}

	/** How many crops within reach are ready to take. */
	public int ripeCropsNear(int radius) {
		if (!(level() instanceof ServerLevel serverLevel)) return 0;
		BlockPos feet = blockPosition();
		int ripe = 0;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
			BlockState state = serverLevel.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop && crop.isMaxAge(state)) {
				ripe++;
			}
		}
		return ripe;
	}

	/**
	 * Whether a walk there would start: loaded, inside the verb's own range, room for his body, and a
	 * route the pathfinder can actually find. The same gate the step applies, asked before it is queued —
	 * an offered walk that the verb would refuse is a promise made before the check.
	 */
	public boolean canWalkTo(BlockPos pos) {
		if (!canStandAt(pos)) return false;
		double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
		if (blockPosition().distSqr(pos) > navLimit * navLimit) return false;
		if (blockPosition().closerThan(pos, 1.5)) return true;
		return getNavigation().createPath(pos, 1) != null;
	}

	/**
	 * Whether his body has room at this square — loaded, and the two blocks a companion occupies are free.
	 *
	 * <p>The half of a walk's gate that does not depend on where he is standing now, which is what a leg he
	 * has not reached yet can honestly be checked for.
	 */
	public boolean canStandAt(BlockPos pos) {
		if (pos == null || !(level() instanceof ServerLevel serverLevel) || !serverLevel.isLoaded(pos)) return false;
		return com.rishi.cobbie.world.BodySpace.fits(this::solidAt, pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * The bag and the raised hand, with every count in powers of two.
	 *
	 * <p>Coarse on purpose: the fingerprint only has to change when <b>what he could do</b> changes. Three
	 * logs and four logs are the same plan; a log and a pickaxe are not. A raw count would wake the model
	 * every time he picked a flower, which is the poll this cadence exists to avoid.
	 */
	public int bagFingerprint() {
		int hash = 17;
		hash = 31 * hash + itemId(getMainHandItem()).hashCode();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) continue;
			hash = 31 * hash + itemId(stack).hashCode();
			hash = 31 * hash + (32 - Integer.numberOfLeadingZeros(Math.max(1, stack.getCount())));
		}
		return hash;
	}

	/** What he is carrying, in words, for the line the model reads beside the shortlist. */
	public String carriedSummary() {
		java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) continue;
			counts.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		ItemStack hand = this.getMainHandItem();
		if (!hand.isEmpty()) counts.merge(itemId(hand) + " (in hand)", hand.getCount(), Integer::sum);
		if (counts.isEmpty()) return "nothing";
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (var entry : counts.entrySet()) {
			if (shown++ == 8) {
				sb.append(", ...");
				break;
			}
			if (sb.length() > 0) sb.append(", ");
			sb.append(shortId(entry.getKey())).append(" x").append(entry.getValue());
		}
		return sb.toString();
	}

	/** The tick anyone last asked him for anything. */
	public int lastOwnerContactTick() {
		return lastOwnerContactTick;
	}

	private static String shortId(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	private ItemStack equipBestToolFor(BlockState state) {
		ToolChoice.Pick pick = chooseTool(state);
		// Said before anything moves: the note names the tool that was passed over, which is still in the bag.
		noteToolChoice(pick);
		ItemStack held = this.getMainHandItem();
		if (pick.slot() == ToolChoice.HAND) return held; // already holding the right one
		// A tool in the hand when another is wanted goes back in the bag rather than nowhere.
		if (isTool(held)) {
			add(held.copy(), inventory);
			this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
		if (!pick.found()) return ItemStack.EMPTY;
		ItemStack chosen = inventory.getItem(pick.slot());
		inventory.setItem(pick.slot(), ItemStack.EMPTY);
		this.setItemInHand(InteractionHand.MAIN_HAND, chosen);
		return chosen;
	}

	/**
	 * Say which tool he is wearing and why, when the choice is worth a word: a better tool was passed over
	 * because it could not finish the job, rather than the cheapest one being the obvious answer. Once per
	 * sentence per plan, because a tunnel of forty blocks is one remark and not forty.
	 */
	private void noteToolChoice(ToolChoice.Pick pick) {
		String line = switch (pick.note()) {
			case STEPPED_DOWN -> "using the " + toolWords(toolAt(pick.slot())) + " — the "
					+ toolWords(toolAt(pick.passedOver()))
					+ (toolUsesLeft(toolAt(pick.passedOver())) < 10 ? " is nearly spent" : " has not enough left for this");
			case LAST_RESORT -> "the " + toolWords(toolAt(pick.slot()))
					+ " is the only one i have for this and it is nearly spent";
			case NONE, MISSING -> null;
		};
		if (line == null) return;
		String key = tasks.latestPlanId() + "|" + line;
		if (key.equals(toolNoteKey)) return;
		toolNoteKey = key;
		if (!sayToOwner(line)) speakNearby(line);
		narrator.noteSpokenAt(System.currentTimeMillis());
	}

	private static int toolUsesLeft(ItemStack stack) {
		return stack == null || stack.isEmpty() ? 0 : stack.getMaxDamage() - stack.getDamageValue();
	}

	/** "iron pickaxe": what a person calls the thing, out of the id the game gave it. */
	private static String toolWords(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "tool";
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		int colon = id.indexOf(':');
		return (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ');
	}

	private static boolean isTool(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& stack.has(net.minecraft.core.component.DataComponents.TOOL);
	}

	private static boolean isBroken(ItemStack stack) {
		return stack.isDamageableItem() && stack.getMaxDamage() > 0
				&& stack.getDamageValue() >= stack.getMaxDamage();
	}

	private void wearTool(ItemStack tool) {
		if (tool == null || tool.isEmpty() || !tool.isDamageableItem()) return;
		try {
			String name = toolWords(tool);
			tool.hurtAndBreak(1, this, InteractionHand.MAIN_HAND);
			if (isBroken(tool)) {
				tool.shrink(1);
				if (tool.isEmpty()) this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				// A tool that just gave out is said out loud: a companion whose pickaxe vanished mid-tunnel
				// without a word is one you stop lending tools to.
				if (!sayToOwner("my " + name + " just gave out")) speakNearby("my " + name + " gave out");
			}
		} catch (Exception e) {
			Cobbie.LOGGER.debug("Cobbie tool wear skipped: {}", e.toString());
		}
	}

	// ==================================================================
	// Helpers
	// ==================================================================

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String describe(BlockPos pos) {
		return pos == null ? "?" : (pos.getX() + " " + pos.getY() + " " + pos.getZ());
	}

	/** A distance for a sentence the owner reads: one decimal, never 3.0000000000000004. */
	private static String oneDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** A free block next to the target, with ground under it, that he can stand on and reach. */
	private BlockPos standingSpotNear(BlockPos target) {
		BlockPos[] candidates = {
				target.east(), target.west(), target.north(), target.south(),
				target.east().above(), target.west().above(), target.north().above(), target.south().above(),
				target.above(),
				target.below().east(), target.below().west(), target.below().north(), target.below().south()
		};
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos feet : candidates) {
			BlockPos ground = feet.below();
			if (!level().isLoaded(feet) || !level().isLoaded(ground)) continue;
			if (level().getBlockState(ground).isAir()) continue;      // needs something to stand on
			if (!level().getBlockState(feet).isAir()) continue;        // needs headroom
			if (!level().getBlockState(feet.above()).isAir()) continue;
			double d = this.blockPosition().distSqr(feet);
			if (d < bestDist) {
				bestDist = d;
				best = feet;
			}
		}
		return best;
	}

	// ==================================================================
	// Persistence
	// ==================================================================

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putBoolean("CobbieFollowing", following);
		out.putString("CobbieName", companionName());
		out.putInt("CobbieXpLevel", experienceLevel);
		out.putFloat("CobbieXpProgress", experienceProgress);
		// Written blank when nothing is in flight, so a marker can never outlive the project it names.
		out.putString("CobbieInterrupted", interruptedPlan);
		// The game's own four food fields, written by FoodData's own save method, beside the bag.
		hunger.save(out.child("CobbieFood"));
		if (ownerUuid != null) {
			out.putLong("CobbieOwnerMost", ownerUuid.getMostSignificantBits());
			out.putLong("CobbieOwnerLeast", ownerUuid.getLeastSignificantBits());
		}
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			items.add(inventory.getItem(i).copy());
		}
		try {
			out.store("CobbieItems", ITEM_LIST, items);
		} catch (Exception e) {
			Cobbie.LOGGER.warn("Cobbie could not save his bag: {}", e.toString());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		following = in.getBooleanOr("CobbieFollowing", true);
		this.entityData.set(DATA_NAME, com.rishi.cobbie.identity.CompanionName
				.orDefault(in.getStringOr("CobbieName", com.rishi.cobbie.identity.CompanionName.DEFAULT)));
		// The plate is rebuilt from the name that was just restored, so the visible plate, the panel and
		// the prompt cannot disagree after a load regardless of the order vanilla reads CustomName in.
		this.setCustomName(net.minecraft.network.chat.Component
				.literal("§e" + companionName() + " §8· §7" + statusWord()));
		// A level he earned is his: death does not take it back, unlike a player's.
		experienceLevel = Math.max(0, in.getIntOr("CobbieXpLevel", 0));
		experienceProgress = Math.max(0.0f, in.getFloatOr("CobbieXpProgress", 0.0f));
		// Food, saturation, the tick timer and exhaustion, read back by the game's own method. An older
		// save has no such child, and FoodData's defaults are a full bar — which is what he spawned with.
		in.child("CobbieFood").ifPresent(hunger::load);

		// A project does not survive a reload: its plan, its steps and where it had got to were memory.
		// The marker says one was running, so he owes the owner a line about it — spoken on a tick, not
		// here, because this runs before he is in a world and before anyone is there to hear it.
		interruptedPlan = in.getStringOr("CobbieInterrupted", "");
		wakeLinePending = !interruptedPlan.isBlank();

		Optional<Long> most = in.getLong("CobbieOwnerMost");
		Optional<Long> least = in.getLong("CobbieOwnerLeast");
		if (most.isPresent() && least.isPresent()) {
			ownerUuid = new UUID(most.get(), least.get());
		}

		try {
			List<ItemStack> items = in.read("CobbieItems", ITEM_LIST).orElse(List.of());
			int slots = inventory.getContainerSize();
			for (int i = 0; i < slots; i++) {
				ItemStack stack = i < items.size() ? items.get(i) : null;
				inventory.setItem(i, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
			}
			// The bag shrank since that save (a config change), so anything past the last slot would
			// be deleted on load. It goes on the ground at his feet instead, once he is in a world.
			for (int i = slots; i < items.size(); i++) {
				ItemStack extra = items.get(i);
				if (extra != null && !extra.isEmpty()) pendingSpill.add(extra.copy());
			}
			if (!pendingSpill.isEmpty()) {
				StringBuilder what = new StringBuilder();
				int total = 0;
				for (ItemStack stack : pendingSpill) {
					total += stack.getCount();
					if (what.length() > 0) what.append(", ");
					what.append(stack.getCount()).append("x ")
							.append(BuiltInRegistries.ITEM.getKey(stack.getItem()));
				}
				Cobbie.LOGGER.warn("Cobbie's bag holds {} slots but that save has more: {} ({}) will be"
						+ " put on the ground beside him rather than lost", slots, total, what);
			}
		} catch (Exception e) {
			Cobbie.LOGGER.warn("Cobbie's saved bag could not be read: {}", e.toString());
		}
	}
}
