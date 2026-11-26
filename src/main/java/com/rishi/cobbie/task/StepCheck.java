package com.rishi.cobbie.task;

/**
 * What the world says about a step's preconditions at the moment the step is about to run.
 *
 * <p>A plan is a list of coordinates in one world, and the world does not hold still for it: another
 * player closes the door he was walking to, breaks the log he was sent for, empties the chest, or puts
 * a block where he was about to build. The plan was made against the world as it was, so every step
 * has to be asked again whether it is still the step its plan was made for — at the moment it runs,
 * not when the plan was adopted or offered.
 *
 * <p>Four answers, and the difference between the last two is the whole point:
 * <ul>
 *   <li>{@link Verdict#READY} — he looked, and the thing the step depends on is as the plan left it.</li>
 *   <li>{@link Verdict#SATISFIED} — the objective is already true, so the world has done this step.
 *       That is a completion, honestly reported, not a failure and not a claim about work he did.</li>
 *   <li>{@link Verdict#BROKEN} — something the step needed has changed, and the step cannot run as it
 *       is written. {@link #changed} says what, in words a person reads.</li>
 *   <li>{@link Verdict#UNKNOWN} — he could not look: an unloaded chunk, a container that answers
 *       nothing. This is deliberately its own answer rather than either of the others, because
 *       "I cannot see it" is not evidence that it changed and must never be read as evidence that it
 *       is done. The step runs and fails with its own reason.</li>
 * </ul>
 */
public record StepCheck(Verdict verdict, String changed) {

	public enum Verdict { READY, SATISFIED, BROKEN, UNKNOWN }

	private static final StepCheck READY = new StepCheck(Verdict.READY, "");

	public static StepCheck ready() {
		return READY;
	}

	/** The objective already holds: the world did this step. */
	public static StepCheck satisfied(String why) {
		return new StepCheck(Verdict.SATISFIED, why == null ? "" : why);
	}

	/** The precondition the step needs no longer holds, and this is what changed. */
	public static StepCheck broken(String changed) {
		return new StepCheck(Verdict.BROKEN,
				changed == null || changed.isBlank() ? "the world changed under that step" : changed);
	}

	/** No look was possible. Never a satisfaction, never a refusal. */
	public static StepCheck unknown(String why) {
		return new StepCheck(Verdict.UNKNOWN, why == null ? "" : why);
	}

	public boolean isReady() {
		return verdict == Verdict.READY;
	}

	public boolean isSatisfied() {
		return verdict == Verdict.SATISFIED;
	}

	public boolean isBroken() {
		return verdict == Verdict.BROKEN;
	}

	public boolean isUnknown() {
		return verdict == Verdict.UNKNOWN;
	}
}
