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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every retry in the planner is a number, and this is the file that says so.
 *
 * <p>The rest of the suite proves a job that goes wrong ends with a reason. That is not the same
 * property: a loop that runs a hundred times and then ends with a reason has still retried a hundred
 * times. "He never retries forever" is a claim about <b>counts</b> — how many times the world was asked,
 * not how the asking ended — so each test here counts the asks and compares them to the budget that
 * allowed them.
 *
 * <p>Two of these bounds only exist because the first draft of them did not: a step can be re-planned
 * once, and a re-plan must not hand the step a fresh retry budget. A bound that resets itself when it
 * is used is not a bound, and it looks exactly like one from the outside.
 */
class BoundsTest {

	/** Counts every hand-off to the world, and answers with whatever the test scripted. */
	private static final class Counting implements TaskRunner.Executor {
		final List<ActionIntent> asked = new ArrayList<>();
		/** One answer per call; the last one repeats once the script runs out. */
		final Deque<ActionOutcome> script = new ArrayDeque<>();
		ActionOutcome repeated = ActionOutcome.fail("mine", "the world would not give it up");
		/** Verdicts for {@link #checkStep}, in order; the last repeats. Empty means always READY. */
		final Deque<StepCheck> verdicts = new ArrayDeque<>();
		ActionIntent substitute;
		int replans;
		int interrupts;

		Counting answering(ActionOutcome first) {
			this.repeated = first;
			return this;
		}

		Counting verdict(StepCheck... checks) {
			for (StepCheck c : checks) verdicts.add(c);
			return this;
		}

		Counting replanningTo(ActionIntent elsewhere) {
			this.substitute = elsewhere;
			return this;
		}

		@Override
		public StepCheck checkStep(ActionIntent intent) {
			if (verdicts.isEmpty()) return StepCheck.ready();
			if (verdicts.size() == 1) return verdicts.peekFirst();
			return verdicts.pollFirst();
		}

		@Override
		public ActionIntent replan(ActionIntent broken, StepCheck why) {
			replans++;
			return substitute;
		}

		@Override
		public ActionOutcome begin(ActionIntent intent) {
			asked.add(intent);
			return script.isEmpty() ? repeated : script.poll();
		}

		@Override
		public ActionOutcome poll() {
			return null;
		}

		@Override
		public void interrupt() {
			interrupts++;
		}
	}

	private static ActionIntent intent(String verb) {
		return new ActionIntent(verb, "", 1, 2, 3, "");
	}

	/** Tick until the plan is finished, and refuse to tick for ever: a loop is what this catches. */
	private static void runUntilFinished(TaskRunner runner) {
		for (int tick = 0; tick < 5000; tick++) {
			runner.tick(System.currentTimeMillis());
			TaskPlan plan = runner.plan();
			if (plan != null && plan.isFinished()) return;
		}
		TaskPlan plan = runner.plan();
		throw new AssertionError("The plan never settled inside 5000 ticks — that is the loop: "
				+ (plan == null ? "no plan" : plan.report()));
	}

	@Test
	void aStepThatKeepsFailingIsAttemptedExactlyTheBudgetAndNoMore() {
		Counting exec = new Counting();
		TaskRunner runner = new TaskRunner(exec, 6, 1, 60_000);
		assertNull(runner.start("dig a hole", List.of(intent(ActionVocabulary.MINE))));

		runUntilFinished(runner);

		assertEquals(2, exec.asked.size(),
				"A budget of 1 retry is 2 attempts, not " + exec.asked.size() + ": " + runner.plan().report());
		assertEquals(1, runner.plan().totalRetries(), "the plan's own retry count must agree with the asks");
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
	}

	@Test
	void aBudgetOfZeroMeansOneAttemptNotNone() {
		Counting exec = new Counting();
		TaskRunner runner = new TaskRunner(exec, 6, 0, 60_000);
		assertNull(runner.start("dig a hole", List.of(intent(ActionVocabulary.MINE))));

		runUntilFinished(runner);

		assertEquals(1, exec.asked.size(),
				"A step with no retries still gets tried once, not " + exec.asked.size() + " time(s)");
	}

	@Test
	void everyStepInALongPlanGetsItsOwnBudgetAndNoMore() {
		Counting exec = new Counting();
		TaskRunner runner = new TaskRunner(exec, 6, 2, 60_000);
		assertNull(runner.start("clear three squares", List.of(
				intent(ActionVocabulary.MINE), intent(ActionVocabulary.MINE), intent(ActionVocabulary.MINE))));

		runUntilFinished(runner);

		assertEquals(9, exec.asked.size(),
				"Three steps at a budget of 2 is 9 attempts; the world was asked " + exec.asked.size()
						+ " times (" + runner.plan().report() + ")");
	}

