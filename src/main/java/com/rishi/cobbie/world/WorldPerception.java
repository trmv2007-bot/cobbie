package com.rishi.cobbie.world;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.world.WorldSnapshot.BlockNote;
import com.rishi.cobbie.world.WorldSnapshot.EntityNote;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the live world into a {@link WorldSnapshot}. This is the only class in
 * the mod that reads Minecraft state for the prompt; everything downstream is
 * plain data so it stays testable.
 *
 * Rules of the house:
 *  - never throw (a broken perception must not silence Cobbie) — fall back to
 *    {@link WorldSnapshot#unknown()},
 *  - never force-load a chunk ({@link Level#isLoaded(BlockPos)} guard),
 *  - keep it cheap: one entity scan, one bounded block scan,
 *  - output is small on purpose (see {@link WorldSnapshot} caps).
 */
public final class WorldPerception {

	/** Block scan box: 7 wide x 5 tall x 7 deep, centred on Cobbie's feet. */
	private static final int SCAN_X = 3, SCAN_Y_DOWN = 2, SCAN_Y_UP = 2, SCAN_Z = 3;
	/** Entities are reported inside this many blocks; players are named a bit further out. */
	private static final double ENTITY_RANGE = 12.0;
	private static final double PLAYER_RANGE = 24.0;
	private static final int MAX_STACK_LINES = 16;
	/** How many merchants get their offers listed, and how many offers each. Both on purpose small. */
	private static final int MAX_DETAILED_MERCHANTS = 2;
	private static final int MAX_OFFERS_PER_MERCHANT = 3;
	/** How much of a custom item name survives into the prompt line. */
	private static final int MAX_NAME_CHARS = 24;

	private WorldPerception() {}

	public static WorldSnapshot snapshot(CobbieEntity entity) {
		return snapshot(entity, "none", "idle", "none");
	}

	public static WorldSnapshot snapshot(CobbieEntity entity, String currentTask,
			String currentAction, String lastActionResult) {
		if (entity == null) return WorldSnapshot.unknown();
		try {
			Level level = entity.level();
			if (level == null) return WorldSnapshot.unknown();

			BlockPos pos = entity.blockPosition();
			List<Entity> seen = scanEntities(entity, level, pos);

			return new WorldSnapshot(
					level.dimension().identifier().toString(),
					biome(level, pos),
					level.getGameTime() % 24000L,
					level.isRaining(),
					level.isThundering(),
					pos.getX(), pos.getY(), pos.getZ(),
					entity.getHealth(),
					entity.getMaxHealth(),
					ownerFoodLevel(entity, level),
					heldItemId(entity),
					nearbyBlocks(entity, level, pos),
					entityNotes(entity, seen),
					playerNames(seen),
					inventoryIds(entity),
					currentTask, currentAction, lastActionResult);
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Cobbie perception failed: {}", t.toString());
			return WorldSnapshot.unknown();
		}
	}

	// ----- entities & players (single scan, then partitioned) -----

	private static List<Entity> scanEntities(CobbieEntity cobbie, Level level, BlockPos pos) {
		AABB box = new AABB(pos).inflate(PLAYER_RANGE, 16.0, PLAYER_RANGE);
		List<Entity> out = new ArrayList<>();
		for (Entity e : level.getEntities(cobbie, box, cand -> useful(cobbie, cand))) {
			out.add(e);
		}
		out.sort(Comparator.comparingDouble(e -> cobbie.distanceToSqr(e)));
		return out;
	}

	/** Drop the noise: XP orbs, arrows, thrown things, dropped items, armour stands. */
	private static boolean useful(CobbieEntity cobbie, Entity e) {
		if (e == null || e == cobbie || e.isRemoved()) return false;
		if (e.distanceToSqr(cobbie) > PLAYER_RANGE * PLAYER_RANGE) return false;
		if (e instanceof Player) return true;
		return !(e instanceof ItemEntity || e instanceof ExperienceOrb
				|| e instanceof Projectile || e instanceof ArmorStand);
	}

	private static List<EntityNote> entityNotes(CobbieEntity cobbie, List<Entity> seen) {
		List<EntityNote> out = new ArrayList<>();
		UUID owner = cobbie.getOwner();
		int villagersDetailed = 0;
		for (Entity e : seen) {
			if (e instanceof Player) continue;
			if (out.size() >= WorldSnapshot.MAX_ENTITIES) break;
			if (e.distanceToSqr(cobbie) > ENTITY_RANGE * ENTITY_RANGE) continue;
			double dist = Math.sqrt(e.distanceToSqr(cobbie));
			boolean hostile = e instanceof Enemy || (e instanceof Mob m && m.getTarget() == cobbie);
			double hp = e instanceof LivingEntity le ? le.getHealth() : -1.0;
			out.add(new EntityNote(typeId(e), e.getX(), e.getY(), e.getZ(), dist, hostile, hp,
					owner != null && owner.equals(e.getUUID()), merchantDetail(e, villagersDetailed)));
			if (e instanceof net.minecraft.world.item.trading.Merchant) villagersDetailed++;
		}
		if (villagersDetailed > MAX_DETAILED_MERCHANTS) {
			// A village of ten must not fill the prompt with eight more price lists.
			out.add(new EntityNote("+" + (villagersDetailed - MAX_DETAILED_MERCHANTS) + " more villager(s)",
					0, 0, 0, 0, false, -1, false, ""));
		}
		return out;
	}

	/**
	 * What a merchant sells, in the shape a person would say it.
	 *
	 * <p>{@code minecraft:villager@2.0} says there is a villager and nothing about whether the pumpkin
	 * pie is on offer; the 0.42.0 verb-choice run caught him declining a buy for exactly that reason
	 * ("I don't see that trade available") while the verb behind it worked.
	 *
	 * <p>Capped hard on purpose: three offers, the price of the cheap half only, and only for the two
	 * nearest merchants. A prompt that lists everything stops being read.
	 */
	private static String merchantDetail(Entity e, int alreadyDetailed) {
		if (!(e instanceof net.minecraft.world.item.trading.Merchant merchant)) return "";
		if (alreadyDetailed >= MAX_DETAILED_MERCHANTS) return "";
		var offers = merchant.getOffers();
		if (offers == null || offers.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("sells ");
		int shown = 0;
		for (var offer : offers) {
			if (shown >= MAX_OFFERS_PER_MERCHANT) break;
			if (offer.getUses() >= offer.getMaxUses()) continue;
			if (shown > 0) sb.append(", ");
			ItemStack result = offer.getResult();
			sb.append(result.getCount()).append(' ')
					.append(BuiltInRegistries.ITEM.getKey(result.getItem()).getPath())
					.append(" for ").append(offer.getCostA().getCount()).append(' ')
					.append(BuiltInRegistries.ITEM.getKey(offer.getCostA().getItem()).getPath());
			if (!offer.getCostB().isEmpty()) {
				sb.append(" + ").append(offer.getCostB().getCount()).append(' ')
						.append(BuiltInRegistries.ITEM.getKey(offer.getCostB().getItem()).getPath());
			}
			shown++;
		}
		return shown == 0 ? "" : sb.toString();
	}

	private static List<String> playerNames(List<Entity> seen) {
		List<String> out = new ArrayList<>();
		for (Entity e : seen) {
			if (out.size() >= WorldSnapshot.MAX_PLAYERS) break;
			if (e instanceof Player p) out.add(p.getName().getString());
		}
		return out;
	}

	private static String typeId(Entity e) {
		try {
			return EntityType.getKey(e.getType()).toString();
		} catch (Throwable t) {
			return e.getName().getString();
		}
	}

	// ----- blocks: distinct ids only, nearest instance of each -----

	private static List<BlockNote> nearbyBlocks(CobbieEntity cobbie, Level level, BlockPos centre) {
		Map<String, BlockNote> nearest = new LinkedHashMap<>();
		for (int dx = -SCAN_X; dx <= SCAN_X; dx++) {
			for (int dy = -SCAN_Y_DOWN; dy <= SCAN_Y_UP; dy++) {
				for (int dz = -SCAN_Z; dz <= SCAN_Z; dz++) {
					BlockPos p = centre.offset(dx, dy, dz);
					if (!level.isLoaded(p)) continue;
					BlockState state = level.getBlockState(p);
					if (state == null || state.isAir()) continue;
					String id;
					try {
						id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					} catch (Throwable t) {
						continue;
					}
					if ("minecraft:air".equals(id)) continue;
					double dist = Math.sqrt(cobbie.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
					BlockNote note = new BlockNote(id, p.getX(), p.getY(), p.getZ(), dist, solid(level, state, p));
					BlockNote best = nearest.get(id);
					if (best == null || note.distance() < best.distance()) nearest.put(id, note);
				}
			}
		}
		List<BlockNote> out = new ArrayList<>(nearest.values());
		out.sort(Comparator.comparingDouble(BlockNote::distance));
		return out.size() > WorldSnapshot.MAX_BLOCKS ? new ArrayList<>(out.subList(0, WorldSnapshot.MAX_BLOCKS)) : out;
	}

	/** True when the block has a real collision box (i.e. Cobbie cannot walk through it). */
	private static boolean solid(Level level, BlockState state, BlockPos pos) {
		try {
			return !state.getCollisionShape(level, pos).isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}

	// ----- self state -----

	private static String biome(Level level, BlockPos pos) {
		try {
			return level.getBiome(pos).unwrapKey()
					.map(k -> k.identifier().getPath())
					.orElse("?");
		} catch (Throwable t) {
			return "?";
		}
	}

	private static String heldItemId(CobbieEntity cobbie) {
		try {
			ItemStack held = cobbie.getMainHandItem();
			if (held == null || held.isEmpty()) return "none";
			return BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		} catch (Throwable t) {
			return "none";
		}
	}

	/**
	 * Cobbie never eats, so hunger is reported for his owner (the person he is
	 * keeping company with); 20 when the owner is not around.
	 */
	private static int ownerFoodLevel(CobbieEntity cobbie, Level level) {
		try {
			UUID owner = cobbie.getOwner();
			if (owner == null) return 20;
			Player p = level.getPlayerByUUID(owner);
			return p == null ? 20 : p.getFoodData().getFoodLevel();
		} catch (Throwable t) {
			return 20;
		}
	}

	private static List<String> inventoryIds(CobbieEntity cobbie) {
		List<String> out = new ArrayList<>();
		Container inv = cobbie.getInventoryContainer();
		if (inv == null) return out;
		try {
			for (int i = 0; i < inv.getContainerSize() && out.size() < MAX_STACK_LINES; i++) {
				ItemStack stack = inv.getItem(i);
				if (stack == null || stack.isEmpty()) continue;
				out.add(describeStack(stack));
			}
		} catch (Throwable t) {
			// partial inventory is still useful
		}
		return out;
	}

	/**
	 * An item as a person would name it: what it is, plus whatever about it changes what can be done
	 * with it.
	 *
	 * <p>The id and count alone are not enough to act on. {@code minecraft:potion x1} is a water
	 * bottle or a swiftness potion and there is no telling which; {@code minecraft:iron_axe x1} is
	 * enchanted or plain, and grinding one is a different request from grinding the other. That was
	 * measured, not guessed: asked to take the sharpness off an axe it was carrying, the model
	 * answered "only my own plain iron axe, which has no Sharpness to strip", which is exactly what
	 * this method used to tell it.
	 *
	 * <p>Short on purpose — three enchantments, a name cut to {@value #MAX_NAME_CHARS} characters,
	 * wear only once it is damaged. A prompt that lists every component stops being read, and the
	 * whole line is one of sixteen.
	 */
	static String describeStack(ItemStack stack) {
		StringBuilder line = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		line.append(" x").append(stack.getCount());

		List<String> state = new ArrayList<>();
		net.minecraft.world.item.alchemy.PotionContents potion =
				stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		if (potion != null) {
			state.add(potion.potion()
					.map(holder -> holder.unwrapKey().map(k -> k.identifier().getPath()).orElse("custom"))
					.orElse("empty"));
		}
		// getEnchantmentsForCrafting is the effective set: it is ENCHANTMENTS on gear and
		// STORED_ENCHANTMENTS on an enchanted book, which is the pair an anvil or a grindstone acts on.
		net.minecraft.world.item.enchantment.ItemEnchantments enchantments =
				net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (enchantments != null && !enchantments.isEmpty()) {
			List<String> named = new ArrayList<>();
			for (var entry : enchantments.entrySet()) {
				if (named.size() >= 3) {
					named.add("…");
					break;
				}
				String key = entry.getKey().unwrapKey()
						.map(k -> k.identifier().getPath())
						.orElse("enchantment");
				named.add(key + " " + entry.getIntValue());
			}
			state.add(String.join(", ", named));
		}
		net.minecraft.network.chat.Component custom =
				stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		if (custom != null) {
			String text = custom.getString();
			state.add("named \"" + (text.length() <= MAX_NAME_CHARS ? text
					: text.substring(0, MAX_NAME_CHARS - 1) + "…") + "\"");
		}
		if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
			state.add("worn " + stack.getDamageValue() + "/" + stack.getMaxDamage());
		}
		if (!state.isEmpty()) line.append(" (").append(String.join("; ", state)).append(')');
		return line.toString();
	}
}
