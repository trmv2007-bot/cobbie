package com.rishi.cobbie.skill;

import java.util.List;

/**
 * A learned skill: a named sequence of actions that worked in a particular
 * context. Cobbie can replay it when the same situation arises.
 */
public record Skill(
        String name,           // e.g. "mine-coal-then-place-torch"
        String context,        // what situation triggers it (e.g. "dark-cave")
        List<ActionStep> steps, // ordered action sequence
        int successCount,      // how many times it succeeded
        int failureCount       // how many times it failed
) {}
