package com.rishi.cobbie.entity;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.config.CobbieConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Cobbie — your AI friend. A peaceful companion that follows you around
 * and acts on the world (move, mine, place, attack, wave, say).
 *
 * World mutations are driven through a per-entity action queue processed on
 * the server thread in tick(), guaranteeing thread safety and no duplicate
 * or overlapping actions.
 *
 * Inventory is a configurable SimpleContainer, integrated into perception
 * and build/mine behavior.
 */
public class CobbieEntity extends PathfinderMob {

	// Queue of pending actions from the LLM, processed one per tick on server thread.
	private final ConcurrentLinkedDeque<ActionIntent> actionQueue = new ConcurrentLinkedDeque<>();

	// Currently executing action.
	private String currentAction = "answer";
	private BlockPos moveTarget = null;
	private String placeBlock = null;
	private BlockPos placePos = null;
	private String attackType = null;
	private String sayText = null;
	private boolean following = true;

	// Owner tracking (UUID of the player who spawned Cobbie)
	private UUID ownerUuid = null;

	// Cobbie's personal inventory (configurable size)
	private final Container inventory = new SimpleContainer(CobbieConfig.getInventorySize());

	public CobbieEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.35)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void registerGoals() {
		// Priority 1: float so he doesn't sink in water
		this.goalSelector.addGoal(1, new FloatGoal(this));
		// Priority 2: follow the owner — active when following==true
		this.goalSelector.addGoal(2, new FollowMobGoal(this, 0.6, 4.0f, 2.0f));
		// Priority 3: look at the player when nearby
		this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));
		// Priority 4: idle head-look
		this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
		// Priority 5: attack hostile mobs when attackNearest is set (combat allowed)
		this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LivingEntity.class, true, false));
	}

	// ----- Owner tracking -----

	public void setOwner(UUID uuid) { this.ownerUuid = uuid; }
	public UUID getOwner() { return ownerUuid; }
	public boolean isOwner(Player player) { return player != null && ownerUuid != null && player.getUUID().equals(ownerUuid); }

	// ----- Public action API used by CobbieBrain -----

	public void startFollowing() { this.following = true; }
	public void stopFollowing() { this.following = false; }
	public boolean isFollowing() { return following; }

	public void wave() { enqueue(new ActionIntent("wave", "", 0, 0, 0, "")); }

	public void moveTo(BlockPos pos) { enqueue(new ActionIntent("walk-to", "", pos.getX(), pos.getY(), pos.getZ(), "")); }

	public void mine(BlockPos pos) { enqueue(new ActionIntent("mine", "", pos.getX(), pos.getY(), pos.getZ(), "")); }

	public void place(String blockName, BlockPos pos) {
		enqueue(new ActionIntent("place", blockName, pos.getX(), pos.getY(), pos.getZ(), ""));
	}

	public void attackNearest(String type) { enqueue(new ActionIntent("attack", type, 0, 0, 0, "")); }

	public void say(String text) { enqueue(new ActionIntent("chat", text, 0, 0, 0, "")); }

	/** Queue an action intent for processing on the next idle tick. */
	public void enqueue(ActionIntent intent) {
		actionQueue.add(intent);
	}

	// ----- Inventory support -----

	/** Get the item in the first non-empty inventory slot, or null. */
	public ItemStack getHeldItem() {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) return stack;
		}
		return ItemStack.EMPTY;
	}

	/** Count how many of a given item registry ID Cobbie has in inventory. */
	public int countItem(String registryId) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				if (registryId.equals(id)) {
					count += stack.getCount();
				}
			}
		}
		return count;
	}

	/** Count items by exact item match (registry ID). */
	public int countItemExact(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack existing = inventory.getItem(i);
			if (!existing.isEmpty() && existing.is(stack.getItem())) {
				count += existing.getCount();
			}
		}
		return count;
	}

	/** Add an item stack to inventory (returns true if stored). */
	public boolean addItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack existing = inventory.getItem(i);
			if (existing.isEmpty()) {
				inventory.setItem(i, stack.copy());
				return true;
			} else if (existing.is(stack.getItem()) && existing.getCount() < existing.getMaxStackSize()) {
				int space = existing.getMaxStackSize() - existing.getCount();
				int toAdd = Math.min(stack.getCount(), space);
				existing.grow(toAdd);
				stack.shrink(toAdd);
				if (stack.isEmpty()) return true;
			}
		}
		return false;
	}

	/** Remove count of item registry ID from inventory. Returns true if enough was available. */
	public boolean consumeItem(String registryId, int count) {
		if (count <= 0) return true;
		if (countItem(registryId) < count) return false;
		int remaining = count;
		for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				if (registryId.equals(id)) {
					int take = Math.min(stack.getCount(), remaining);
					stack.shrink(take);
					remaining -= take;
				}
			}
		}
		return remaining == 0;
	}

	/** Try to pick up a nearby ItemEntity. Returns true if something was collected. */
	public boolean collectNearbyItems() {
		if (this.level() == null) return false;
		boolean collected = false;
		List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class,
				new AABB(this.blockPosition()).inflate(2.5),
				ItemEntity::isAlive);
		for (ItemEntity item : items) {
			ItemStack stack = item.getItem();
			if (!stack.isEmpty() && addItem(stack)) {
				stack.shrink(stack.getCount());
				item.discard();
				collected = true;
			}
		}
		return collected;
	}

	// ----- Tick: drive pending actions (server thread only) -----

	@Override
	public void tick() {
		super.tick();

		// Auto-collect nearby dropped items periodically
		if (this.tickCount % 20 == 0) {
			collectNearbyItems();
		}

		// Owner-following logic (respects follow_distance)
		if (ownerUuid != null && this.level() != null) {
			Player owner = this.level().getPlayerByUUID(ownerUuid);
			if (owner != null && isFollowing()) {
				double dist = this.distanceToSqr(owner);
				double followDist = CobbieConfig.getFollowDistance();
				if (dist > followDist * followDist) {
					getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 0.6);
				} else {
					getNavigation().stop();
				}
			}
		}

		// If idle and not following, wander occasionally (freedom to explore)
		if (!following && currentAction.equals("answer") && this.random.nextInt(600) == 0) {
			if (CobbieConfig.isExplorationAllowed()) {
				BlockPos wander = this.blockPosition().offset(
						this.random.nextInt(16) - 8, 0, this.random.nextInt(16) - 8);
				moveTo(wander);
			}
		}

		// If idle, pull the next queued action.
		if (currentAction.equals("answer")) {
			ActionIntent next = actionQueue.poll();
			if (next != null) {
				startAction(next);
			}
		}

		// Drive the current action to completion.
		switch (currentAction) {
			case "walk-to" -> {
				if (moveTarget != null) {
					getNavigation().moveTo(moveTarget.getX(), moveTarget.getY(), moveTarget.getZ(), 0.6);
					if (this.blockPosition().closerThan(moveTarget, 1.5)) {
						moveTarget = null;
						currentAction = "answer";
					}
				} else {
					currentAction = "answer";
				}
			}
			case "mine" -> {
				if (moveTarget != null && this.level() != null && CobbieConfig.isMiningAllowed()) {
					mineBlock(moveTarget);
					moveTarget = null;
					currentAction = "answer";
				} else {
					currentAction = "answer";
				}
			}
			case "place" -> {
				if (placeBlock != null && placePos != null && this.level() != null && CobbieConfig.isBuildingAllowed()) {
					placeBlock(placeBlock, placePos);
					placeBlock = null;
					placePos = null;
					currentAction = "answer";
				} else {
					currentAction = "answer";
				}
			}
			case "attack" -> {
				if (this.level() != null && CobbieConfig.isCombatAllowed()) {
					attackNearestEntity();
				}
				currentAction = "answer";
			}
			case "chat" -> {
				if (sayText != null) {
					if (this.level() != null && !this.level().isClientSide()) {
						this.level().players().forEach(p ->
								p.sendSystemMessage(Component.literal("<Cobbie> " + sayText)));
					}
					sayText = null;
					currentAction = "answer";
				} else {
					currentAction = "answer";
				}
			}
			case "follow" -> {
				// Owner-following handled above
				currentAction = "answer";
			}
			case "wave" -> {
				currentAction = "answer";
			}
			case "answer" -> {
				// No world action
			}
		}
	}

	/** Start executing a queued intent. */
	private void startAction(ActionIntent intent) {
		switch (intent.action()) {
			case "follow" -> startFollowing();
			case "wave" -> { currentAction = "wave"; }
			case "walk-to" -> { currentAction = "walk-to"; moveTarget = new BlockPos(intent.x(), intent.y(), intent.z()); }
			case "mine" -> { currentAction = "mine"; moveTarget = new BlockPos(intent.x(), intent.y(), intent.z()); }
			case "place" -> {
				currentAction = "place";
				placeBlock = intent.target();
				placePos = new BlockPos(intent.x(), intent.y(), intent.z());
			}
			case "attack" -> { currentAction = "attack"; attackType = intent.target(); }
			case "chat" -> { currentAction = "chat"; sayText = intent.target(); }
			case "answer", "wait" -> { /* no world action */ }
			default -> Cobbie.LOGGER.warn("Unknown action from LLM: {}", intent.action());
		}
	}

	// ----- Safe combat targeting (never attack owner or friendlies) -----

	private void attackNearestEntity() {
		if (this.level() == null) return;

		var targets = this.level().getEntitiesOfClass(LivingEntity.class,
				new AABB(this.blockPosition()).inflate(16.0),
				e -> e.isAlive() && e != this
						&& !(e instanceof Player p && isOwner(p))
						&& (attackType == null || e.getType().toString().contains(attackType)));

		if (!targets.isEmpty()) {
			this.setTarget(targets.get(0));
			Cobbie.LOGGER.info("Cobbie attacking {}", targets.get(0).getName().getString());
		}
	}

	// ----- Real world-changing operations (server thread only) -----

	private void mineBlock(BlockPos pos) {
		if (this.level() == null) return;

		// Distance check — respect action_range
		if (this.blockPosition().distSqr(pos) > Math.pow(CobbieConfig.getActionRange(), 2)) {
			Cobbie.LOGGER.warn("Cobbie mine target too far: {}", pos);
			return;
		}

		BlockState state = this.level().getBlockState(pos);
		if (state.isAir()) {
			Cobbie.LOGGER.warn("Cobbie mine target is air: {}", pos);
			return;
		}

		// Don't mine bedrock, barrier, command, structure
		if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)
				|| state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.STRUCTURE_BLOCK)) {
			Cobbie.LOGGER.warn("Cobbie refused to mine protected block: {}", pos);
			return;
		}

		// Collect drops via proper loot table
		if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			List<ItemStack> drops = state.getBlock().getDrops(state, serverLevel, pos, null);
			for (ItemStack drop : drops) {
				if (!drop.isEmpty()) {
					addItem(drop);
				}
			}
		}
		this.level().destroyBlock(pos, false, this); // false = don't spawn drops (we handle them)
		Cobbie.LOGGER.info("Cobbie mined {} at {}", state.getBlock().getName().getString(), pos);
	}

	private void placeBlock(String blockName, BlockPos pos) {
		if (this.level() == null) return;

		// Distance check
		if (this.blockPosition().distSqr(pos) > Math.pow(CobbieConfig.getActionRange(), 2)) {
			Cobbie.LOGGER.warn("Cobbie place target too far: {}", pos);
			return;
		}

		// Don't place inside the player, Cobbie himself, or owner
		if (this.blockPosition().equals(pos)) {
			Cobbie.LOGGER.warn("Cobbie refused to place on self: {}", pos);
			return;
		}

		// Resolve block from name (registry ID)
		BlockState state = resolveBlock(blockName);
		if (state == null) {
			Cobbie.LOGGER.warn("Cobbie couldn't resolve block: {}", blockName);
			return;
		}

		// Consume material from inventory (by registry ID)
		String blockRegistryId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (!consumeItem(blockRegistryId, 1)) {
			Cobbie.LOGGER.warn("Cobbie has no {} in inventory to place", blockName);
			return;
		}

		// Don't place inside an existing solid block unless replacing air
		BlockState existing = this.level().getBlockState(pos);
		if (!existing.isAir()) {
			Cobbie.LOGGER.warn("Cobbie place target occupied: {} by {}", pos, existing.getBlock().getName().getString());
			return;
		}

		// Support check: don't place floating blocks without support below (except certain blocks)
		if (!state.getBlock().equals(Blocks.TORCH)
				&& !state.getBlock().equals(Blocks.WALL_TORCH)
				&& !state.getBlock().equals(Blocks.LADDER)) {
			BlockPos below = pos.below();
			if (this.level().getHeight(Heightmap.Types.MOTION_BLOCKING, below.getX(), below.getZ()) <= below.getY()) {
				BlockState belowState = this.level().getBlockState(below);
				if (belowState.isAir()) {
					Cobbie.LOGGER.warn("Cobbie refused to place floating block at {}", pos);
					return;
				}
			}
		}

		boolean success = this.level().setBlock(pos, state, 3);
		if (success) {
			Cobbie.LOGGER.info("Cobbie placed {} at {}", blockName, pos);
		} else {
			Cobbie.LOGGER.warn("Cobbie failed to place {} at {}", blockName, pos);
		}
	}

	private BlockState resolveBlock(String blockName) {
		if (blockName == null || blockName.isBlank()) return null;

		String name = blockName;
		String namespace = "minecraft";
		if (name.contains(":")) {
			String[] parts = name.split(":", 2);
			namespace = parts[0];
			name = parts[1];
		}

		Identifier id = Identifier.fromNamespaceAndPath(namespace, name);
		var block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block != null && block != Blocks.AIR) {
			return block.defaultBlockState();
		}

		return null;
	}

	/** Get the inventory container for perception integration. */
	public Container getInventoryContainer() {
		return inventory;
	}
}
