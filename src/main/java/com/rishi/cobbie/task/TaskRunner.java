package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;

import java.util.ArrayList;
import java.util.List;

/**
 * The closed-loop planner: PLAN → VALIDATE → EXECUTE → OBSERVE → VERIFY → RETRY → COMPLETE,
 * driven one call per entity tick.
 *
 * <p>It knows nothing about Minecraft. Everything it does goes through {@link Executor}, which the
 * entity implements — that is what makes the whole planner unit-testable without a game running.
 *
 * <p>Guarantees the model cannot talk its way out of:
 * <ul>
 *   <li>at most {@code maxSteps} steps, each retried at most {@code maxRetriesPerStep} times,</li>
 *   <li>a hard wall-clock deadline ({@link TaskPlan.Status#EXPIRED}),</li>
 *   <li>only verbs in {@link ActionVocabulary} are ever handed to the executor,</li>
 *   <li>a step is only DONE when the executor's verified {@link ActionOutcome} says so —
 *       "I called setBlock" is not success, "the block at that position is now that block" is,</li>
 *   <li>one report is produced per plan, so the completion message cannot spam the chat.</li>
 * </ul>
 */
public final class TaskRunner {

	/** Everything a runner can ask the world to do. Implemented by {@code CobbieEntity}. */
	public interface Executor {

		/**
		 * Start (or synchronously complete) one intent.
		 *
		 * @return the final verified outcome when the action completes immediately, or
		 *         {@code null} when it is asynchronous — in that case the runner waits for
		 *         {@link #poll()} before judging the step.
		 */
		ActionOutcome begin(ActionIntent intent);

		/** @return the outcome of the asynchronous action once it settles, else {@code null}. */
		ActionOutcome poll();

		/** Abort whatever is in flight (used on cancel / despawn). */
		void interrupt();
	}

	/** How many instructions may wait behind the running one. */
	private static final int MAX_QUEUED_PLANS = 8;

	private final Executor executor;
	private final int maxSteps;
	private final int maxRetriesPerStep;
	private final long durationMillis;

	private TaskPlan plan;
	/** Requests that arrived while he was busy. One plan at a time, but nothing is thrown away. */
	private final java.util.Deque<TaskPlan> queued = new java.util.ArrayDeque<>();
	private String pendingReport;
	private boolean reported;

	public TaskRunner(Executor executor, int maxSteps, int maxRetriesPerStep, long durationMillis) {
		this.executor = executor;
		this.maxSteps = Math.max(1, Math.min(ActionVocabulary.MAX_ACTIONS, maxSteps));
		this.maxRetriesPerStep = Math.max(0, maxRetriesPerStep);
		this.durationMillis = durationMillis;
	}

	/**
	 * Validate and adopt a plan.
	 *
	 * @return null when accepted, otherwise the reason it was rejected (so the caller can tell the
	 *         player instead of silently doing nothing).
	 */
	public String start(String request, List<ActionIntent> intents) {
		if (intents == null || intents.isEmpty()) return "nothing to do";
		if (queued.size() >= MAX_QUEUED_PLANS) {
			return "I already have " + queued.size() + " jobs waiting; say stop to clear them";
		}

		List<ActionIntent> accepted = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		for (ActionIntent intent : intents) {
			if (accepted.size() >= maxSteps) {
				rejected.add("dropped " + (intents.size() - accepted.size()) + " step(s) over the limit of " + maxSteps);
				break;
			}
			if (intent == null || !ActionVocabulary.isAllowed(intent.action())) {
				rejected.add("unknown verb " + (intent == null ? "null" : intent.action()));
				continue;
			}
			accepted.add(intent);
		}
		if (accepted.isEmpty()) {
			return "no valid steps (" + String.join("; ", rejected) + ")";
		}

		TaskPlan candidate = new TaskPlan(summarise(request), accepted, maxRetriesPerStep, durationMillis);
		candidate.setStatus(TaskPlan.Status.VALIDATED);
		for (String problem : rejected) {
			candidate.note(problem);
		}
		// One plan runs at a time; anything else waits its turn instead of being refused, because a
		// busy companion that drops your instruction is indistinguishable from one that ignored it.
		if (plan != null && !plan.isFinished()) {
			queued.addLast(candidate);
			return null;
		}
		adopt(candidate);
		return null;
	}

	/** Call once per entity tick. Never throws: a broken executor becomes a failed plan. */
	public void tick(long nowMillis) {
		try {
			tickUnsafe(nowMillis);
		} catch (Exception e) {
			if (plan != null && !plan.isFinished()) {
				plan.note("executor error " + e.getClass().getSimpleName());
				plan.finishAsFailure("internal error while acting");
				queueReport();
			}
		}
	}

	private void adopt(TaskPlan next) {
		this.plan = next;
		this.pendingReport = null;
		this.reported = false;
	}

