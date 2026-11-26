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

		/**
		 * Has the world moved on since this step's plan was made?
		 *
		 * <p>Asked at two moments and answered by one rule: once when a plan is adopted, so a project
		 * that went stale while it queued closes as done, and again the moment each step is about to
		 * run, because the world does not hold still for a plan. A default of
		 * {@link StepCheck#ready()} keeps every caller that cannot answer the question exactly as it was.
		 */
		default StepCheck checkStep(ActionIntent intent) {
			return StepCheck.ready();
		}

		/**
		 * Where else this same job could be done, now that the place it named has changed.
		 *
		 * <p>An answer may move the coordinates and nothing else: the runner refuses a substitute that
		 * changes the verb, the target or the quantity, because those are the instruction and the
		 * coordinates are only where it was going to happen. The verdict comes along because it is what
		 * says whether the job has moved elsewhere or been done by somebody else. {@code null} means
		 * there is no honest substitute and the plan should stop and say what changed.
		 */
		default ActionIntent replan(ActionIntent broken, StepCheck why) {
			return null;
		}
	}

	/**
	 * Told about a step the moment it starts and the moment it settles.
	 *
	 * <p>This is how a step gets narrated as it happens instead of being announced in advance: the
	 * runner is the only place that knows both edges of a step, and the entity is the only place
	 * that can speak. Nothing here can change a decision — a listener that throws is swallowed.
	 */
	/** A plan this runner created, by id — so a caller can ask what became of one it started earlier. */
	public TaskPlan planById(long id) {
		for (TaskPlan made : created) {
			if (made.id() == id) return made;
		}
		return null;
	}

	/** The id of the most recently created plan, or -1 when none has been made. Monotonic. */
	public long latestPlanId() {
		TaskPlan last = created.peekLast();
		return last == null ? -1 : last.id();
	}

	/** How many plans have been created since the given id: more than one means a follow-up appeared too. */
	public int plansSince(long id) {
		int count = 0;
		for (TaskPlan made : created) {
			if (made.id() > id) count++;
		}
		return count;
	}

	/** The first plan created after the given id, or null when nothing was created since. */
	public TaskPlan planAfter(long id) {
		for (TaskPlan made : created) {
			if (made.id() > id) return made;
		}
		return null;
	}

	/** How many plans this runner has created in its lifetime, which is what an attribution check counts. */
	public int plansCreated() {
		return created.size();
	}

	public interface Listener {

		/** A step is about to be handed to the world. */
		default void onStepStarted(TaskStep step) {}

		/** A step settled, successfully or not. */
		default void onStepSettled(TaskStep step, ActionOutcome outcome) {}
	}

	/** How many instructions may wait behind the running one. */
	private static final int MAX_QUEUED_PLANS = 8;

	private final Executor executor;
	private Listener listener;
	private final int maxSteps;
	private final int maxRetriesPerStep;
	private final long durationMillis;

	private TaskPlan plan;
	/**
	 * Every plan created here, newest last. Kept so a caller can bind a plan to the request that produced it:
	 * the running plan is replaced by the next one, and a follow-up plan generated to comment on a failure is
	 * a different plan — reading "the last plan" scores whatever happened most recently, not the ask.
	 */
	private final java.util.Deque<TaskPlan> created = new java.util.ArrayDeque<>();
	private static final int CREATED_MEMORY = 16;
	/** Requests that arrived while he was busy. One plan at a time, but nothing is thrown away. */
	private final java.util.Deque<TaskPlan> queued = new java.util.ArrayDeque<>();
	/** Typed commands: they pre-empt the running job, which is then resumed from where it stopped. */
	private final java.util.Deque<TaskPlan> urgent = new java.util.ArrayDeque<>();
	/** The job that was interrupted by an urgent command, kept so nothing is lost. */
	private TaskPlan suspended;
	private String pendingReport;
	private boolean reported;

	public TaskRunner(Executor executor, int maxSteps, int maxRetriesPerStep, long durationMillis) {
		this.executor = executor;
		this.maxSteps = Math.max(1, Math.min(ActionVocabulary.MAX_ACTIONS, maxSteps));
		this.maxRetriesPerStep = Math.max(0, maxRetriesPerStep);
		this.durationMillis = durationMillis;
	}

	/** Nobody has to listen; when nobody does, the runner behaves exactly as before. */
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/**
	 * Validate and adopt a plan.
	 *
	 * @return null when accepted, otherwise the reason it was rejected (so the caller can tell the
	 *         player instead of silently doing nothing).
	 */
	public String start(String request, List<ActionIntent> intents) {
		return start(request, intents, false);
	}

	/**
	 * @param urgent true for something the player typed just now: it interrupts whatever he is doing
	 *               and that job resumes afterwards, which is what steering means for one body.
	 */
	public String start(String request, List<ActionIntent> intents, boolean urgentRequest) {
		if (intents == null || intents.isEmpty()) return "nothing to do";
		if (queued.size() + urgent.size() >= MAX_QUEUED_PLANS) {
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
		created.addLast(candidate);
		while (created.size() > CREATED_MEMORY) created.removeFirst();
		candidate.setStatus(TaskPlan.Status.VALIDATED);
		for (String problem : rejected) {
			candidate.note(problem);
		}
		// One plan runs at a time; anything else waits its turn instead of being refused, because a
		// busy companion that drops your instruction is indistinguishable from one that ignored it.
		if (urgentRequest) {
			if (plan != null && !plan.isFinished()) suspendCurrent();
			urgent.addLast(candidate);
			if (plan == null || plan.isFinished()) adoptNext();
		} else if (plan != null && !plan.isFinished()) {
			queued.addLast(candidate);
		} else if (!urgent.isEmpty()) {
			urgent.addLast(candidate);
		} else {
			adopt(candidate);
		}
		return null;
	}

	/**
	 * Add work to the job in progress instead of queueing a separate one. This is how "and get coal
	 * while you are down there" becomes part of the same errand.
	 *
	 * @return null when merged, otherwise the reason it could not be (so the caller can say so).
	 */
	public String append(String request, List<ActionIntent> intents) {
		if (intents == null || intents.isEmpty()) return "nothing to do";
		if (plan == null || plan.isFinished()) return start(request, intents);

		List<ActionIntent> accepted = new ArrayList<>();
		for (ActionIntent intent : intents) {
			if (intent == null || !ActionVocabulary.isAllowed(intent.action())) continue;
			if (plan.steps().size() + accepted.size() >= maxSteps) break;
			accepted.add(intent);
		}
		if (accepted.isEmpty()) return "nothing I can add to what I am doing";
		int added = plan.addPending(accepted);
		plan.note("added " + added + " step(s) from \"" + request + "\"");
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
		skipWhatTheWorldAlreadyDid(next);
	}

	/**
	 * An objective the world already satisfies when the plan is adopted is not work.
	 *
	 * <p>This is the stale project: the plan was offered while something else was running, the world
	 * finished the job in the meantime, and the plan starts against a world that has moved on. Here an
	 * absence is unambiguous — the plan was validated against a world where the target was there, so its
	 * absence now is the world having done the work, and the honest ending is COMPLETED with a line that
	 * says so rather than a swing at air.
	 *
	 * <p>The same predicate is asked again for every step the moment it is about to run (see
	 * {@code tickUnsafe}), because between adoption and a step's turn there is still a walk to make and
	 * another player can be doing the same job. The <b>reading</b> is different there, and that is the
	 * point: at adoption "satisfied" means there is nothing to do yet, and at a step's turn it means the
	 * work went out from under him — a thing to report, not to claim. Both readings live in the one
	 * predicate, so the question and the answer cannot drift apart.
	 */
	private void skipWhatTheWorldAlreadyDid(TaskPlan next) {
		for (TaskStep step : next.steps()) {
			if (!step.isPending()) continue;
			StepCheck check = checkStep(step.intent());
			if (!check.isSatisfied()) continue;
			step.markDone(check.changed().isBlank()
					? "already done — nothing left to do" : "already done — " + check.changed());
			next.recordAlreadyDone(step.intent().action(), "when the plan started");
		}
	}

	/** What the executor knows about a step's preconditions, with "it could not answer" kept the same. */
	private StepCheck checkStep(ActionIntent intent) {
		try {
			StepCheck check = executor.checkStep(intent);
			return check == null ? StepCheck.ready() : check;
		} catch (Exception e) {
			// A world that cannot answer is not a world that has changed — and it is not a world that has
			// finished the job either. The step runs and fails with its own reason.
			return StepCheck.unknown("could not check it: " + e.getClass().getSimpleName());
		}
	}

	private void adoptNext() {
		if (!urgent.isEmpty()) {
			adopt(urgent.pollFirst());
			return;
		}
		if (suspended != null) {
			TaskPlan resume = suspended;
			suspended = null;
			adopt(resume);
			return;
		}
		if (!queued.isEmpty()) adopt(queued.pollFirst());
	}

	/** Park the running job: interrupt the step in flight, keep everything not yet done. */
	private void suspendCurrent() {
		if (plan == null || plan.isFinished()) return;
		TaskStep running = plan.running();
		if (running != null) {
			running.requeue();
			executor.interrupt();
		}
		suspended = plan;
		plan = null;
	}

	private void tickUnsafe(long nowMillis) {
		if (plan == null || plan.isFinished()) adoptNext();
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

			// The plan was made against the world as it was, and the world does not hold still: the log he
			// was sent for can be broken, the chest emptied, the square filled — or the whole job done by
			// somebody else — by the time his turn comes. Asked here, at the moment the step stands up.
			//
			// Both non-ready answers are a change since adoption, including a step that is now *satisfied*:
			// the plan promised this work and the world did it instead, so the honest report is that it went
			// rather than "all done". Adoption is where "already done" belongs, and it has already run.
			StepCheck check = checkStep(next.intent());
			if (!check.isReady() && !check.isUnknown()) {
				if (replanOnce(next, check)) continue;   // the substitute goes round this check again
				abortOnChange(next, check);
				queueReport();
				return;
			}
			if (check.isUnknown() && next.attempts() == 0 && !check.changed().isBlank()) {
				plan.note("could not check step " + (next.index() + 1) + ": " + check.changed());
			}

			if (!ActionVocabulary.isAllowed(next.intent().action())) {
				next.markFailed("verb not allowed");
				plan.finishAsFailure("step " + (next.index() + 1) + " used a forbidden verb");
				queueReport();
				return;
			}

			next.beginAttempt();
			notifyStarted(next);
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

	/**
	 * One bounded replan: ask for the same job somewhere else, and take it only if it is the same job.
	 *
	 * <p>The allowance is spent before the executor is asked, so a substitute that is itself broken
	 * cannot start a second one. The substitute is not run from here — the loop sends it back round the
	 * same check, which is what makes "the new place is checked before he goes" true rather than a
	 * promise.
	 *
	 * @return whether the step now has somewhere else to go
	 */
	private boolean replanOnce(TaskStep step, StepCheck check) {
		if (step.wasReplanned()) return false;
		step.markReplanned();
		ActionIntent original = step.intent();
		ActionIntent substitute;
		try {
			substitute = executor.replan(original, check);
		} catch (Exception e) {
			return false; // a world that cannot name a new place has not named one
		}
		if (substitute == null) {
			plan.note("no other place to try: " + check.changed());
			return false;
		}
		if (!sameJob(original, substitute)) {
			// A substitute that changes the instruction is a different job wearing the same step number.
			plan.note("refused a substitute that changed the job: " + substitute + " for " + original);
			return false;
		}
		step.retarget(substitute);
		plan.note("replanned to " + describe(substitute) + " because " + check.changed());
		return true;
	}

	/** A replan may move the coordinates. It may not change the verb, the target or the quantity. */
	private static boolean sameJob(ActionIntent original, ActionIntent substitute) {
		return original != null && substitute != null
				&& same(original.action(), substitute.action())
				&& same(original.target(), substitute.target())
				&& same(original.data(), substitute.data());
	}

	private static boolean same(String a, String b) {
		return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
	}

	/** One intent, readably, for the plan log — the record a reader follows when a plan goes sideways. */
	private static String describe(ActionIntent intent) {
		StringBuilder sb = new StringBuilder(intent.action());
		if (intent.target() != null && !intent.target().isBlank()) sb.append(' ').append(intent.target());
		return sb.append(" at ").append(intent.x()).append(' ').append(intent.y()).append(' ').append(intent.z())
				.toString();
	}

	/**
	 * End the plan because the world moved on, naming what changed.
	 *
	 * <p>Not a retry: retrying an identical step at an identical place is what a companion does when it
	 * has stopped thinking. The rest of the plan is abandoned with it, because every later step was
	 * planned against the world that just changed.
	 */
	private void abortOnChange(TaskStep step, StepCheck check) {
		String action = step.intent().action();
		String why = check.changed();
		step.markFailed(why);
		plan.finishAsFailure("step " + (step.index() + 1) + " (" + action + ") " + why);
		plan.recordAbort("i stopped — " + why);
		plan.note(action + " stopped: " + why);
		notifySettled(step, ActionOutcome.fail(action, why));
	}

	/** A narrator must never be able to stop the work it is describing. */
	private void notifyStarted(TaskStep step) {
		if (listener == null) return;
		try {
			listener.onStepStarted(step);
		} catch (Exception ignored) {
			// narration is not part of execution
		}
	}

	private void notifySettled(TaskStep step, ActionOutcome outcome) {
		if (listener == null) return;
		try {
			listener.onStepSettled(step, outcome);
		} catch (Exception ignored) {
			// narration is not part of execution
		}
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
			notifySettled(step, outcome);
			return;
		}

		// Failure. Non-world actions (talk, look, wait) are not worth retrying.
		if (!worldChange) {
			step.markFailed(outcome.summary());
			plan.note(action + " skipped: " + outcome.summary());
			notifySettled(step, outcome);
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
			notifySettled(step, outcome);
			queueReport();
			return;
		}
		// A step that will not work should not cancel the rest of the job: he carries on with what
		// remains and reports the failure alongside the work that did get done.
		plan.note(action + " gave up after " + step.attempts() + " attempt(s): " + outcome.summary()
				+ " (continuing with the rest)");
		notifySettled(step, outcome);
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

	/** How many jobs are waiting behind the one running (plus parked work). */
	public int queuedCount() {
		return queued.size() + urgent.size() + (suspended == null ? 0 : 1);
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
		urgent.clear();
		suspended = null;
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
