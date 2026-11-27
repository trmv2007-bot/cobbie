package com.rishi.cobbie.entity;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * His hunger, held as the game's own {@link FoodData} and ticked by the game's own {@link FoodData#tick}.
 *
 * <h2>Why the numbers are not mine</h2>
 * Every quantity here is produced by vanilla: the drain of exhaustion, the two regeneration rates, the
 * starvation damage and its health gates, the nutrition and saturation of a food, the exhaustion each
 * action costs, and whether he may sprint. None of it is re-implemented. The values live in the
 * {@link FoodData} instance and are written and read by {@code FoodData}'s own save methods, so what a
 * save round-trips is the game's four fields, not a copy of them.
 *
 * <h2>How a mob gets a player's hunger tick</h2>
 * {@code FoodData.tick} takes a {@code ServerPlayer}: it reads its health and difficulty, heals it and
 * hurts it. {@link Actor} is that player — Fabric's {@code FakePlayer} shape, cached-tab and no-op
 * connection included — but every question it asks about the body is answered by the companion:
 * {@code getFoodData}/{@code foodData}, health, hurt, heal and effects all forward to him. So the game's
 * tick runs unchanged, and its healing and its starvation damage land on Cobbie.
 *
 * <p>The actor is also what eats: {@code ItemStack.use} and {@code finishUsingItem} run on a
 * {@code Player}, so bread, cooked meat, a golden apple, milk and a potion all behave exactly as they do
 * for a player — nutrition from the item's own {@code FOOD} component, effects applied to the body,
 * the stack shrunk by the game's rule, a bowl or a bucket returned by the item's own remainder.
 */
final class Hunger {

	/**
	 * My policy, not the game's: at or below this many food points he looks for something to eat. The
	 * game's own numbers are {@link FoodConstants#HEAL_LEVEL} (18, slow regeneration),
	 * {@link FoodConstants#SPRINT_LEVEL} (6, no sprinting) and {@link FoodConstants#STARVE_LEVEL} (0).
	 * Fourteen is early enough to top up before the regeneration band closes and late enough that he is
	 * not eating at nineteen out of twenty.
	 */
	static final int EAT_AT = 14;

	/** My policy: below this much health he drinks a healing potion if he is carrying one. */
	static final float DRINK_AT_HEALTH = 8.0f;

	/** One look at the bag every second, which is the cadence a person eats at. */
	private static final int POLICY_INTERVAL_TICKS = 20;

	/**
	 * A move longer than this in one tick is a teleport, not walking: the game never moves a player two
	 * blocks in a tick, and handing that distance to the movement tracker would charge him exhaustion for
	 * a journey he did not walk.
	 */
	private static final double TELEPORT_SQR = 4.0;

	private static final UUID ACTOR_ID =
			UUID.nameUUIDFromBytes("cobbie:stomach".getBytes(StandardCharsets.UTF_8));
	private static final GameProfile ACTOR_PROFILE = new GameProfile(ACTOR_ID, "[Cobbie]");

	private final CobbieEntity body;
	private final FoodData data = new FoodData();
	private Actor actor;
	private ServerLevel actorLevel;
	private String actorError = "";

	private double lastX, lastY, lastZ;
	private boolean haveLast;
	private int policyCountdown = POLICY_INTERVAL_TICKS;
	private int band;

	Hunger(CobbieEntity body) {
		this.body = body;
	}

	// ------------------------------------------------------------------
	// The tick, and what it is fed
	// ------------------------------------------------------------------

	/**
	 * One server tick: the exhaustion of the distance he covered, then the game's own food tick.
	 *
	 * <p>Movement goes through {@code ServerPlayer.checkMovementStatistics}, which is where vanilla
	 * charges a player for swimming, walking under water and sprinting; a sprinting player pays
	 * {@link FoodConstants#EXHAUSTION_SPRINT} per centimetre times a hundred, which is the constant the
	 * game's own method applies. Walking costs {@link FoodConstants#EXHAUSTION_WALK} — zero — so a
	 * companion who strolls never starves, exactly as a player who strolls never does.
	 */
	void tick() {
		if (!(body.level() instanceof ServerLevel level)) return;
		Actor a = actor(level);
		if (a == null) return;
		if (!body.isAlive()) {
			haveLast = false;
			return;
		}

		double x = body.getX(), y = body.getY(), z = body.getZ();
		if (haveLast) {
			double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
			if (dx * dx + dy * dy + dz * dz <= TELEPORT_SQR) {
				a.setPos(x, y, z);
				a.setOnGround(body.onGround());
				a.setSprinting(body.isSprinting());
				a.setSwimming(body.isSwimming());
				a.checkMovementStatistics(dx, dy, dz);
			}
		}
		lastX = x;
		lastY = y;
		lastZ = z;
		haveLast = true;

		data.tick(a);
		noteBand();

		if (--policyCountdown <= 0) {
			policyCountdown = POLICY_INTERVAL_TICKS;
			autoConsume();
		}
	}

	/**
	 * Jumping charges what the game charges: a sprint jump {@link FoodConstants#EXHAUSTION_SPRINT_JUMP},
	 * any other jump {@link FoodConstants#EXHAUSTION_JUMP}, both applied by {@code ServerPlayer}'s own
	 * {@code jumpFromGround}. The actor is not in the world, so its leap moves nothing.
	 */
	void jumped() {
		Actor a = actor();
		if (a == null) return;
		a.setSprinting(body.isSprinting());
		a.jumpFromGround();
	}

	/** Breaking one block costs what {@code Block.playerDestroy} charges a player: {@link FoodConstants#EXHAUSTION_MINE}. */
	void mined() {
		Actor a = actor();
		if (a != null) a.causeFoodExhaustion(FoodConstants.EXHAUSTION_MINE);
	}

	/** Landing a hit costs what {@code Player.attack} charges: {@link FoodConstants#EXHAUSTION_ATTACK}. */
	void hit() {
		Actor a = actor();
		if (a != null) a.causeFoodExhaustion(FoodConstants.EXHAUSTION_ATTACK);
	}

	/**
	 * Taking damage costs what the damage source says it costs, which is the game's own number and not a
	 * constant of mine: {@code Player.hurtServer} calls {@code causeFoodExhaustion(source.getFoodExhaustion())}
	 * and this is the same call on the same source.
	 */
	void damaged(net.minecraft.world.damagesource.DamageSource source) {
		Actor a = actor();
		if (a != null && source != null) a.causeFoodExhaustion(source.getFoodExhaustion());
	}

	// ------------------------------------------------------------------
	// Eating and drinking
	// ------------------------------------------------------------------

	/**
	 * He eats the worst food he carries, in the game's terms: lowest nutrition first.
	 *
	 * <p>There is no "is he full" test here on purpose. The game's {@code Consumable.canConsume} answers
	 * that — ordinary food is refused at a full bar and a golden apple is not — and a second rule beside
	 * it would be the kind of guess this codebase stopped making. The automatic policy below is what
	 * keeps him from spending a golden apple at nineteen out of twenty.
	 */
	boolean tryEat() {
		int slot = worstFoodSlot();
		return slot >= 0 && consume(slot);
	}

	/**
	 * Milk when a harmful effect is on him and he carries it; a healing potion when he is badly hurt.
	 * Both go through the item's own use, so milk clears effects with the game's own
	 * {@code ClearAllStatusEffectsConsumeEffect} and a potion applies the game's own instant health.
	 */
	boolean tryDrink() {
		if (!body.level().isClientSide() && hasHarmfulEffect()) {
			int milk = firstSlotOf(Items.MILK_BUCKET);
			if (milk >= 0 && consume(milk)) return true;
		}
		if (body.getHealth() <= DRINK_AT_HEALTH) {
			int potion = healingPotionSlot();
			if (potion >= 0 && consume(potion)) return true;
		}
		return false;
	}

	private void autoConsume() {
		if (body.isUsingItem()) return;
		if (tryDrink()) return;
		if (foodLevel() <= EAT_AT) tryEat();
	}

	/**
	 * Consume one item from a bag slot the way a player does.
	 *
	 * <p>{@code ItemStack.use} is the start of the gesture and asks the game whether it is allowed
	 * ({@code Consumable.canConsume}, which asks {@code Player.canEat} about the food bar, so a full bar
	 * refuses bread and allows a golden apple). {@code finishUsingItem} is the end of it: nutrition,
	 * effects, the shrink, and the item's own remainder — a bowl from stew, a bucket from milk — all come
	 * out of the item's components. Whatever the game leaves in the actor's hand replaces the slot, and
	 * anything the game created beside the stack (a bowl next to a stack of stew) is swept into his bag.
	 */
	private boolean consume(int slot) {
		if (!(body.level() instanceof ServerLevel level)) return false;
		ItemStack source = body.bagStack(slot);
		if (source.isEmpty()) return false;
		Actor a = actor(level);
		if (a == null) return false;

		emptyActorBag(a);
		ItemStack hand = source.copy();
		a.setItemInHand(InteractionHand.MAIN_HAND, hand);
		ItemStack after;
		try {
			InteractionResult started = hand.use(level, a, InteractionHand.MAIN_HAND);
			if (!started.consumesAction()) return false;
			if (a.isUsingItem()) {
				// The use duration is real for a player; the outcome is the same stack finishing.
				after = a.getItemInHand(InteractionHand.MAIN_HAND).finishUsingItem(level, a);
				a.stopUsingItem();
			} else {
				after = a.getItemInHand(InteractionHand.MAIN_HAND);
			}
		} catch (RuntimeException e) {
			com.rishi.cobbie.Cobbie.LOGGER.warn("Cobbie could not consume {}: {}",
					source.getItem(), e.toString());
			return false;
		} finally {
			a.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
		body.setBagStack(slot, after == null ? ItemStack.EMPTY : after.copy());
		sweepActorBag(a);
		return true;
	}

	private int worstFoodSlot() {
		int best = -1;
		int bestNutrition = Integer.MAX_VALUE;
		float bestSaturation = Float.MAX_VALUE;
		for (int i = 0; i < body.bagSize(); i++) {
			ItemStack stack = body.bagStack(i);
			if (stack.isEmpty()) continue;
			FoodProperties food = stack.get(DataComponents.FOOD);
			if (food == null || food.nutrition() <= 0) continue;
			if (food.nutrition() < bestNutrition
					|| (food.nutrition() == bestNutrition && food.saturation() < bestSaturation)) {
				best = i;
				bestNutrition = food.nutrition();
				bestSaturation = food.saturation();
			}
		}
		return best;
	}

	private int firstSlotOf(net.minecraft.world.item.Item item) {
		for (int i = 0; i < body.bagSize(); i++) {
			ItemStack stack = body.bagStack(i);
			if (!stack.isEmpty() && stack.is(item)) return i;
		}
		return -1;
	}

	private int healingPotionSlot() {
		for (int i = 0; i < body.bagSize(); i++) {
			ItemStack stack = body.bagStack(i);
			if (stack.isEmpty() || !stack.is(Items.POTION)) continue;
			PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
			if (contents == null) continue;
			for (MobEffectInstance effect : contents.getAllEffects()) {
				if (effect.getEffect().value() == MobEffects.INSTANT_HEALTH.value()) return i;
			}
		}
		return -1;
	}

	private boolean hasHarmfulEffect() {
		for (MobEffectInstance effect : body.getActiveEffects()) {
			if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return true;
		}
		return false;
	}

	private void emptyActorBag(Actor a) {
		for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
			a.getInventory().setItem(i, ItemStack.EMPTY);
		}
	}

	private void sweepActorBag(Actor a) {
		for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
			ItemStack left = a.getInventory().getItem(i);
			if (left.isEmpty()) continue;
			ItemStack copy = left.copy();
			a.getInventory().setItem(i, ItemStack.EMPTY);
			body.addItem(copy);
		}
	}

	// ------------------------------------------------------------------
	// What the game says about his body
	// ------------------------------------------------------------------

	String debugSprint() {
		Actor a = actor();
		if (a == null) return "actor=none (" + actorError + ")";
		return "actorFood=" + a.getFoodData().getFoodLevel() + " sameData=" + (a.getFoodData() == data)
				+ " mayfly=" + a.getAbilities().mayfly + " instabuild=" + a.getAbilities().instabuild
				+ " enough=" + a.maySprint() + " own=" + data.getFoodLevel();
	}

	/** The game's own answer to "may he sprint": food above {@link FoodConstants#SPRINT_LEVEL}. */
	boolean maySprint() {
		Actor a = actor();
		return a == null || a.maySprint();
	}

	// ------------------------------------------------------------------
	// State, saving, and what a person reads
	// ------------------------------------------------------------------

	int foodLevel() {
		return data.getFoodLevel();
	}

	float saturation() {
		return data.getSaturationLevel();
	}

	/** Exhaustion has no getter on {@link FoodData}, so it is read through the game's own save form. */
	float exhaustion() {
		TagValueOutput out = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
		data.addAdditionalSaveData(out);
		return out.buildResult().getFloatOr("foodExhaustionLevel", 0.0f);
	}

	void set(int food, float saturation) {
		data.setFoodLevel(Math.max(0, Math.min(FoodConstants.MAX_FOOD, food)));
		data.setSaturation(Math.max(0.0f, saturation));
		// The band is deliberately not synced: a bar put somewhere by a command or a test is a change he
		// crossed, and the next tick says so once.
	}

	void addExhaustion(float amount) {
		Actor a = actor();
		if (a != null) a.causeFoodExhaustion(amount);
		else data.addExhaustion(amount);
	}

	void save(ValueOutput out) {
		data.addAdditionalSaveData(out);
	}

	void load(ValueInput in) {
		data.readAdditionalSaveData(in);
		band = bandOf(data.getFoodLevel());
	}

	/** One line for `/cobbie status` and the panel: the three numbers a hunger bar is made of. */
	String describe() {
		return String.format(Locale.ROOT, "food %d/%d, saturation %.1f, exhaustion %.1f",
				foodLevel(), FoodConstants.MAX_FOOD, saturation(), exhaustion());
	}

	/** 0 well fed, 1 hungry, 2 starving — in the game's own terms, one band below the eat threshold. */
	private static int bandOf(int food) {
		if (food <= FoodConstants.STARVE_LEVEL) return 2;
		if (food <= EAT_AT) return 1;
		return 0;
	}

	/** Say it once, when he crosses into hungry or into starving — not on every tick he spends there. */
	private void noteBand() {
		int now = bandOf(foodLevel());
		if (now == band) return;
		int was = band;
		band = now;
		if (now > was) {
			body.sayToOwner(now == 2
					? "I'm starving — I need food."
					: "I'm getting hungry — I could eat something.");
		}
	}

	// ------------------------------------------------------------------
	// The player that stands in for his body
	// ------------------------------------------------------------------

	private Actor actor() {
		return body.level() instanceof ServerLevel level ? actor(level) : null;
	}

	private Actor actor(ServerLevel level) {
		// One failure per level is enough: retrying every tick would be a log flood, and a body that
		// cannot carry a player actor will not start carrying one on the next tick.
		if (actor == null && !actorError.isEmpty() && actorLevel == level) return null;
		if (actor == null || actorLevel != level) {
			actorError = "";
			actorLevel = level;
			try {
				actor = new Actor(level, ACTOR_PROFILE, body, data);
				actorLevel = level;
			} catch (RuntimeException e) {
				actorError = e.getClass().getSimpleName() + ": " + e.getMessage();
				com.rishi.cobbie.Cobbie.LOGGER.warn("Cobbie has no appetite today", e);
				return null;
			}
		}
		return actor;
	}

	/**
	 * A {@code FakePlayer} whose body is the companion.
	 *
	 * <p>Fabric's shape supplies the do-nothing connection and the no-op stat/advancement hooks that
	 * every menu path expects; the overrides below are the only thing that makes the hunger the
	 * companion's rather than a ghost player's. The {@code foodData} field is the inherited one, so the
	 * game's own {@code causeFoodExhaustion} and {@code canEat} — which read the field directly — read
	 * and write his bar.
	 */
	private static final class Actor extends FakePlayer {
		private final CobbieEntity body;

		Actor(ServerLevel level, GameProfile profile, CobbieEntity body, FoodData data) {
			super(level, profile);
			this.body = body;
			this.foodData = data;
			// FakePlayer starts with creative-style abilities. Both of the ones that matter have to go:
			// instabuild so the food stack actually shrinks, and mayfly because the game's sprint rule
			// ("food above SPRINT_LEVEL, or flight") would otherwise always answer yes.
			this.getAbilities().instabuild = false;
			this.getAbilities().invulnerable = false;
			this.getAbilities().mayfly = false;
			this.getAbilities().flying = false;
		}

		/*
		 * The super constructor calls this methods before `body` is assigned — it creates the
		 * interpolation handler from level(), and a hunger actor has no body for that instant. Every
		 * override is therefore inert until the constructor has finished.
		 */

		@Override
		public boolean isHurt() {
			// The same predicate Player.isHurt uses, asked of the companion's body.
			return body != null && body.getHealth() > 0.0f && body.getHealth() < body.getMaxHealth();
		}

		@Override
		public float getHealth() {
			return body == null ? 20.0f : body.getHealth();
		}

		@Override
		public void setHealth(float health) {
			if (body != null) body.setHealth(health);
		}

		@Override
		public void heal(float amount) {
			if (body != null) body.heal(amount);
		}

		@Override
		public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source,
				float amount) {
			return body != null && body.hurtServer(level, source, amount);
		}

		@Override
		public boolean hasInfiniteMaterials() {
			return false;
		}

		@Override
		public boolean addEffect(MobEffectInstance effect, Entity source) {
			return body != null && body.addEffect(effect, source);
		}

		@Override
		public boolean removeAllEffects() {
			return body != null && body.removeAllEffects();
		}

		@Override
		public boolean removeEffect(Holder<MobEffect> effect) {
			return body != null && body.removeEffect(effect);
		}

		@Override
		public Collection<MobEffectInstance> getActiveEffects() {
			return body == null ? List.of() : body.getActiveEffects();
		}

		@Override
		public Map<Holder<MobEffect>, MobEffectInstance> getActiveEffectsMap() {
			return body == null ? Map.of() : body.getActiveEffectsMap();
		}

		@Override
		public boolean hasEffect(Holder<MobEffect> effect) {
			return body != null && body.hasEffect(effect);
		}

		@Override
		public MobEffectInstance getEffect(Holder<MobEffect> effect) {
			return body == null ? null : body.getEffect(effect);
		}

		/** The game's own sprint rule: {@code FoodConstants.SPRINT_LEVEL}, or flight. */
		boolean maySprint() {
			return this.hasEnoughFoodToDoExhaustiveManoeuvres();
		}
	}
}