	private void tickUnsafe(long nowMillis) {
		if ((plan == null || plan.isFinished()) && !queued.isEmpty()) {
			adopt(queued.pollFirst());
		}
		if (plan == null || plan.isFinished()) return;

		if (plan.isExpired(nowMillis)) {
			TaskStep running = plan.running();
			if (running != null) {
				running.markFailed("timed out");
				executor.interrupt();
			}
			plan.setStatus(TaskPlan.Status.EXPIRED);
			plan.note("deadline of " + (durationMillis / 1000L) + "s reached");
			queueReport();
			return;
		}

		// Drive as many steps as the world settles within this tick (instant actions finish here),
		// but stop at the first asynchronous hand-off. The guard bounds the loop absolutely.
		// A step can requeue itself up to its retry budget, so the bound must allow that many
		// passes or a retrying plan trips the guard and dies with 'planner guard tripped'.
		int guard = plan.steps().size() * (maxRetriesPerStep + 2) + 8;
		while (guard-- > 0) {
			if (plan.isFinished()) return;

			// 1. Observe a step we already handed to the world.
			TaskStep running = plan.running();
			if (running != null) {
				ActionOutcome outcome = executor.poll();
				if (outcome == null) return;      // still walking / mining / swinging
				settle(running, outcome);
				continue;
			}

			// 2. Nothing in flight: start the next pending step.
			TaskStep next = plan.nextPending();
			if (next == null) {
				plan.setStatus(plan.failedCount() > 0 ? TaskPlan.Status.FAILED : TaskPlan.Status.COMPLETED);
				queueReport();
				return;
			}
			plan.setStatus(TaskPlan.Status.EXECUTING);

			if (!ActionVocabulary.isAllowed(next.intent().action())) {
				next.markFailed("verb not allowed");
				plan.finishAsFailure("step " + (next.index() + 1) + " used a forbidden verb");
				queueReport();
				return;
			}

			next.beginAttempt();
			ActionOutcome immediate = executor.begin(next.intent());
			if (immediate == null) {
				next.markStarted();   // asynchronous: the result arrives through poll()
				return;
			}
			settle(next, immediate);
		}
		// Only reachable if the executor keeps answering instantly past the plan length.
		plan.finishAsFailure("planner guard tripped");
		queueReport();
	}

	/** VERIFY: only a successful, re-read-confirmed outcome advances the plan. */
	private void settle(TaskStep step, ActionOutcome outcome) {
		if (outcome == null) {
			outcome = ActionOutcome.fail(step.intent().action(), "no result reported");
		}
		String action = step.intent().action();
		boolean worldChange = ActionVocabulary.isWorldChanging(action);

		if (outcome.success()) {
			step.markDone(outcome.summary());
			plan.note(action + ": " + outcome.summary());
			return;
		}

		// Failure. Non-world actions (talk, look, wait) are not worth retrying.
		if (!worldChange) {
			step.markFailed(outcome.summary());
			plan.note(action + " skipped: " + outcome.summary());
			return;
		}
		if (step.attempts() <= maxRetriesPerStep) {
			plan.recordRetry();
			plan.note("retrying " + action + " (" + outcome.summary() + ")");
			step.requeue();
			return;
		}
		step.markFailed(outcome.summary());
		if (isFatal(outcome.summary())) {
			plan.finishAsFailure("step " + (step.index() + 1) + " (" + action + ") cannot be done: " + outcome.summary());
			queueReport();
			return;
		}
		// A step that will not work should not cancel the rest of the job: he carries on with what
		// remains and reports the failure alongside the work that did get done.
		plan.note(action + " gave up after " + step.attempts() + " attempt(s): " + outcome.summary()
				+ " (continuing with the rest)");
	}

	/** Some failures are not worth continuing past: a permission refusal means the plan is wrong. */
	private static boolean isFatal(String summary) {
		if (summary == null) return false;
		String s = summary.toLowerCase(java.util.Locale.ROOT);
		return s.contains("not permitted") || s.contains("not something i can do")
				|| s.contains("my owner has not allowed") || s.contains("that is not something");
	}

	private void queueReport() {
		if (plan != null) {
			this.pendingReport = plan.report();
		}
	}

	/** @return the one-shot completion report for the finished plan, or null. Clears it. */
	public String takeReport() {
		if (reported || pendingReport == null) return null;
		reported = true;
		String r = pendingReport;
		pendingReport = null;
		return r;
	}

	public boolean hasActiveTask() {
		return plan != null && !plan.isFinished();
	}

	public String currentTaskDescription() {
		if (plan == null) return "none";
		if (plan.isFinished()) return "last: " + plan.progress();
		return plan.progress();
	}

	public TaskPlan plan() {
		return plan;
	}

	/** Cancel the active plan. Returns false when there was nothing to cancel. */
	public boolean cancel(String reason) {
		if (plan == null || plan.isFinished()) return false;
		TaskStep running = plan.running();
		if (running != null) {
			running.markFailed("cancelled");
			executor.interrupt();
		}
		for (TaskStep s : plan.steps()) {
			if (s.isPending()) s.markSkipped();
		}
		queued.clear();
		plan.setStatus(TaskPlan.Status.CANCELLED);
		plan.note(reason == null || reason.isBlank() ? "cancelled by owner" : reason);
		queueReport();
		return true;
	}

	private static String summarise(String request) {
		if (request == null || request.isBlank()) return "unspecified task";
		String s = request.replaceAll("\\s+", " ").trim();
		return s.length() > 80 ? s.substring(0, 80) + "..." : s;
	}
}
