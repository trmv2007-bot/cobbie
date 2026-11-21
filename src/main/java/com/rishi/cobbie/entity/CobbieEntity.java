package com.rishi.cobbie.entity;

import com.mojang.serialization.Codec;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.task.TaskRunner;
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
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
public class CobbieEntity extends PathfinderMob implements TaskRunner.Executor {

	private static final EntityDataAccessor<Boolean> DATA_WAVING =
			SynchedEntityData.defineId(CobbieEntity.class, EntityDataSerializers.BOOLEAN);

	private static final int WAVE_TICKS = 40;
	private static final int STEP_DEADLINE_TICKS = 400;   // 20s per step, then it is abandoned
	private static final double ARRIVE_RADIUS = 1.5;      // "I have walked there", not "I can touch it"
	private static final int WALK_GRACE_TICKS = 10;       // let the navigator compute before judging it
	private static final int MAX_WALK_REPATHS = 3;         // then repair a dropped path, a few times
	private static final int FOLLOW_REPATH_INTERVAL = 12; // the pathfinder is not free
	private static final int PICKUP_INTERVAL = 20;

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
	private ActionOutcome pendingOutcome;

	// ---- bookkeeping ----
	private int waveTicksLeft;
	private int followRepathTick;
	private int lastPickupTick;
	private int attackCooldownUntil;
	private String lastActionResult = "nothing yet";
	/** Where he stood when the current plan started — the origin skill steps are stored relative to. */
	private BlockPos planOrigin;
	/** Items he already failed to take, so he does not claw at them every tick. */
	private final Set<UUID> skippedItems = new HashSet<>();

	public CobbieEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.inventory = new SimpleContainer(Math.max(1, CobbieConfig.getInventorySize()));
		this.tasks = new TaskRunner(this,
				CobbieConfig.getPlanStepLimit(),
				CobbieConfig.getTaskRetryLimit(),
				CobbieConfig.getTaskTimeoutSeconds() * 1000L);
		this.setPersistenceRequired(); // a companion must not despawn because you walked away
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.ATTACK_DAMAGE, 3.0)
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
		if (this.isRemoved()) return "I am not around any more";
		String refusal = tasks.start(request, intents);
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
		return WorldPerception.snapshot(this, currentTaskDescription(),
				active == null ? "idle" : active.action(), lastActionResult);
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

		followOwnerTick();

		ActionOutcome settled = driveActiveStep();
		if (settled != null) pendingOutcome = settled;

		tasks.tick(System.currentTimeMillis());

		// Push the finished plan's verified report to the brain (chat, memory, skill credit,
		// learning, and the model's own feedback all hang off that single delivery).
		String report = tasks.takeReport();
		if (report != null) {
			com.rishi.cobbie.brain.CobbieBrain.reportPlanSettled(this, tasks.plan());
		}

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
			return;
		}
		double navLimit = Math.max(8.0, CobbieConfig.getNavigationRange() * 2.0);
		if (distSq > navLimit * navLimit) return; // never path across the map for a teleport

		if (this.tickCount - followRepathTick < FOLLOW_REPATH_INTERVAL) return;
		followRepathTick = this.tickCount;
		if (!level().isLoaded(owner.blockPosition())) return;
		getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 0.85);
	}

	private void exploreSomewhereNearby() {
		int range = Math.max(6, Math.min(CobbieConfig.getNavigationRange(), 16));
		int dx = this.random.nextInt(range * 2 + 1) - range;
		int dz = this.random.nextInt(range * 2 + 1) - range;
		BlockPos guess = this.blockPosition().offset(dx, 0, dz);
		int surface = level().getHeight(Heightmap.Types.MOTION_BLOCKING, guess.getX(), guess.getZ());
		BlockPos stand = new BlockPos(guess.getX(), surface, guess.getZ());
		if (!level().isLoaded(stand)) return;
		offerPlan("looking around a bit",
				List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
						stand.getX(), stand.getY(), stand.getZ(), "")));
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
		// In-world speech, heard by whoever is standing nearby — not a dimension-wide broadcast.
		for (Player p : level().getEntitiesOfClass(Player.class,
				new AABB(this.blockPosition()).inflate(32.0))) {
			p.sendSystemMessage(Component.literal("§e[Cobbie] §f" + line));
		}
		return ActionOutcome.ok("chat", "said: " + line);
	}

	private ActionOutcome startWalk(ActionIntent intent) {
		BlockPos target = new BlockPos(intent.x(), intent.y(), intent.z());
		if (!level().isLoaded(target)) return ActionOutcome.fail("walk-to", "that area is not loaded");
		double navLimit = Math.max(8, CobbieConfig.getNavigationRange());
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
			if (wanted.isEmpty() || wanted.equalsIgnoreCase(itemId(stack)) || wanted.endsWith(stack.getItem().toString())) {
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
			if (wanted.isEmpty() || wanted.endsWith(stack.getItem().toString())
					|| wanted.equalsIgnoreCase(itemId(stack))) {
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
			if (wanted.isEmpty() || wanted.equalsIgnoreCase(itemId(stack))) {
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
				world.shrink(before - trial.getCount());
				if (world.isEmpty()) item.discard();
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
			attackCooldownUntil = this.tickCount + 16;
			boolean hit = doHurtTarget(serverLevel, target);
			if (!hit) return null; // swung and missed; try again inside the deadline
			ActionOutcome outcome = ActionOutcome.ok("attack", "hit " + target.getName().getString()
					+ (target.isAlive() ? "" : " and finished it off"));
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
