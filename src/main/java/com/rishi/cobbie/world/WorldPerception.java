package com.rishi.cobbie.world;

import com.rishi.cobbie.entity.CobbieEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.stream.*;

/**
 * Gathers what Cobbie can see around him: blocks, entities, players, biome,
 * time, health, inventory. Returns an immutable WorldSnapshot.
 */
public final class WorldPerception {

	private WorldPerception() {}

	public static WorldSnapshot snapshot(CobbieEntity entity) {
		Level level = entity.level();
		BlockPos pos = entity.blockPosition();

		// Nearby block names in a 5x5x5 around the entity (sampled)
		List<String> nearbyBlockNames = new ArrayList<>();
		for (int x = -2; x <= 2; x++) {
			for (int y = -1; y <= 2; y++) {
				for (int z = -2; z <= 2; z++) {
					BlockPos p = pos.offset(x, y, z);
					String name = level.getBlockState(p).getBlock().toString();
					if (!nearbyBlockNames.contains(name)) nearbyBlockNames.add(name);
				}
			}
		}

		// Nearby entities (not Cobbie himself) within 8 blocks
		List<String> nearbyEntityTypes = level.getEntitiesOfClass(Entity.class,
				new AABB(pos).inflate(8.0),
				e -> e != entity).stream()
				.map(e -> e.getType().toString())
				.distinct()
				.limit(12)
				.toList();

		// Nearby players within 16 blocks
		List<String> nearbyPlayerNames = level.players().stream()
				.filter(p -> p.distanceToSqr(entity) <= 256.0)
				.map(p -> p.getName().getString())
				.toList();

		String biome = level.getBiome(pos).value().toString();
		long timeOfDay = level.getGameTime() % 24000;

		// Real inventory contents
		List<String> inventoryItemNames = new ArrayList<>();
		for (int i = 0; i < entity.getInventoryContainer().getContainerSize(); i++) {
			var stack = entity.getInventoryContainer().getItem(i);
			if (!stack.isEmpty()) {
				inventoryItemNames.add(stack.getHoverName().getString() + " x" + stack.getCount());
			}
		}

		return new WorldSnapshot(
				pos,
				biome,
				timeOfDay,
				nearbyBlockNames,
				nearbyEntityTypes,
				nearbyPlayerNames,
				entity.getHealth(),
				inventoryItemNames
		);
	}
}
