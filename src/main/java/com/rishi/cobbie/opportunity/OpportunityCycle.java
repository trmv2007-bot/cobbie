package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.entity.CobbieEntity;
import net.minecraft.server.MinecraftServer;

/**
 * The whole loop, in one place: offer, decide, run, and write down what happened.
 *
 * <p>This is the only path from a shortlist to a plan, and it is deliberately small enough to read in one
 * sitting, because the split it enforces is the point of the slice:
 *
 * <ul>
 *   <li>the <b>assessor</b> decides what is possible ({@link OpportunityAssessor});</li>
 *   <li>the <b>model</b> decides what is worthwhile ({@link OpportunitySelector});</li>
 *   <li>the <b>decision</b> is checked once more against the list it was given, so an id nobody offered
 *       can never start anything here;</li>
 *   <li>the <b>executor</b> — the same plan runner every owner instruction uses — decides what happened.</li>
 * </ul>
 *
 * <p><b>One log line per decision</b>, naming the shortlist's ids and what was chosen, so a session can be
 * read back afterwards: that line is the instrument the next slice measures against, and it is written
 * before the plan starts rather than after, so a crash mid-job still leaves the choice on record.
 */
public final class OpportunityCycle {

	/** The last decision as a person would read it; {@code /cobbie opportunities} prints it. */
	private static volatile String lastDecision = "nothing asked yet";

	private OpportunityCycle() {}

	public static String lastDecision() {
		return lastDecision;
	}

	/** Ask the model about this shortlist; the answer comes back on the server thread and is applied. */
	public static void begin(CobbieEntity cobbie, Shortlist list, MinecraftServer server) {
		if (cobbie == null || list == null || list.isEmpty() || server == null) return;
		OpportunitySelector.ask(cobbie, list).thenAccept(decision -> server.execute(() -> {
			if (cobbie.isRemoved()) return;
			apply(cobbie, list, decision);
		}));
	}

	/**
	 * Turn a decision into work. Server thread only: it starts plans.
	 *
	 * <p>A decision naming something the list does not hold is a rejection, not a pick — the same rule as
	 * an unparseable answer, applied at the last moment it can be applied.
	 *
	 * @return null when a plan started, otherwise a line saying why nothing did
	 */
	public static String apply(CobbieEntity cobbie, Shortlist list, OpportunitySelector.Decision decision) {
		if (cobbie == null || list == null) return "nothing to choose from";
		OpportunitySelector.Decision settled = decision == null
				? OpportunitySelector.Decision.failedClosed("no answer arrived")
				: decision;
		Opportunity chosen = settled.isWait() ? null : list.byId(settled.opportunityId());
		if (!settled.isWait() && chosen == null) {
			settled = OpportunitySelector.Decision.failedClosed(
					"chose '" + settled.opportunityId() + "', which was not on the list");
		}

		String line = "offered [" + list.describe() + "] -> " + settled.describe()
				+ " [" + settled.origin().label() + "]";
		lastDecision = line;
		Cobbie.LOGGER.info("[opportunity] {}", line);

		int now = cobbie.tickCount;
		if (settled.isWait()) {
			cobbie.opportunityCadence().waited(now);
			return "waiting: " + settled.why();
		}
		String refusal = cobbie.startOpportunity(chosen);
		if (refusal != null) {
			lastDecision = line + " (refused: " + refusal + ")";
			Cobbie.LOGGER.warn("[opportunity] {} refused when it started: {}", line, refusal);
			cobbie.opportunityCadence().waited(now);
			return refusal;
		}
		cobbie.opportunityCadence().chosen(now);
		return null;
	}

	/** Nothing could be started at all, so no model call was spent: worth a line of its own. */
	public static void nothingOffered(CobbieEntity cobbie, String why) {
		lastDecision = "nothing offered (" + why + ")";
		Cobbie.LOGGER.info("[opportunity] nothing offered ({})", why);
	}
}
