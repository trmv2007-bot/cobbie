package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner is pure Java with a fakeable {@link TaskRunner.Executor}, so every guarantee the
 * mission asks for — bounded steps, verified success, retry budget, cancellation, deadline —
 * is testable without a running game.
 */
class TaskRunnerTest {

	/** Records what it was asked to do and answers with scripted outcomes. */
	private static final class Fake implements TaskRunner.Executor {
		final List<ActionIntent> begun = new ArrayList<>();
		final Deque<ActionOutcome> script = new ArrayDeque<>();
		/** Objectives this fake world already holds, by verb — the stale-project case. */
		final java.util.Set<String> alreadyThere = new java.util.HashSet<>();
		boolean async;
		int interrupts;

		@Override
		public StepCheck checkStep(ActionIntent intent) {
			return alreadyThere.contains(intent.action())
					? StepCheck.satisfied("nothing left to do there") : StepCheck.ready();
		}

		Fake answering(ActionOutcome... outcomes) {
			for (ActionOutcome o : outcomes) script.add(o);
			return this;
		}

		Fake asynchronous() {
			async = true;
			return this;
		}

		@Override
		public ActionOutcome begin(ActionIntent intent) {
			begun.add(intent);
			if (async) return null;
			return script.isEmpty() ? ActionOutcome.ok(intent.action(), "fine") : script.poll();
		}

		@Override
		public ActionOutcome poll() {
			return script.isEmpty() ? null : script.poll();
		}

		@Override
		public void interrupt() {
			interrupts++;
		}
	}

	private static ActionIntent intent(String verb) {
		return new ActionIntent(verb, "", 1, 2, 3, "");
	}

	@Test
	void completesEveryStepInOrderAndReportsOnce() {
		Fake exec = new Fake();
		TaskRunner runner = new TaskRunner(exec, 6, 1, 60_000);
		assertNull(runner.start("dig a path",
				List.of(intent(ActionVocabulary.WALK_TO), intent(ActionVocabulary.MINE))));

		runner.tick(System.currentTimeMillis());
		runner.tick(System.currentTimeMillis());

		assertEquals(2, exec.begun.size());
		assertEquals(ActionVocabulary.WALK_TO, exec.begun.get(0).action());
		assertEquals(ActionVocabulary.MINE, exec.begun.get(1).action());
		assertFalse(runner.hasActiveTask());

		String report = runner.takeReport();
		assertNotNull(report);
		assertTrue(report.startsWith("completed"), report);
		assertNull(runner.takeReport(), "the completion report must only be delivered once");
	}

	@Test
	void anObjectiveTheWorldAlreadyHoldsIsNotWalkedInto() {
		Fake exec = new Fake();
		exec.alreadyThere.add(ActionVocabulary.MINE);
		TaskRunner runner = new TaskRunner(exec, 6, 1, 60_000);

		assertNull(runner.start("clear the log and mark the spot",
				List.of(intent(ActionVocabulary.MINE), intent(ActionVocabulary.WALK_TO))));
		runner.tick(System.currentTimeMillis());

		assertEquals(1, exec.begun.size(), "the objective the world already held was still attempted");
		assertEquals(ActionVocabulary.WALK_TO, exec.begun.get(0).action());
		TaskPlan plan = runner.plan();
		assertEquals(TaskPlan.Status.COMPLETED, plan.status());
		assertEquals(1, plan.alreadyDoneCount());
		assertTrue(plan.report().contains("already done"), plan.report());
		assertFalse(runner.hasActiveTask());
	}

