package com.rishi.cobbie.entity;

import com.mojang.serialization.Codec;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.craft.CraftChain;
import com.rishi.cobbie.craft.CraftRecipes;
import com.rishi.cobbie.net.CobbiePanelPayload;
import com.rishi.cobbie.presence.Narrator;
import com.rishi.cobbie.presence.Sight;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskRunner;
import com.rishi.cobbie.task.TaskStep;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

	private static final int WAVE_TICKS = 40;
	private static final int STEP_DEADLINE_TICKS = 1200;  // 60s per step, then it is abandoned
	private static final double ARRIVE_RADIUS = 1.5;      // "I have walked there", not "I can touch it"
	private static final int WALK_GRACE_TICKS = 10;       // let the navigator compute before judging it
	private static final int MAX_WALK_REPATHS = 3;         // then repair a dropped path, a few times
	private static final int FOLLOW_REPATH_INTERVAL = 12; // the pathfinder is not free
	private static final int PICKUP_INTERVAL = 20;
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

	// ---- swimming ----
	private static final double SWIM_PUSH = 0.055;         // per-tick horizontal thrust in water
	private static final double SWIM_MAX_HORIZONTAL = 0.30; // never faster than a sprinting player
	private static final double SWIM_RISE = 0.06;           // buoyancy toward the surface
	private static final double SWIM_MAX_RISE = 0.16;
	private static final int CRAFT_CHAIN_DEPTH = 6;
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

	private static final List<String> TOOL_WORDS =
			List.of("pickaxe", "axe", "shovel", "hoe", "shears", "sword");
	private static final List<String> TIER_RANK = List.of(
			"netherite", "diamond", "iron", "stone", "copper", "golden", "wooden", "wood", "leather");

	/** Saved with the entity. ItemStack.OPTIONAL_CODEC tolerates empty slots. */
	private static final Codec<List<ItemStack>> ITEM_LIST = ItemStack.OPTIONAL_CODEC.listOf();

	// ---- companion state (persisted) ----
	private UUID ownerUuid;
	private boolean following = true;

	// ---- inventory (the bag; a held tool is a bag slot that has been raised) ----
	private final Container inventory;

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
	private int pickupReportTick;    // last time a pickup was folded into what he tells the model
	private int pickedUpSinceReport;
	private final Set<String> pickedUpIds = new HashSet<>();
	private ActionOutcome pendingOutcome;

	// ---- bookkeeping ----
	private int waveTicksLeft;
	private int followRepathTick;
	private int lastPickupTick;
	private int attackCooldownUntil;
	private String lastActionResult = "nothing yet";
	/** His voice: one short line per step, at most one every chat cooldown. */
	private final Narrator narrator = new Narrator();
	private int lastObservationTick;
	/** Where he stood when the current plan started — the origin skill steps are stored relative to. */
	private BlockPos planOrigin;
	/** Items he already failed to take, so he does not claw at them every tick. */
	private final Set<UUID> skippedItems = new HashSet<>();

	// ---- recall (the last resort, off by default) ----
	private BlockPos recallSamplePos;
	private int recallSampleTick;
	/** Consecutive far re-paths the pathfinder refused; each one shortens the next step. */
	private int followMisses;

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

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.ATTACK_DAMAGE, 3.0)
				// A player's swings are paced by the weapon: a sword ~12 ticks, an axe ~20. Without
				// this attribute a mob cannot even read the number, so every swing was a flat 16.
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
		this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
		// Deliberately absent: FollowMobGoal (it would trail whoever was nearest, not the owner)
		// and any unrestricted NearestAttackableTargetGoal (a combat-permission bypass).
		this.targetSelector.addGoal(1, new OwnerDefenseGoal(this));
	}

	/** Melee chasing that refuses to run while combat is switched off. */
	private static final class GatedMeleeAttack extends MeleeAttackGoal {
		private final CobbieEntity cobbie;

		GatedMeleeAttack(CobbieEntity cobbie) {
			super(cobbie, 0.9, true);
			this.cobbie = cobbie;
		}

		@Override
		public boolean canUse() {
			return CobbieConfig.isCombatAllowed() && cobbie.getTarget() != null && super.canUse();
		}

		@Override
		public boolean canContinueToUse() {
			return CobbieConfig.isCombatAllowed() && cobbie.getTarget() != null && super.canContinueToUse();
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
		return offerPlan(request, intents, false);
	}

	/**
	 * Add work to the errand he is already on, nearest first. "Get coal while you are down there"
	 * should not mean walking back: among the things just asked for, he does the closest one next.
	 *
	 * @return null when merged, otherwise why it could not be
	 */
	public String offerAddition(String request, List<ActionIntent> intents) {
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
		String refusal = tasks.start(request, intents, urgent);
		if (refusal == null) planOrigin = this.blockPosition().immutable();
		return refusal;
	}

	/** The plan he is running, or the one that most recently finished (for reporting / learning). */
	public com.rishi.cobbie.task.TaskPlan lastPlan() {
		return tasks.plan();
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

	/** One-shot "here is what actually happened" text when a plan settles. */
	public String takeTaskReport() {
		return tasks.takeReport();
	}

	public boolean isBusy() {
		return tasks.hasActiveTask() || active != null;
	}

	public boolean cancelTask(String reason) {
		return tasks.cancel(reason);
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

		if (waveTicksLeft > 0 && --waveTicksLeft == 0) {
			this.entityData.set(DATA_WAVING, false);
		}

		updateNameplate();
		followOwnerTick();
		swimTick();

		ActionOutcome settled = driveActiveStep();
		if (settled != null) pendingOutcome = settled;

		tasks.tick(System.currentTimeMillis());

		// Push the finished plan's verified report to the brain (chat, memory, skill credit,
		// learning, and the model's own feedback all hang off that single delivery).
		String report = tasks.takeReport();
		if (report != null) {
			com.rishi.cobbie.task.TaskPlan settledPlan = tasks.plan();
			com.rishi.cobbie.presence.ActionTiming.settled(System.currentTimeMillis(),
					settledPlan == null ? 0 : settledPlan.steps().size());
			com.rishi.cobbie.brain.CobbieBrain.reportPlanSettled(this, settledPlan);
		}

		flushNarration();
		observeWhenIdle();

		// Pick up what is already within arm's reach. Quietly, no project attached.
		if (CobbieConfig.isCollectionAllowed() && this.tickCount - lastPickupTick >= PICKUP_INTERVAL) {
			lastPickupTick = this.tickCount;
			collectWithinReach();
		}

		// Own initiative: creative mode only, idle, allowed to explore, and not asked to stay.
		if (!isBusy() && !following && pendingOutcome == null
				&& CobbieConfig.isCreative() && CobbieConfig.permits("explore")
				&& this.random.nextInt(400) == 0) {
			exploreSomewhereNearby();
		}
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
		if (!text.startsWith("§eCobbie")) return;
		String target = "§eCobbie §8· §7" + statusWord();
		if (!target.equals(text)) {
			this.setCustomName(Component.literal(target));
			this.setCustomNameVisible(true);
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
		// carrying nothing while mining.
		ItemStack held = this.getMainHandItem();
		if (!held.isEmpty()) {
			boolean known = items.stream().anyMatch(s -> !s.isEmpty() && ItemStack.isSameItemSameComponents(s, held));
			if (!known) items.add(held.copy());
		}
		return new CobbiePanelPayload(this.getId(), "Cobbie", statusWord(), job, steps, states, items);
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
		// While a task is walking him somewhere, following must not repath him back mid-job. Any
		// other kind of plan (talking, waving, a queued single step) must not freeze his feet.
		if (navigatingForAction) return;
		ServerPlayer owner = owner();
		if (owner == null) return; // offline or in another dimension: wait where he is

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
		if (!level().isLoaded(owner.blockPosition())) return;

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
		if (!ActionVocabulary.ANSWER.equals(verb) && !ActionVocabulary.WAIT.equals(verb)) {
			com.rishi.cobbie.presence.ActionTiming.firstStep(System.currentTimeMillis());
		}

		return switch (verb) {
			case ActionVocabulary.ANSWER, ActionVocabulary.WAIT -> ActionOutcome.ok(verb, "nothing to do");
			case ActionVocabulary.CHAT -> speak(intent.target());
			case ActionVocabulary.WAVE -> {
				wave();
				yield ActionOutcome.ok(verb, "waved hello");
			}
			case ActionVocabulary.FOLLOW -> {
				startFollowing();
				yield ActionOutcome.ok(verb, "following you again");
			}
			case ActionVocabulary.STAY -> {
				stopFollowing();
				yield ActionOutcome.ok(verb, "staying put");
			}
			case ActionVocabulary.EXPLORE -> {
				exploreSomewhereNearby();
				yield ActionOutcome.ok(verb, "wandering a little");
			}
			case ActionVocabulary.WALK_TO -> startWalk(intent);
			case ActionVocabulary.LIGHT -> startPlace(new ActionIntent(ActionVocabulary.PLACE,
					"minecraft:torch", intent.x(), intent.y(), intent.z(), ""));
			case ActionVocabulary.GIVE -> giveToOwner(intent);
			case ActionVocabulary.DROP -> dropFromBag(intent);
			case ActionVocabulary.EQUIP -> equipFromBag(intent);
			case ActionVocabulary.CRAFT -> craftFromBag(intent);
			case ActionVocabulary.MINE -> startMine(intent);
			case ActionVocabulary.PLACE -> startPlace(intent);
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
		active = null;
		activeTarget = null;
		workTarget = null;
		navigatingForAction = false;
		pendingOutcome = null;
		if (level() != null && !level().isClientSide()) {
			getNavigation().stop();
			this.setTarget(null);
		}
	}

	/** Advance the in-flight step. Non-null return means "this step has settled". */
	private ActionOutcome driveActiveStep() {
		if (active == null) return null;
		String verb = active.action();

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
				interrupt();
				return ActionOutcome.fail(verb, "could not get close enough to " + describe(activeTarget));
			}
			return null; // still walking
		}

		// Phase 2: multi-tick verbs.
		if (ActionVocabulary.ATTACK.equals(verb)) return attackTick();

		interrupt();
		return ActionOutcome.ok(verb, "done");
	}

	/** Reached the target position: do the thing we walked there for. */
	private ActionOutcome performNow() {
		String verb = active.action();
		BlockPos work = workTarget != null ? workTarget : activeTarget;
		return switch (verb) {
			case ActionVocabulary.WALK_TO -> {
				clearActive();
				yield ActionOutcome.ok(verb, "arrived at " + describe(work));
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
			default -> {
				clearActive();
				yield ActionOutcome.ok(verb, "arrived");
			}
		};
	}

	private void clearActive() {
		active = null;
		activeTarget = null;
		workTarget = null;
		navigatingForAction = false;
	}

	/** How long this weapon makes him wait between swings, the way it paces a player. */
	private int swingDelayTicks() {
		double speed = 4.0;
		try {
			speed = this.getAttributeValue(Attributes.ATTACK_SPEED);
		} catch (RuntimeException ignored) {
			// An unregistered attribute throws; the default is a bare-handed pace.
		}
		return Math.max(4, (int) Math.round(20.0 / Math.max(0.5, speed)));
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
		getNavigation().moveTo(standAt.getX(), standAt.getY(), standAt.getZ(), 0.9);
	}

	// ---- individual verbs ----

	private ActionOutcome speak(String text) {
		if (text == null || text.isBlank()) return ActionOutcome.fail("chat", "nothing to say");
		String line = text.length() > 200 ? text.substring(0, 200) : text;
		speakNearby(line);
		narrator.noteSpokenAt(System.currentTimeMillis());
		return ActionOutcome.ok("chat", "said: " + line);
	}

	/** In-world speech: heard by whoever is standing nearby, never a dimension-wide broadcast. */
	private void speakNearby(String line) {
		if (line == null || line.isBlank() || level() == null) return;
		String text = line.length() > 200 ? line.substring(0, 200) : line;
		for (Player p : level().getEntitiesOfClass(Player.class,
				new AABB(this.blockPosition()).inflate(32.0))) {
			p.sendSystemMessage(Component.literal("§e[Cobbie] §f" + text));
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
		double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
		if (EXPLORE_WALK.equals(intent.data())) {
			navLimit = Math.max(navLimit, CobbieConfig.getExplorationRadius());
		}
		if (this.blockPosition().distSqr(target) > navLimit * navLimit) {
			return ActionOutcome.fail("walk-to", describe(target) + " is further than I will walk ("
					+ (int) navLimit + " blocks)");
		}
		if (this.blockPosition().closerThan(target, 1.5)) {
			return ActionOutcome.ok("walk-to", "already at " + describe(target));
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

		String id = blockId(state);
		float hardness = state.getDestroySpeed(serverLevel, pos);
		if (hardness < 0) return ActionOutcome.fail("mine", id + " cannot be broken");

		ItemStack tool = equipBestToolFor(id);
		if (hardness > 1.5f && tool.isEmpty()) {
			return ActionOutcome.fail("mine", "I need a proper tool for " + id + " and have none");
		}

		// Loot comes from the game with the tool actually in hand. Never fabricated.
		List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, null, this, tool);

		if (fitsInInventory(drops)) {
			if (!serverLevel.destroyBlock(pos, false, this, 3)) {
				return ActionOutcome.fail("mine", "could not break " + id);
			}
			int stored = 0;
			for (ItemStack drop : drops) {
				if (!drop.isEmpty()) stored += drop.getCount();
			}
			for (ItemStack drop : drops) {
				add(drop.copy(), inventory);
			}
			wearTool(tool);
			if (!serverLevel.getBlockState(pos).isAir()) {
				return ActionOutcome.fail("mine", id + " is still standing at " + describe(pos));
			}
			return ActionOutcome.ok("mine", "mined " + id + " at " + describe(pos)
					+ (stored > 0 ? ", stored " + stored + " item(s)" : " (nothing dropped)"));
		}

		// Not enough room: drop them where they fall rather than deleting the player's goods.
		if (!serverLevel.destroyBlock(pos, true, this, 3)) {
			return ActionOutcome.fail("mine", "could not break " + id);
		}
		wearTool(tool);
		return ActionOutcome.ok("mine", "broke " + id + " at " + describe(pos)
				+ ", but my bag is full so the items are on the ground");
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
	private ActionOutcome equipFromBag(ActionIntent intent) {
		String wanted = intent.target() == null ? "" : intent.target().trim();

		ItemStack chosen = ItemStack.EMPTY;
		for (int i = 0; i < inventory.getContainerSize() && chosen.isEmpty(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) continue;
			if (matchesItem(wanted, stack)) {
				chosen = stack;
			}
		}
		if (chosen.isEmpty()) {
			return ActionOutcome.fail("equip", wanted.isEmpty() ? "I have nothing to put on" : "I have no " + wanted);
		}

		EquipmentSlot slot = EquipmentSlot.MAINHAND;
		var equippable = chosen.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable != null && equippable.slot() != null) slot = equippable.slot();

		String id = itemId(chosen);
		ItemStack toWear = chosen.copyWithCount(1);
		ItemStack previous = this.getItemBySlot(slot).copy();

		if (!consumeItem(id, 1)) return ActionOutcome.fail("equip", "I could not take the " + id + " out of my bag");
		this.setItemSlot(slot, toWear);
		if (!previous.isEmpty()) addItem(previous);

		ItemStack now = this.getItemBySlot(slot);
		if (now.isEmpty() || !now.is(toWear.getItem())) {
			// Put the world back the way it was: a failed attempt must not cost an item.
			if (!toWear.isEmpty()) addItem(toWear);
			this.setItemSlot(slot, previous);
			return ActionOutcome.fail("equip", "the game would not let me wear the " + id);
		}
		return ActionOutcome.ok("equip", "equipped " + id + " in " + slot.getName() + ", verified");
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
		return ActionOutcome.ok("drop", "dropped " + count + " " + id + " at " + describe(at));
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
		if (!consumeItem(id, 1)) return ActionOutcome.fail("give", "I could not let go of the " + id);
		if (!owner.getInventory().add(gift)) {
			addItem(gift);
			return ActionOutcome.fail("give", "your bag is full, so I kept the " + id);
		}
		return ActionOutcome.ok("give", "gave you 1 " + id);
	}

	/**
	 * Make something out of what he is carrying, through the game's own recipe table.
	 *
	 * <p>The chain is resolved before anything is spent: if the result needs planks, and the planks
	 * need logs, every step is known and accounted for first ({@link CraftChain}). Then each step is
	 * run in that order and the bag is read back afterwards, so "I crafted it" is a count that went
	 * up, not a call that returned.
	 */
	private ActionOutcome craftFromBag(ActionIntent intent) {
		if (!CobbieConfig.isCraftingAllowed()) return ActionOutcome.fail("craft", "crafting is switched off");
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("craft", "no server world");

		String wanted = resolveItemId(intent.target());
		if (wanted == null) {
			String asked = intent.target() == null ? "" : intent.target().trim();
			return ActionOutcome.fail("craft", asked.isEmpty()
					? "tell me what to craft"
					: "there is no item called " + asked);
		}
		int quantity = craftQuantity(intent.data());

		Map<String, CraftRecipes.Match> byRecipe = new LinkedHashMap<>();
		CraftChain.Book book = itemId -> {
			List<CraftChain.Recipe> recipes = new ArrayList<>();
			for (CraftRecipes.Match match : CraftRecipes.allFor(serverLevel, itemId)) {
				byRecipe.put(match.recipe().id(), match);
				recipes.add(match.recipe());
			}
			return recipes;
		};
		CraftChain.Plan plan = CraftChain.plan(wanted, quantity, book, carriedByItem(), CRAFT_CHAIN_DEPTH);
		if (!plan.possible()) return ActionOutcome.fail("craft", plan.failure());
		if (plan.steps().isEmpty()) {
			return ActionOutcome.ok("craft", "I already have " + countItem(wanted) + " " + wanted);
		}

		int before = countItem(wanted);
		int stored = 0;
		Map<String, Integer> spilled = new LinkedHashMap<>();
		for (CraftChain.Step step : plan.steps()) {
			for (String ingredient : step.consumes()) {
				if (!consumeItem(ingredient, 1)) {
					return ActionOutcome.fail("craft", "I ran out of " + ingredient + " while making " + wanted);
				}
			}
			CraftRecipes.Match match = byRecipe.get(step.recipeId());
			ItemStack made = match.output().copyWithCount(match.output().getCount() * step.runs());
			ItemStack leftover = made.copy();
			if (add(leftover, inventory)) stored += made.getCount() - leftover.getCount();
			if (!leftover.isEmpty()) {
				// A full bag must not delete what he just made; it goes on the ground at his feet.
				ItemEntity pile = new ItemEntity(level(), getX(), getY() + 0.4, getZ(), leftover);
				pile.setDefaultPickUpDelay();
				if (!level().addFreshEntity(pile)) {
					return ActionOutcome.fail("craft", "my bag is full and I could not put " + step.result()
							+ " on the ground either");
				}
				spilled.merge(step.result(), leftover.getCount(), Integer::sum);
			}
		}

		int expected = 0;
		for (CraftChain.Step step : plan.steps()) {
			if (step.result().equals(wanted)) expected += step.produced();
		}
		int gained = countItem(wanted) - before;
		int dropped = spilled.getOrDefault(wanted, 0);
		if (gained + dropped != expected) {
			return ActionOutcome.fail("craft", "my bag says " + gained + " new " + wanted
					+ " but the recipes should have made " + expected);
		}
		StringBuilder chain = new StringBuilder();
		for (CraftChain.Step step : plan.steps()) {
			if (chain.length() > 0) chain.append(", then ");
			chain.append(step.runs()).append("x ").append(step.recipeId());
		}
		return ActionOutcome.ok("craft", "crafted " + gained + " " + wanted + " ("
				+ chain + ")" + (dropped > 0 ? ", " + dropped + " on the ground beside me" : ""));
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
		if (!NO_SUPPORT_NEEDED.contains(wanted.getBlock())) {
			BlockPos below = pos.below();
			if (!level().isLoaded(below)) return "the ground under " + describe(pos) + " is not loaded";
			if (level().getBlockState(below).isAir()) return "nothing to support a block at " + describe(pos);
		}
		List<LivingEntity> occupants = level().getEntitiesOfClass(LivingEntity.class, new AABB(pos),
				e -> e.isAlive() && e != this);
		if (!occupants.isEmpty()) return "something is standing in " + describe(pos);
		return null;
	}

	/** Place one block. The material is consumed only AFTER the block is verified to be there. */
	private ActionOutcome placeBlock(String blockName, BlockPos pos) {
		String refusal = placementRefusal(blockName, pos);
		if (refusal != null) return ActionOutcome.fail("place", refusal);

		BlockState wanted = resolveState(blockName, pos);
		if (wanted == null) return ActionOutcome.fail("place", "I do not know a block called " + blockName);
		if (!(level() instanceof ServerLevel serverLevel)) return ActionOutcome.fail("place", "no server world");

		String itemId = BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).toString();
		if (countItem(itemId) < 1) return ActionOutcome.fail("place", "I have no " + itemId + " with me");

		boolean placed = serverLevel.setBlock(pos, wanted, 3);
		BlockState now = serverLevel.getBlockState(pos);
		if (!placed || !now.is(wanted.getBlock())) {
			return ActionOutcome.fail("place", "the world would not accept " + itemId + " at " + describe(pos));
		}
		consumeItem(itemId, 1);
		return ActionOutcome.ok("place", "placed " + itemId + " at " + describe(pos));
	}

	/** Orientation preserved: a block with a facing points toward Cobbie, as if he placed it. */
	private BlockState resolveState(String blockName, BlockPos pos) {
		Block block = resolveBlock(blockName);
		if (block == null) return null;
		BlockState state = block.defaultBlockState();
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facingFrom(pos));
		}
		return state;
	}

	private Direction facingFrom(BlockPos pos) {
		double dx = this.getX() - (pos.getX() + 0.5);
		double dz = this.getZ() - (pos.getZ() + 0.5);
		if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
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
		return ActionOutcome.ok("collect", "picked up " + stacks + " stack(s) at " + describe(pos));
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

	LivingEntity findThreatToDefendAgainst() {
		if (!CobbieConfig.isCombatAllowed()) return null;
		ServerPlayer owner = owner();
		List<LivingEntity> around = level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(16.0), LivingEntity::isAlive);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : around) {
			if (e == this || !isLegalTarget(e)) continue;
			boolean threatening = e instanceof Mob asMob
					&& (asMob.getTarget() == this || (owner != null && asMob.getTarget() == owner));
			if (!threatening) continue;
			double d = e.distanceToSqr(this);
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	/** The combat safety rules, in exactly one place. */
	boolean isLegalTarget(LivingEntity e) {
		if (e == null || !e.isAlive() || e == this) return false;
		if (e instanceof Player p && isOwner(p)) return false;   // never the owner
		if (e instanceof Player) {
			return CobbieConfig.isPlayerCombatAllowed();     // players need their own separate switch
		}
		if (!CobbieConfig.isCombatAllowed()) return false;
		return e instanceof Enemy;                           // hostile mobs only; passives are untouchable
	}

	private ActionOutcome startAttack(ActionIntent intent) {
		if (!CobbieConfig.isCombatAllowed()) return ActionOutcome.fail("attack", "combat is switched off");
		LivingEntity target = chooseAttackTarget(intent.target());
		if (target == null) return ActionOutcome.fail("attack", "nothing I am allowed to fight is nearby");
		this.setTarget(target);
		active = intent;
		activeTarget = target.blockPosition();
		navigatingForAction = false;
		activeDeadlineTick = this.tickCount + STEP_DEADLINE_TICKS;
		return null; // resolves across ticks in attackTick()
	}

	private LivingEntity chooseAttackTarget(String typeHint) {
		double scan = Math.max(8, CobbieConfig.getNavigationRange());
		List<LivingEntity> around = level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(scan), LivingEntity::isAlive);
		String hint = typeHint == null ? "" : typeHint.trim().toLowerCase(Locale.ROOT);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : around) {
			if (!isLegalTarget(e) || !canAttack(e)) continue;
			if (!hint.isEmpty()) {
				String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().toLowerCase(Locale.ROOT);
				if (!id.contains(hint)) continue;
			}
			double d = e.distanceToSqr(this);
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	/** Chase and hit, revalidating the target immediately before every swing. */
	private ActionOutcome attackTick() {
		LivingEntity target = this.getTarget();
		if (target == null || !target.isAlive()) {
			this.setTarget(null);
			clearActive();
			return ActionOutcome.ok("attack", "nothing left to fight");
		}
		if (!isLegalTarget(target)) {
			this.setTarget(null);
			clearActive();
			return ActionOutcome.fail("attack", "that target is off-limits");
		}
		if (isWithinMeleeAttackRange(target) && hasLineOfSight(target)) {
			if (this.tickCount < attackCooldownUntil) return null;
			if (!(level() instanceof ServerLevel serverLevel)) {
				clearActive();
				return ActionOutcome.fail("attack", "no server world");
			}
			attackCooldownUntil = this.tickCount + swingDelayTicks();
			boolean hit = doHurtTarget(serverLevel, target);
			if (!hit) return null; // swung and missed; try again inside the deadline
			if (target.isAlive()) {
				// "Kill that zombie" used to end after the first swing landed. A fight is not one
				// hit, and a companion who stops mid-fight makes the owner finish his work.
				return null;
			}
			ActionOutcome outcome = ActionOutcome.ok("attack", "hit " + target.getName().getString()
					+ " and finished it off");
			this.setTarget(null);
			clearActive();
			return outcome;
		}
		getNavigation().moveTo(target, 0.95);
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
	 * Pick the best tool for the block kind and raise it into the hand — moving the real stack, so
	 * there is never a second copy of it.
	 */
	private ItemStack equipBestToolFor(String blockId) {
		ItemStack held = this.getMainHandItem();
		if (isTool(held) && scores(held, blockId) > 0 && !isBroken(held)) {
			return held; // already holding something usable
		}
		ItemStack best = ItemStack.EMPTY;
		int bestScore = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!isTool(stack) || isBroken(stack)) continue;
			int score = scores(stack, blockId);
			if (score > bestScore) {
				bestScore = score;
				best = stack;
			}
		}
		if (best.isEmpty()) {
			if (isTool(held)) this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return ItemStack.EMPTY;
		}
		// Stow whatever was in hand, then raise the tool out of the bag.
		if (!held.isEmpty()) {
			add(held.copy(), inventory);
			this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
		inventory.setItem(indexOf(best), ItemStack.EMPTY);
		this.setItemInHand(InteractionHand.MAIN_HAND, best);
		return best;
	}

	private int indexOf(ItemStack stack) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i) == stack) return i;
		}
		return 0;
	}

	private static boolean isTool(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
		for (String word : TOOL_WORDS) {
			if (id.contains(word)) return true;
		}
		return false;
	}

	private static boolean isBroken(ItemStack stack) {
		return stack.isDamageableItem() && stack.getMaxDamage() > 0
				&& stack.getDamageValue() >= stack.getMaxDamage();
	}

	/** Higher is better; 0 means "not the right implement for this block". */
	private static int scores(ItemStack stack, String blockId) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
		int tier = 0;
		for (int t = 0; t < TIER_RANK.size(); t++) {
			if (id.contains(TIER_RANK.get(t))) {
				tier = TIER_RANK.size() - t;
				break;
			}
		}
		int score = tier;
		String b = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
		boolean logLike = b.contains("log") || b.contains("planks") || b.contains("fence") || b.contains("wood");
		boolean stoneLike = b.contains("stone") || b.contains("ore") || b.contains("deepslate")
				|| b.contains("cobble") || b.contains("granite") || b.contains("diorite") || b.contains("andesite");
		boolean dirtLike = b.contains("dirt") || b.contains("grass") || b.contains("sand")
				|| b.contains("gravel") || b.contains("clay");
		if (logLike && id.contains("axe")) score += 8;
		else if (stoneLike && id.contains("pickaxe")) score += 8;
		else if (dirtLike && id.contains("shovel")) score += 8;
		else if (id.contains("sword")) score += 1;
		return score;
	}

	private void wearTool(ItemStack tool) {
		if (tool == null || tool.isEmpty() || !tool.isDamageableItem()) return;
		try {
			tool.hurtAndBreak(1, this, InteractionHand.MAIN_HAND);
			if (isBroken(tool)) {
				tool.shrink(1);
				if (tool.isEmpty()) this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
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

		Optional<Long> most = in.getLong("CobbieOwnerMost");
		Optional<Long> least = in.getLong("CobbieOwnerLeast");
		if (most.isPresent() && least.isPresent()) {
			ownerUuid = new UUID(most.get(), least.get());
		}

		try {
			List<ItemStack> items = in.read("CobbieItems", ITEM_LIST).orElse(List.of());
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = i < items.size() ? items.get(i) : null;
				inventory.setItem(i, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
			}
		} catch (Exception e) {
			Cobbie.LOGGER.warn("Cobbie's saved bag could not be read: {}", e.toString());
		}
	}
}
