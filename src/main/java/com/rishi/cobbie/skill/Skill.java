package com.rishi.cobbie.skill;

import java.util.List;

/**
 * A named, reusable procedure: what it is for plus the ordered steps that
 * worked. Success/failure counts steer which skill the brain reaches for, and
 * {@code enabled} is the owner's on/off switch (a skill that misbehaves can be
 * muted without deleting it).
 */
public record Skill(
        String name,
        String description,
        List<ActionStep> steps,
        int successes,
        int failures,
        boolean enabled,
        String learnedIn) {

	public Skill {
		if (steps == null) steps = List.of();
		description = description == null ? "" : description;
		learnedIn = learnedIn == null ? "" : learnedIn;
	}

	/** The constructor every caller had before skills recorded where they came from. */
	public Skill(String name, String description, List<ActionStep> steps, int successes, int failures,
			boolean enabled) {
		this(name, description, steps, successes, failures, enabled, "");
	}

	/** Total times this skill has been run to a verdict. */
	public int trials() {
		return Math.max(0, successes) + Math.max(0, failures);
	}

	/**
	 * Laplace-smoothed success rate in (0,1): a brand new skill scores 0.5, so it
	 * gets tried a couple of times before a proven one is pushed aside.
	 */
	public double reliability() {
		return (successes + 1.0) / (successes + failures + 2.0);
	}
}
