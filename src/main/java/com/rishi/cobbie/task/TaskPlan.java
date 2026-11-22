package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bounded, ordered plan with a lifecycle:
 * {@code PLANNED -> VALIDATED -> EXECUTING -> (COMPLETED | FAILED | CANCELLED | EXPIRED)}.
 *
 * <p>Everything about a plan has a ceiling — steps, retries per step, wall-clock duration — because
 * the thing producing them is a language model that can hallucinate an endless to-do list.
 */
public final class TaskPlan {

	public enum Status { PLANNED, VALIDATED, EXECUTING, COMPLETED, FAILED, CANCELLED, EXPIRED }

	private static long sequence = 0L;

	private final long id;
	private final String request;
	private final List<TaskStep> steps = new ArrayList<>();
	private final long createdAtMillis;
	private final long deadlineMillis;
	private final int maxRetriesPerStep;

	private Status status = Status.PLANNED;
	private String failureReason = "";
	private int totalRetries = 0;
	private final StringBuilder log = new StringBuilder();

	public TaskPlan(String request, List<ActionIntent> intents, int maxRetriesPerStep, long durationMillis) {
		this.id = ++sequence;
		this.request = request == null ? "" : request;
		this.maxRetriesPerStep = Math.max(0, maxRetriesPerStep);
		this.createdAtMillis = System.currentTimeMillis();
		this.deadlineMillis = createdAtMillis + Math.max(1000L, durationMillis);
		if (intents != null) {
			int i = 0;
			for (ActionIntent intent : intents) {
				steps.add(new TaskStep(intent, i++));
			}
		}
	}

	/**
	 * Add work to a plan that is already running. The steps join the same job rather than forming a
	 * second one, so "and get coal too" is acted on inside what he is already doing.
	 *
	 * @return how many steps were added
	 */
	public int addPending(List<ActionIntent> intents) {
		if (intents == null || intents.isEmpty()) return 0;
		int added = 0;
		for (ActionIntent intent : intents) {
			if (intent == null) continue;
			steps.add(new TaskStep(intent, steps.size()));
			added++;
		}
		return added;
	}

	public long id() {
		return id;
	}

	public String request() {
		return request;
	}

	public Status status() {
		return status;
	}

	public List<TaskStep> steps() {
		return Collections.unmodifiableList(steps);
	}

	public int maxRetriesPerStep() {
		return maxRetriesPerStep;
	}

	public int totalRetries() {
		return totalRetries;
	}

	public boolean isFinished() {
		return status == Status.COMPLETED || status == Status.FAILED
				|| status == Status.CANCELLED || status == Status.EXPIRED;
	}

	public boolean isExpired(long nowMillis) {
		return nowMillis > deadlineMillis;
	}

	/** The next step that still needs doing, or null when the plan is exhausted. */
	public TaskStep nextPending() {
		for (TaskStep s : steps) {
			if (s.isPending()) return s;
		}
		return null;
	}

	public TaskStep running() {
		for (TaskStep s : steps) {
			if (s.isRunning()) return s;
		}
		return null;
	}

	public int doneCount() {
		int n = 0;
		for (TaskStep s : steps) {
			if (s.status() == TaskStep.Status.DONE) n++;
		}
		return n;
	}

	public int failedCount() {
		int n = 0;
		for (TaskStep s : steps) {
			if (s.status() == TaskStep.Status.FAILED) n++;
		}
		return n;
	}

	void setStatus(Status next) {
		this.status = next;
	}

	void note(String text) {
		if (log.length() > 1200) return; // bounded log, never grows without limit
		if (log.length() > 0) log.append("; ");
		log.append(text);
	}

	void recordRetry() {
		totalRetries++;
	}

	void finishAsFailure(String reason) {
		this.failureReason = reason == null ? "" : reason;
		this.status = Status.FAILED;
	}

	public String failureReason() {
		return failureReason;
	}

	public String log() {
		return log.toString();
	}

	/** Short progress line for {@code /cobbie status} and the perception block. */
	public String progress() {
		return request + " (" + doneCount() + "/" + steps.size() + " done"
				+ (failedCount() > 0 ? ", " + failedCount() + " failed" : "") + ")";
	}

	/** The report handed back to the model and to the owner when the plan settles. */
	public String report() {
		StringBuilder sb = new StringBuilder();
		sb.append(status.name().toLowerCase()).append(" — ").append(request).append(": ");
		sb.append(doneCount()).append('/').append(steps.size()).append(" steps done");
		if (failedCount() > 0) sb.append(", ").append(failedCount()).append(" failed");
		if (totalRetries > 0) sb.append(", ").append(totalRetries).append(" retries");
		if (!failureReason.isBlank()) sb.append(" (").append(failureReason).append(')');
		sb.append(". Details: ");
		boolean first = true;
		for (TaskStep s : steps) {
			if (!first) sb.append(" | ");
			first = false;
			sb.append(s.describe());
		}
		return sb.length() > 900 ? sb.substring(0, 900) + "..." : sb.toString();
	}
}