	/**
	 * The world moving on after adoption is not the same event as the world having done the job before it.
	 *
	 * <p>Adoption is where "already done" belongs: there the plan was made against a world it never got to
	 * see change, and a finished objective is a truthful completion with nothing to do. A target that
	 * disappears after adoption is the other case — he was sent to do something and somebody else did it —
	 * and it must end as a stop with a reason, not as a completion claim about work he did not do, and not
	 * as a swing at air. 0.78 replaced the once-at-adoption rule this test used to pin; the reason it
	 * existed survives, in a different shape.
	 */
	@Test
	void whatGoesStaleAfterThePlanStartsStopsWithAReasonRatherThanCompleting() {
		Fake exec = new Fake();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		runner.start("mine that far log", List.of(intent(ActionVocabulary.MINE)));

		exec.alreadyThere.add(ActionVocabulary.MINE);   // the world clears it a moment too late
		exec.answering(ActionOutcome.fail("mine", "there is nothing at 1 2 3"));
		runner.tick(System.currentTimeMillis());

		assertEquals(0, exec.begun.size(), "a step that went stale after adoption was run against nothing");
		assertEquals(0, runner.plan().alreadyDoneCount(),
				"a step somebody else finished after adoption was counted as work nobody did");
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
		assertTrue(runner.plan().abortReason().contains("nothing left to do there"), runner.plan().abortReason());
	}

