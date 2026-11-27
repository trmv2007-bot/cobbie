package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.entity.CobbieEntity;

/**
 * What the scene looks like, in the few buckets that are allowed to wake a new question.
 *
 * <p>The cadence's whole job is <b>not</b> to ask again about a scene that has not changed, so the
 * fingerprint has to be coarse enough to hold still between heartbeats and fine enough that a real
 * change is not missed. Each field is here because someone can name the event it stands for:
 *
 * <table>
 *   <tr><td>{@code ownerBucket}</td><td><b>the owner moved away</b> — 0 inside 4 blocks, 1 to 12, 2 to
 *       32, 3 beyond, 4 not here (offline or another dimension)</td></tr>
 *   <tr><td>{@code healthBucket}</td><td><b>health dropped</b> — his health in fourteenths, so the
 *       fingerprint changes about one full heart of damage</td></tr>
 *   <tr><td>{@code foodBucket}</td><td><b>hunger crossed a line</b> — his food bar in quarters</td></tr>
 *   <tr><td>{@code threats}</td><td><b>a hostile arrived</b> — legal targets in sight within 16 blocks,
 *       capped at 3</td></tr>
 *   <tr><td>{@code bagHash}</td><td><b>the bag changed</b> — what he carries, counted in powers of two,
 *       so picking up one more of something is not a change</td></tr>
 *   <tr><td>{@code busy}</td><td><b>a task started or finished</b> — a plan in flight or an action
 *       under way</td></tr>
 *   <tr><td>{@code planId}</td><td><b>a different job ran</b> — the id of the last plan the runner
 *       created, which moves even when two jobs follow each other without a gap</td></tr>
 *   <tr><td>{@code ownerSpokeTick}</td><td><b>the owner spoke</b> — the last tick anyone asked him for
 *       something</td></tr>
 * </table>
 *
 * <p>Two things are deliberately <b>not</b> in here. The time of day is not: dusk changes what is
 * dangerous, but the plan runner already refuses a fight it cannot win and the assessor re-reads the
 * world whenever it is asked, so a separate wake for the clock would only buy model calls. Nor is his
 * exact coordinates: walking three blocks is not a change in the situation, and a fingerprint that
 * moved every tick would make the cadence a poll.
 */
public record Situation(int ownerBucket, int healthBucket, int foodBucket, int threats, int bagHash,
		boolean busy, int planId, int ownerSpokeTick) {

	/** Distance buckets, in blocks, for the owner: inside 4, inside 12, inside 32, beyond, absent. */
	public static final int OWNER_ABSENT = 4;

	public static Situation of(CobbieEntity cobbie) {
		if (cobbie == null) return new Situation(OWNER_ABSENT, 0, 0, 0, 0, false, 0, 0);
		var owner = cobbie.owner();
		int ownerBucket;
		if (owner == null) {
			ownerBucket = OWNER_ABSENT;
		} else {
			double d = Math.sqrt(cobbie.distanceToSqr(owner));
			ownerBucket = d < 4.0 ? 0 : d < 12.0 ? 1 : d < 32.0 ? 2 : 3;
		}
		double health = cobbie.getMaxHealth() <= 0 ? 0 : cobbie.getHealth() / cobbie.getMaxHealth();
		int healthBucket = (int) Math.round(14.0 * Math.max(0.0, Math.min(1.0, health)));
		int foodBucket = Math.max(0, Math.min(5, cobbie.foodLevel() / 4));
		int planId = cobbie.lastPlan() == null ? 0 : (int) cobbie.lastPlan().id();
		return new Situation(ownerBucket, healthBucket, foodBucket, cobbie.threatsNear(16),
				cobbie.bagFingerprint(), cobbie.isBusy(), planId, cobbie.lastOwnerContactTick());
	}

	/** One line naming exactly which parts changed, for the log that explains a decision. */
	public String changesSince(Situation before) {
		if (before == null) return "nothing asked yet";
		StringBuilder sb = new StringBuilder();
		note(sb, before.ownerBucket() != ownerBucket, "the owner moved");
		note(sb, before.healthBucket() != healthBucket, "his health changed");
		note(sb, before.foodBucket() != foodBucket, "his hunger changed");
		note(sb, before.threats() != threats, "the hostiles changed");
		note(sb, before.bagHash() != bagHash, "his bag changed");
		note(sb, before.busy() != busy, "a job started or finished");
		note(sb, before.planId() != planId, "a different job ran");
		note(sb, before.ownerSpokeTick() != ownerSpokeTick, "the owner spoke");
		return sb.length() == 0 ? "nothing changed" : sb.toString();
	}

	private static void note(StringBuilder sb, boolean changed, String what) {
		if (!changed) return;
		if (sb.length() > 0) sb.append(", ");
		sb.append(what);
	}
}
