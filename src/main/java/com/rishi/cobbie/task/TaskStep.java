package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;

/**
 * One step of a {@link TaskPlan}: an intent plus its execution bookkeeping.
 *
 * <p>Kept separate from {@link ActionIntent} because the intent is "what the model asked for"
 * (immutable, auditable) while the step is "what actually happened to it".
 */
public final class TaskStep {

	public enum Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

	private final ActionIntent intent;
	private final int index;

	private Status status = Status.PENDING;
	private int attempts = 0;
	private String result = "";

	public TaskStep(ActionIntent intent, int index) {
		this.intent = intent;
		this.index = index;
	}

	public ActionIntent intent() {
		return intent;
	}

	public int index() {
		return index;
	}

	public Status status() {
		return status;
	}

	public int attempts() {
		return attempts;
	}

	public String result() {
		return result;
	}

	public boolean isPending() {
		return status == Status.PENDING;
	}

	public boolean isRunning() {
		return status == Status.RUNNING;
	}

	public boolean isSettled() {
		return status == Status.DONE || status == Status.FAILED || status == Status.SKIPPED;
	}

	/** Mark the step as handed to the world after an asynchronous begin(). */
	void markStarted() {
		status = Status.RUNNING;
		result = "in progress";
	}

	/**
	 * Count an attempt before the action is handed over, so a step that fails instantly still burns
	 * retry budget. Without this, a synchronous failure retries forever inside one tick.
	 */
	void beginAttempt() {
		attempts++;
		status = Status.RUNNING;
	}

	void markDone(String note) {
		status = Status.DONE;
		result = note == null ? "" : note;
	}

	void markFailed(String note) {
		status = Status.FAILED;
		result = note == null ? "" : note;
	}

	/** Give it back to the planner for another try, if the retry budget allows. */
	void requeue() {
		status = Status.PENDING;
	}

	/** Never got a chance to run because the plan was cancelled. */
	void markSkipped() {
		status = Status.SKIPPED;
		result = "cancelled before it ran";
	}

	public String describe() {
		StringBuilder sb = new StringBuilder(intent.action());
		if (intent.target() != null && !intent.target().isBlank()) sb.append(' ').append(intent.target());
		sb.append(" [").append(status.name().toLowerCase()).append(')');
		if (attempts > 0) sb.append(" x").append(attempts);
		if (!result.isBlank()) sb.append(" — ").append(result);
		return sb.toString();
	}

	@Override
	public String toString() {
		return describe();
	}
}
