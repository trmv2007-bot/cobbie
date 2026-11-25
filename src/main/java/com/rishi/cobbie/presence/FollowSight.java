package com.rishi.cobbie.presence;

/**
 * What following does when the owner's chunk is not loaded.
 *
 * <p>The old shape was a bare {@code if (...) return;} in the follow loop: it asked for nothing, targeted nothing,
 * said nothing, and came back on a timer forever. Whatever else is true, that is a companion looping in silence, and
 * silence is the failure this project keeps removing — the same reason the wake-up line exists.
 *
 * <p>So the wait is bounded and named, one episode at a time:
 * <ul>
 *   <li>the first tick his owner is out of sight, he says one line — {@link #UNSEEN_LINE} — and never repeats it for
 *       that episode;</li>
 *   <li>inside the deadline he waits, because a chunk that is loading is not a chunk that is gone;</li>
 *   <li>past the deadline he <b>stops trying</b> and stays idle instead of hammering the pathfinder at nothing;</li>
 *   <li>when the owner's chunk loads again the episode ends and following resumes on the same tick, with the state
 *       cleared so a later disappearance is a new episode and may speak once more.</li>
 * </ul>
 *
 * <p>Pure Java on purpose: this is a decision, not a world read, so the whole boundary is testable without a game.
 * The entity supplies one boolean and one tick count and acts on the answer.
 */
public final class FollowSight {

	/** His wording when he cannot see where they are. Lower case, like every other line of his. */
	public static final String UNSEEN_LINE = "i can't see where you are from here";

	/** How long a chunk has to take before he gives up on reaching them for now. Ten seconds is generous. */
	public static final long DEFAULT_DEADLINE_TICKS = 200;

	/** One tick's answer. */
	public enum Step {
		/** The owner's chunk is loaded: follow normally. */
		VISIBLE,
		/** They have just gone out of sight: say the line, once for this episode. */
		SPEAK,
		/** Out of sight, inside the deadline: wait. */
		WAITING,
		/** Out of sight past the deadline: stop trying and stay idle until their chunk is back. */
		GAVE_UP
	}

	private final long deadlineTicks;
	private long hiddenSince = -1;
	private Step last = Step.VISIBLE;

	public FollowSight() {
		this(DEFAULT_DEADLINE_TICKS);
	}

	public FollowSight(long deadlineTicks) {
		this.deadlineTicks = Math.max(1, deadlineTicks);
	}

	/**
	 * Advance the state by one tick.
	 *
	 * @param ownerVisible whether the owner's chunk is loaded in his world
	 * @param tick         the entity's own tick count; only differences matter
	 * @return what this tick should do
	 */
	public Step onTick(boolean ownerVisible, long tick) {
		if (ownerVisible) {
			// Back in sight: the episode is over, including the right to say the line again next time.
			hiddenSince = -1;
			last = Step.VISIBLE;
			return last;
		}
		if (hiddenSince < 0) {
			hiddenSince = tick;
			last = Step.SPEAK;
			return last;
		}
		last = tick - hiddenSince >= deadlineTicks ? Step.GAVE_UP : Step.WAITING;
		return last;
	}

	/** What the last tick decided, for a caller that has to report the state. */
	public Step last() {
		return last;
	}

	/** True while he has given up: out of sight, past the deadline, and not trying. */
	public boolean gaveUp() {
		return last == Step.GAVE_UP;
	}
}
