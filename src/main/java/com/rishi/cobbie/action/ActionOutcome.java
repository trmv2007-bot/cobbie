package com.rishi.cobbie.action;

/**
 * The verified result of one attempted action.
 *
 * <p>A <b>success</b> cannot exist without {@link ActionEvidence}: the type has one constructor and it
 * refuses to build a winning outcome that does not say what was observed. That makes "I called the API,
 * so it worked" unwritable rather than merely discouraged — the four false successes of 0.79.0 were all
 * a success built out of an intermediate answer, and none of them could have been built here without
 * someone writing down what they had actually seen.
 *
 * <p>A <b>failure</b> needs no evidence. Nothing has to be proved for "the world would not take it", and
 * demanding proof of a refusal would only tempt a handler to invent one.
 *
 * <p>Whether the evidence is <i>enough</i> is not this type's question — that belongs to the verb. See
 * {@link ActionVocabulary#provesWorld(String)} for the bar and
 * {@code TaskRunner.settle} for the check that applies it: a step is only DONE when its outcome carries
 * evidence the verb's own rule accepts.
 *
 * <p>The summary is what gets fed back to the model on the next turn and shown to the owner, so it is
 * written from the observation rather than from the request: "harvested 2 ripe wheat" is a count of
 * squares that are no longer wheat, never a restatement of having asked for it.
 */
public record ActionOutcome(boolean success, String action, String summary, ActionEvidence evidence) {

	public ActionOutcome {
		if (success && evidence == null) {
			throw new IllegalArgumentException(
					"a success must carry the observation it rests on: " + action + " — " + summary);
		}
	}

	public static ActionOutcome ok(String action, String summary, ActionEvidence evidence) {
		if (evidence == null) {
			throw new IllegalArgumentException("a success must carry the observation it rests on: " + action);
		}
		return new ActionOutcome(true, action, summary, evidence);
	}

	public static ActionOutcome fail(String action, String summary) {
		return new ActionOutcome(false, action, summary, null);
	}

	/** Whether this claim was checked against the world; false for a failure. */
	public boolean aboutTheWorld() {
		return success && evidence != null && evidence.aboutTheWorld();
	}

	public String describe() {
		return (success ? "OK " : "FAILED ") + action + ": " + summary;
	}
}