	@Test
	void aReplanDoesNotHandTheStepAFreshRetryBudget() {
		// The failure this catches is the nested one: the runner retries a step twice, the step's place
		// turns out to be taken, the replan gives it somewhere else, and the retry budget is counted from
		// zero again — so the same logical job is attempted once per place, multiplied by the budget.
		//
		// Three verdicts, in the order the runner asks them: one at adoption (which only reads
		// "satisfied"), one at the step's turn (broken, so the replan happens), one for the substitute
		// (ready, so it runs and its own failure spends what is left of the budget).
		Counting exec = new Counting()
				.verdict(StepCheck.ready(), StepCheck.broken("that square is stone now"), StepCheck.ready())
				.replanningTo(new ActionIntent(ActionVocabulary.MINE, "", 9, 9, 9, ""));
		TaskRunner runner = new TaskRunner(exec, 6, 1, 60_000);
		assertNull(runner.start("dig a hole", List.of(intent(ActionVocabulary.MINE))));

		runUntilFinished(runner);

		assertEquals(1, exec.replans, "A step may be re-planned once, and this one was re-planned "
				+ exec.replans + " time(s)");
		assertEquals(2, exec.asked.size(),
				"After one replan the step still has the budget it was given, so 2 asks in total; it was"
						+ " asked " + exec.asked.size() + " times (" + runner.plan().report() + ")");
		assertEquals(2, runner.plan().steps().get(0).attempts(),
				"the substitute is where the budget was spent, so the step's own count is 2: "
						+ runner.plan().report());
	}

	@Test
	void aStepThatIsBrokenBeforeItRunsIsNeverHandedToTheWorld() {
		// The other side of the same coin: a bound is not only an upper limit on retries, it is a lower
		// limit on pointless work. A step whose precondition is already gone is asked about, and refused
		// before a single attempt is spent on it.
		Counting exec = new Counting().verdict(StepCheck.broken("the chest has no coal in it now"));
		TaskRunner runner = new TaskRunner(exec, 6, 3, 60_000);
		assertNull(runner.start("get the coal", List.of(
				new ActionIntent(ActionVocabulary.TAKE, "minecraft:coal", 4, 5, 6, ""))));

		runUntilFinished(runner);

		assertEquals(0, exec.asked.size(),
				"A broken step reached the world " + exec.asked.size() + " time(s): " + runner.plan().report());
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
		assertTrue(runner.plan().abortReason().contains("no coal"),
				"the reason must name the change, not just that something changed: "
						+ runner.plan().abortReason());
	}

	@Test
	void anImpossibleBudgetIsClampedRatherThanHonoured() {
		Counting exec = new Counting();
		TaskRunner runner = new TaskRunner(exec, 6, -3, 60_000);
		assertNull(runner.start("dig a hole", List.of(intent(ActionVocabulary.MINE))));

		runUntilFinished(runner);

		assertEquals(1, exec.asked.size(), "A negative budget must read as none, not as unbounded");
		assertEquals(0, runner.plan().maxRetriesPerStep());
	}

	@Test
	void theDeadlineEndsAPlanTheWorldNeverAnswers() {
		Counting exec = new Counting();
		// An executor that never settles a step is the shape of a hang, not a loop; the plan deadline is
		// the only thing that ends it, so it has to actually end it.
		TaskRunner runner = new TaskRunner(exec, 6, 1, 1000);
		TaskRunner.Executor hung = new TaskRunner.Executor() {
			@Override
			public ActionOutcome begin(ActionIntent intent) {
				return null;
			}

			@Override
			public ActionOutcome poll() {
				return null;
			}

			@Override
			public void interrupt() {
				exec.interrupts++;
			}
		};
		runner = new TaskRunner(hung, 6, 1, 1000);
		assertNull(runner.start("walk somewhere", List.of(intent(ActionVocabulary.WALK_TO))));
		runner.tick(System.currentTimeMillis());
		assertNotNull(runner.plan());

		try {
			Thread.sleep(1100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		runner.tick(System.currentTimeMillis());

		assertEquals(TaskPlan.Status.EXPIRED, runner.plan().status(),
				"A step the world never answered outlived its deadline: " + runner.plan().report());
		assertTrue(exec.interrupts > 0, "the deadline must also stop the step in the world");
	}
}
