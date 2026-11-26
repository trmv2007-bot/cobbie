package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionEvidence;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world changes while a plan is running, and the planner has to notice at the moment each step runs.
 *
 * <p>Everything here is about one question the runner asks before it hands a step over: is this still the
 * step my plan was made for? The four answers and what each one is allowed to cause are pinned
 * separately, because the failure modes are different and so are their cures:
 * <ul>
 *   <li>a step the world <b>finished</b> while he was walking — not a completion claim, because he did not
 *       do it, and not a retry, because there is nothing left to retry;</li>
 *   <li>a step whose <b>place is gone</b> — one bounded replan, onto the same job somewhere else;</li>
 *   <li>a step whose place is gone and <b>has nowhere else to be</b> — stop, and say what changed;</li>
 *   <li>a step he <b>cannot see</b> — run it, and let it fail with its own reason.</li>
 * </ul>
 *
 * <p>The planner is pure Java with a fakeable world, so each of those is decided here rather than inferred
 * from an in-game run that can only show the case it happened to build.
 */
class StaleStepTest {

	private static final class Fake implements TaskRunner.Executor {
		final List<ActionIntent> begun = new ArrayList<>();
		/** Places the fake world reads as already the thing asked for, by coordinates. */
		final Set<String> satisfied = new HashSet<>();
		/** Places the fake world reads as changed in a way that blocks the step. */
		final Set<String> broken = new HashSet<>();
		/** Places the fake world cannot answer about at all. */
		final Set<String> unknown = new HashSet<>();
		ActionIntent substitute;
		int replanCalls;

		@Override
		public StepCheck checkStep(ActionIntent intent) {
			String at = at(intent);
			if (unknown.contains(at)) return StepCheck.unknown("that area is not loaded");
			if (satisfied.contains(at)) return StepCheck.satisfied("there is nothing left at " + at + " to break");
			if (broken.contains(at)) return StepCheck.broken(at + " is stone now");
			return StepCheck.ready();
		}

		@Override
		public ActionIntent replan(ActionIntent brokenStep, StepCheck why) {
			replanCalls++;
			return substitute;
		}

		@Override
		public ActionOutcome begin(ActionIntent intent) {
			begun.add(intent);
			return ActionOutcome.ok(intent.action(), "fine", ActionEvidence.world("the world agreed"));
		}

		@Override
		public ActionOutcome poll() {
			return null;
		}

		@Override
		public void interrupt() {
		}
	}

	private static String at(ActionIntent intent) {
		return intent.x() + "," + intent.y() + "," + intent.z();
	}

	private static ActionIntent mine(int x, int y, int z) {
		return new ActionIntent(ActionVocabulary.MINE, "", x, y, z, "");
	}

	private static ActionIntent place(String block, int x, int y, int z) {
		return new ActionIntent(ActionVocabulary.PLACE, block, x, y, z, "");
	}

	@Test
	void aStepTheWorldFinishedWhileHeWalkedIsNotClaimedAndNotRetried() {
		Fake world = new Fake();
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("break that log", List.of(mine(7, 64, 9)));
		world.substitute = mine(8, 64, 9);

		// Somebody else broke it between the plan being adopted and its turn coming.
		world.satisfied.add("7,64,9");
		runner.tick(System.currentTimeMillis());

		assertEquals(1, world.begun.size());
		assertEquals(8, world.begun.get(0).x(), "the replan was not what ran");
		assertEquals(1, world.replanCalls, "a step was replanned more than once, or not at all");
		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status());
		assertTrue(runner.plan().log().contains("replanned"), runner.plan().log());
	}

	@Test
	void aStepSatisfiedAtAdoptionIsWorkNobodyDid() {
		Fake world = new Fake();
		world.satisfied.add("7,64,9");
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("break that log", List.of(mine(7, 64, 9)));
		runner.tick(System.currentTimeMillis());

		assertTrue(world.begun.isEmpty(), "a step the world had already done was attempted");
		assertEquals(1, runner.plan().alreadyDoneCount());
		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status());
	}

	@Test
	void aStepWithNowhereElseToBeStopsThePlanWithTheReasonSaidOut() {
		Fake world = new Fake();
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("plant a post", List.of(place("minecraft:oak_log", 3, 4, 5)));
		world.broken.add("3,4,5");   // somebody built there while he was on his way

		runner.tick(System.currentTimeMillis());

		assertTrue(world.begun.isEmpty(), "a step whose place was taken was attempted anyway");
		assertEquals(1, world.replanCalls, "the one replan attempt was not made");
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
		assertEquals("i stopped — 3,4,5 is stone now", runner.plan().abortReason());
		assertTrue(runner.plan().report().contains("3,4,5 is stone now"), runner.plan().report());
	}

	@Test
	void aSubstituteThatIsAlsoBrokenAbortsRatherThanReplanningAgain() {
		Fake world = new Fake();
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("plant a post", List.of(place("minecraft:oak_log", 3, 4, 5)));
		world.broken.add("3,4,5");
		world.broken.add("4,4,5");
		world.substitute = place("minecraft:oak_log", 4, 4, 5);

		runner.tick(System.currentTimeMillis());

		assertTrue(world.begun.isEmpty(), "a substitute that was itself taken was attempted anyway");
		assertEquals(1, world.replanCalls, "a broken substitute started a second replan");
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
		assertTrue(runner.plan().abortReason().contains("4,4,5"), runner.plan().abortReason());
	}

	@Test
	void aSubstituteThatChangesTheJobIsRefused() {
		Fake world = new Fake();
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("break that log", List.of(mine(7, 64, 9)));
		world.satisfied.add("7,64,9");
		// A substitute that quietly changes which item the job is about is a different job wearing the same
		// step number, and taking it would be acting on an instruction nobody gave.
		world.substitute = new ActionIntent(ActionVocabulary.MINE, "minecraft:diamond_ore", 8, 64, 9, "");

		runner.tick(System.currentTimeMillis());

		assertTrue(world.begun.isEmpty(), "a substitute that changed the job was run");
		assertEquals(TaskPlan.Status.FAILED, runner.plan().status());
		assertTrue(runner.plan().log().contains("changed the job"), runner.plan().log());
	}

	@Test
	void aStepHeCannotSeeRunsRatherThanBeingCalledDoneOrBroken() {
		Fake world = new Fake();
		world.unknown.add("7,64,9");
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("break that log", List.of(mine(7, 64, 9)));
		runner.tick(System.currentTimeMillis());

		assertEquals(1, world.begun.size(), "a step nobody could look at was skipped");
		assertEquals(0, runner.plan().alreadyDoneCount(), "an unobservable step was counted as done");
		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status());
		assertTrue(runner.plan().log().contains("could not check"), runner.plan().log());
	}

	@Test
	void aStepThatSettlesNormallyIsNeverReplanned() {
		Fake world = new Fake();
		TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
		runner.start("break that log", List.of(mine(7, 64, 9)));
		runner.tick(System.currentTimeMillis());

		assertEquals(1, world.begun.size());
		assertEquals(0, world.replanCalls, "a step nothing had changed under was replanned");
		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status());
		assertTrue(runner.plan().abortReason().isBlank());
	}
}
