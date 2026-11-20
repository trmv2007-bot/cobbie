package com.rishi.cobbie.world;

import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * Immutable snapshot of what Cobbie perceives around him.
 * Built each tick / chat event by WorldPerception and fed into the LLM prompt.
 */
public record WorldSnapshot(
        BlockPos position,
        String biome,
        long timeOfDay,
        List<String> nearbyBlockNames,
        List<String> nearbyEntityTypes,
        List<String> nearbyPlayerNames,
        double health,
        List<String> inventoryItemNames
) {}
