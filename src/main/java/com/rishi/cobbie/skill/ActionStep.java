package com.rishi.cobbie.skill;

/**
 * One step inside a skill — mirrors ActionIntent fields.
 */
public record ActionStep(
        String action,
        String target,
        int x, int y, int z,
        String data
) {}