	@Test
	void anAsyncStepIsNotJudgedUntilTheWorldAnswers() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 4, 0, 60_000);
		runner.start("walk over there", List.of(intent(ActionVocabulary.WALK_TO)));

		runner.tick(System.currentTimeMillis());
		assertEquals(1, exec.begun.size());
		assertTrue(runner.hasActiveTask(), "still walking");

		exec.script.add(ActionOutcome.ok("walk-to", "arrived at 1 2 3"));
		runner.tick(System.currentTimeMillis());
		assertFalse(runner.hasActiveTask());
		assertTrue(runner.takeReport().contains("arrived"));
	}

	@Test
	void successIsNeverAssumedFromAFailedOutcome() {
		Fake exec = new Fake().answering(
				ActionOutcome.fail("mine", "needs a pickaxe"),
				ActionOutcome.fail("mine", "needs a pickaxe"));   // attempt 1 + 1 retry, then give up
		TaskRunner runner = new TaskRunner(exec, 4, 1, 60_000);
		runner.start("mine iron", List.of(intent(ActionVocabulary.MINE)));

		for (int i = 0; i < 10 && runner.hasActiveTask(); i++) {
			runner.tick(System.currentTimeMillis());
		}

		assertEquals(2, exec.begun.size(), "retry budget is 1, so exactly two attempts");
		assertFalse(runner.hasActiveTask());
		String report = runner.takeReport();
		assertTrue(report.startsWith("failed"), report);
		assertTrue(report.contains("needs a pickaxe"), report);
	}

	@Test
	void chatFailuresAreNotRetried() {
		Fake exec = new Fake().answering(ActionOutcome.fail("chat", "nothing to say"));
		TaskRunner runner = new TaskRunner(exec, 4, 3, 60_000);
		runner.start("say hi", List.of(new ActionIntent(ActionVocabulary.CHAT, "", 0, 0, 0, "")));

		for (int i = 0; i < 6 && runner.hasActiveTask(); i++) runner.tick(System.currentTimeMillis());

		assertEquals(1, exec.begun.size(), "a non-world action must not be hammered");
		assertFalse(runner.hasActiveTask());
	}

	@Test
	void aFailedWorldStepDoesNotCancelTheRestOfThePlan() {
		Fake exec = new Fake();
		exec.script.add(ActionOutcome.fail("mine", "protected block"));
		exec.async = false;
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		runner.start("build a wall", List.of(
				intent(ActionVocabulary.MINE),
				intent(ActionVocabulary.PLACE),
				intent(ActionVocabulary.PLACE)));

		for (int i = 0; i < 10 && runner.hasActiveTask(); i++) runner.tick(System.currentTimeMillis());

		assertEquals(3, exec.begun.size(), "one bad block must not throw away the rest of the job: " + exec.begun);
	}

	@Test
	void aPermissionRefusalStopsThePlanBecauseThePlanIsWrong() {
		Fake exec = new Fake();
		exec.script.add(ActionOutcome.fail("mine", "my owner has not allowed that"));
		exec.async = false;
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		runner.start("dig", List.of(
				intent(ActionVocabulary.MINE),
				intent(ActionVocabulary.PLACE)));

		for (int i = 0; i < 10 && runner.hasActiveTask(); i++) runner.tick(System.currentTimeMillis());

		assertEquals(1, exec.begun.size(), "a refusal is not a transient failure: " + exec.begun);
	}

	@Test
	void cancellationStopsExecutionAndInterruptsTheWorld() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 1, 60_000);
		runner.start("long job", List.of(intent(ActionVocabulary.WALK_TO), intent(ActionVocabulary.MINE)));
		runner.tick(System.currentTimeMillis());
		assertTrue(runner.hasActiveTask());

		assertTrue(runner.cancel("owner said stop"));
		assertEquals(1, exec.interrupts);
		assertFalse(runner.hasActiveTask());
		assertTrue(runner.takeReport().contains("cancelled"));
		assertNull(runner.start("new job", List.of(intent(ActionVocabulary.WAVE))),
				"a cancelled plan must release the runner");
	}

	@Test
	void deadlineExpiresAnOverrunningStep() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 1_000);
		runner.start("slow job", List.of(intent(ActionVocabulary.MINE)));
		long t0 = System.currentTimeMillis();
		runner.tick(t0);

		runner.tick(t0 + 5_000);

		assertFalse(runner.hasActiveTask());
		assertTrue(exec.interrupts > 0, "the in-flight action must be interrupted on expiry");
		assertTrue(runner.takeReport().contains("expired"));
	}

	@Test
	void plansOverTheStepLimitAreTrimmedNotRefused() {
		Fake exec = new Fake();
		TaskRunner runner = new TaskRunner(exec, 3, 0, 60_000);
		List<ActionIntent> five = List.of(
				intent(ActionVocabulary.WAVE), intent(ActionVocabulary.WAVE), intent(ActionVocabulary.WAVE),
				intent(ActionVocabulary.WAVE), intent(ActionVocabulary.WAVE));
		assertNull(runner.start("wave a lot", five));

		for (int i = 0; i < 6 && runner.hasActiveTask(); i++) runner.tick(System.currentTimeMillis());
		assertEquals(3, exec.begun.size(), "hard ceiling of 3 steps");
		assertTrue(runner.plan().log().contains("over the limit"), runner.plan().log());
	}

	@Test
	void inventedVerbsNeverReachTheExecutor() {
		Fake exec = new Fake();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		String refusal = runner.start("do impossible things",
				List.of(new ActionIntent("summon_wither", "", 0, 0, 0, "")));

		assertNotNull(refusal, "an all-invalid plan must be refused, not run silently");
		assertTrue(exec.begun.isEmpty());
	}

	@Test
	void newWorkJoinsTheRunningJobInsteadOfQueueingBehindIt() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 24, 0, 60_000);
		runner.start("mine the seam", List.of(intent(ActionVocabulary.MINE)), false);
		runner.tick(System.currentTimeMillis());

		// "and get coal while you are there" — same errand, not a second one.
		assertNull(runner.append("also coal", List.of(intent(ActionVocabulary.MINE),
				intent(ActionVocabulary.MINE))));

		exec.script.add(ActionOutcome.ok("mine", "1"));
		exec.script.add(ActionOutcome.ok("mine", "2"));
		exec.script.add(ActionOutcome.ok("mine", "3"));
		for (int i = 0; i < 12; i++) runner.tick(System.currentTimeMillis());

		long mines = exec.begun.stream().filter(i -> ActionVocabulary.MINE.equals(i.action())).count();
		assertEquals(3, mines, "the added steps belong to the same job: " + exec.begun);
	}

	@Test
	void anUrgentOrderInterruptsAndTheOldJobResumes() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		runner.start("walk somewhere", List.of(intent(ActionVocabulary.WALK_TO)), false);
		runner.tick(System.currentTimeMillis());

		// Typed command mid-walk: it must interrupt, and the walk must come back afterwards.
		runner.start("wave", List.of(intent(ActionVocabulary.WAVE)), true);
		exec.script.add(ActionOutcome.ok("wave", "waved"));
		for (int i = 0; i < 4; i++) runner.tick(System.currentTimeMillis());
		assertTrue(exec.begun.stream().anyMatch(i -> ActionVocabulary.WAVE.equals(i.action())),
				"the urgent order must run first: " + exec.begun);

		// Finish the wave, then the parked walk is picked up again and its step re-begun.
		exec.script.add(ActionOutcome.ok("walk-to", "arrived"));
		for (int i = 0; i < 8; i++) runner.tick(System.currentTimeMillis());
		long walks = exec.begun.stream().filter(i -> ActionVocabulary.WALK_TO.equals(i.action())).count();
		assertTrue(walks >= 2, "the interrupted job must resume: " + exec.begun);
	}

	@Test
	void stopClearsQueuedAndParkedWork() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		runner.start("first", List.of(intent(ActionVocabulary.WALK_TO)), false);
		runner.tick(System.currentTimeMillis());
		runner.start("urgent", List.of(intent(ActionVocabulary.WAVE)), true);
		runner.start("second", List.of(intent(ActionVocabulary.MINE)), false);

		runner.cancel("owner said stop");
		exec.script.add(ActionOutcome.ok("mine", "fine"));
		for (int i = 0; i < 8; i++) runner.tick(System.currentTimeMillis());

		assertTrue(exec.begun.stream().noneMatch(i -> ActionVocabulary.MINE.equals(i.action())),
				"nothing queued may run after stop: " + exec.begun);
	}

	@Test
	void aSecondPlanWaitsItsTurnInsteadOfBeingRefused() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		assertNull(runner.start("first", List.of(intent(ActionVocabulary.WALK_TO))));
		runner.tick(System.currentTimeMillis());

		// One plan runs at a time, but an instruction given while he is busy is queued, not dropped:
		// a companion that silently ignores you is the failure this test exists to prevent.
		assertNull(runner.start("second", List.of(intent(ActionVocabulary.WAVE))), "second must be queued");

		exec.script.add(ActionOutcome.ok("walk-to", "arrived"));
		for (int i = 0; i < 8; i++) runner.tick(System.currentTimeMillis());

		assertTrue(exec.begun.stream().anyMatch(i -> ActionVocabulary.WAVE.equals(i.action())),
				"the queued plan must run once the first finishes: " + exec.begun);
	}

	@Test
	void taskDescriptionReflectsProgressForPerception() {
		Fake exec = new Fake().asynchronous();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		assertEquals("none", runner.currentTaskDescription());
		runner.start("gather wood", List.of(intent(ActionVocabulary.MINE), intent(ActionVocabulary.MINE)));
		runner.tick(System.currentTimeMillis());
		assertTrue(runner.currentTaskDescription().contains("gather wood"));
		assertTrue(runner.currentTaskDescription().contains("/2"));
	}

	@Test
	void vocabularyWhitelistIsTheGateForSkillsAndPlansAlike() {
		assertTrue(ActionVocabulary.isAllowed(ActionVocabulary.MINE));
		assertFalse(ActionVocabulary.isAllowed("setblock"));
		assertFalse(ActionVocabulary.isAllowed(null));
		assertTrue(ActionVocabulary.needsPosition(ActionVocabulary.PLACE));
		assertFalse(ActionVocabulary.needsPosition(ActionVocabulary.WAVE));
		assertTrue(ActionVocabulary.isWorldChanging(ActionVocabulary.MINE));
		assertFalse(ActionVocabulary.isWorldChanging(ActionVocabulary.WAVE));
		assertEquals("mine", ActionVocabulary.normalise("  DIG "));
		assertEquals("walk-to", ActionVocabulary.normalise("goto"));
		assertEquals("collect", ActionVocabulary.normalise("pick_up"));
		assertNull(ActionVocabulary.normalise("rm -rf"));
	}
}
