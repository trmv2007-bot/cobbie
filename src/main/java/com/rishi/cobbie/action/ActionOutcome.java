package com.rishi.cobbie.action;

/**
 * The verified result of one attempted action.
 *
 * <p>Nothing in Cobbie reports success from "I called the API". Every world mutation ends in one
 * of these, built by re-reading the world afterwards (is the block actually air? is the block
 * actually the one I placed? did the target actually take damage?). The summary string is what
 * gets fed back to the model on the next turn and shown to the owner.
 */
public record ActionOutcome(boolean success, String action, String summary) {

	public static ActionOutcome ok(String action, String summary) {
		return new ActionOutcome(true, action, summary);
	}

	public static ActionOutcome fail(String action, String summary) {
		return new ActionOutcome(false, action, summary);
	}

	public String describe() {
		return (success ? "OK " : "FAILED ") + action + ": " + summary;
	}
}
