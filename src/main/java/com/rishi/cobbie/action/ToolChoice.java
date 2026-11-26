package com.rishi.cobbie.action;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of his tools he raises for a block, decided without a world.
 *
 * <p>Until 0.81.0 the answer was "the best one he owns", scored from a hand-written table of tier names and
 * block-name fragments. That is how a companion breaks your diamond pickaxe on cobblestone: the table had no
 * notion of a job being cheap, so every stone block wore the finest tool in the bag. It was also how he broke
 * obsidian with a wooden pickaxe and reported "nothing dropped" — the table asked whether the *name* of the
 * block looked stony, never whether the game would give him the drops.
 *
 * <p>The rule a person uses instead: <b>the cheapest tool that will do the job, out of the ones that will
 * actually harvest it</b>. Both halves come from the game. {@code harvests} is
 * {@code ItemStack.isCorrectToolForDrops}, which reads the block's own tags and the tool's own material and is
 * false for anything the game would not pay out — so a wooden pickaxe is never chosen for obsidian. The cost is
 * the material's own durability, the only price the game publishes, and it orders the tiers the way a player
 * ranks them: wood before stone before iron before diamond before netherite.
 *
 * <p>Wear is the other half of the same question. A tool with fewer uses left than the job needs does not get
 * raised; the next cheapest one does, and the choice is reported so the owner hears which tool he lent out.
 * The arithmetic of "uses left" is vanilla's, not this class's: a stack at one damage below its maximum is
 * destroyed by its next use, so it can only be trusted for {@code usesLeft - 1} blocks.
 *
 * <p>Pure on purpose, like {@link ItemQuery}: every ambiguity here is arithmetic over what the game said, so
 * the unit tier settles the ordering without booting a world.
 */
public final class ToolChoice {

	/** The hand's own slot, so a tool already raised is a candidate like any other. */
	public static final int HAND = -1;

	/** One tool he is carrying, as the game describes it. */
	public record Candidate(int slot, String id, boolean harvests, int maxDamage, int usesLeft) {
		public Candidate {
			id = id == null ? "" : id;
		}

		/** How much of a cost raising this tool is: the material's durability, and nothing else. */
		int worth() {
			return Math.max(1, maxDamage);
		}

		/** Blocks this tool can be trusted with: the last use is the one that breaks it. */
		int survivable() {
			return Math.max(0, usesLeft - 1);
		}
	}

	/** Why the pick is worth a word to the owner, or {@link #NONE} when it is the obvious choice. */
	public enum Note {
		/** The cheapest tool that can finish the job — nothing to explain. */
		NONE,
		/** A better tool was passed over because it had too little left, so the owner hears about it. */
		STEPPED_DOWN,
		/** Nothing he owns can finish the job; the longest-lasting of them is raised, and warned about. */
		LAST_RESORT,
		/** He owns nothing that harvests this block. */
		MISSING
	}

	/**
	 * The answer. {@code slot} is {@link #HAND}, a bag slot, or -1 with {@link Note#MISSING}; {@code passedOver}
	 * is the slot of the better tool that was skipped on a {@link Note#STEPPED_DOWN}, -1 otherwise.
	 */
	public record Pick(int slot, Note note, int passedOver) {
		public boolean found() {
			return note() != Note.MISSING;
		}

		static Pick missing() {
			return new Pick(-1, Note.MISSING, -1);
		}
	}

	private ToolChoice() {}

	/**
	 * @param bag every tool he could raise, the hand included as {@link #HAND}
	 * @param needed how many blocks this job still has to break — at least one
	 */
	public static Pick choose(List<Candidate> bag, int needed) {
		int job = Math.max(1, needed);
		List<Candidate> usable = new ArrayList<>();
		if (bag != null) {
			for (Candidate candidate : bag) {
				if (candidate != null && candidate.harvests() && candidate.usesLeft() > 0) usable.add(candidate);
			}
		}
		if (usable.isEmpty()) return Pick.missing();

		List<Candidate> lasts = new ArrayList<>();
		for (Candidate candidate : usable) {
			if (candidate.survivable() >= job) lasts.add(candidate);
		}
		if (lasts.isEmpty()) {
			// Nothing can finish it. The one that lasts longest is still the best he has, and saying so is
			// the difference between a worn-out pickaxe and a silent one.
			return new Pick(longestLasting(usable).slot(), Note.LAST_RESORT, -1);
		}

		Candidate chosen = cheapest(lasts);
		Candidate skipped = strongerSkipped(usable, lasts, chosen);
		return skipped == null
				? new Pick(chosen.slot(), Note.NONE, -1)
				: new Pick(chosen.slot(), Note.STEPPED_DOWN, skipped.slot());
	}

	/** The cheapest that can finish: lowest material durability, then the most uses left, then the hand. */
	private static Candidate cheapest(List<Candidate> candidates) {
		Candidate best = null;
		for (Candidate candidate : candidates) {
			if (best == null || cheaper(candidate, best)) best = candidate;
		}
		return best;
	}

	private static boolean cheaper(Candidate a, Candidate b) {
		if (a.worth() != b.worth()) return a.worth() < b.worth();
		if (a.usesLeft() != b.usesLeft()) return a.usesLeft() > b.usesLeft();
		return a.slot() < b.slot();
	}

	/** The strongest tool left out because it was too worn, or null when nothing worth naming was left out. */
	private static Candidate strongerSkipped(List<Candidate> usable, List<Candidate> lasts, Candidate chosen) {
		Candidate skipped = null;
		for (Candidate candidate : usable) {
			if (lasts.contains(candidate) || candidate.worth() <= chosen.worth()) continue;
			if (skipped == null || candidate.worth() > skipped.worth()
					|| (candidate.worth() == skipped.worth() && candidate.usesLeft() > skipped.usesLeft())) {
				skipped = candidate;
			}
		}
		return skipped;
	}

	/** Of tools that cannot finish, the one to wear out: most uses left, then the cheapest. */
	private static Candidate longestLasting(List<Candidate> candidates) {
		Candidate best = null;
		for (Candidate candidate : candidates) {
			if (best == null) {
				best = candidate;
				continue;
			}
			if (candidate.usesLeft() != best.usesLeft()) {
				if (candidate.usesLeft() > best.usesLeft()) best = candidate;
			} else if (cheaper(candidate, best)) {
				best = candidate;
			}
		}
		return best;
	}
}
