package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionEvidence;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one contract: a settled step carries a claim <b>and</b> the observation it rests on.
 *
 * <p>Before 0.80.0 "the world agrees" was a property each handler kept by hand, and four false successes
 * shipped in one week because three handlers did not. This class is the proof that the guarantee is now
 * the executor's: a verb whose claim has to be read back off the world cannot be marked DONE by an
 * outcome that only says it did something.
 *
 * <p>The below-bar tests are written against the <b>four defects of 0.79.0</b> by name — the walked sign
 * write, the farm count, the station output that exists nowhere, and the item taken from the wrong slot —
 * because those are the shapes that must not be able to come back. A handler that reverts to trusting a
 * return value produces exactly what these tests feed the runner.
 */
class ActionContractTest {

	/** A world that answers with the scripted outcomes and never moves on by itself. */
	private static final class Scripted implements TaskRunner.Executor {
		final List<ActionIntent> asked = new ArrayList<>();
		final Deque<ActionOutcome> script = new ArrayDeque<>();
		final Deque<StepCheck> checks = new ArrayDeque<>();
		int interrupts;

		Scripted answering(ActionOutcome outcome) {
			this.script.add(outcome);
			return this;
		}

		Scripted alreadyThere(StepCheck check) {
			this.checks.add(check);
			return this;
		}

		@Override
		public StepCheck checkStep(ActionIntent intent) {
			if (checks.isEmpty()) return StepCheck.ready();
			if (checks.size() == 1) return checks.peekFirst();
			return checks.pollFirst();
		}

		@Override
		public ActionOutcome begin(ActionIntent intent) {
			asked.add(intent);
			return script.isEmpty() ? ActionOutcome.fail(intent.action(), "nothing scripted") : script.poll();
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
		return new ActionIntent(verb, "minecraft:stone", 1, 2, 3, "");
	}

	private static void runUntilFinished(TaskRunner runner) {
		for (int tick = 0; tick < 5000; tick++) {
			runner.tick(System.currentTimeMillis());
			TaskPlan plan = runner.plan();
			if (plan != null && plan.isFinished()) return;
		}
		TaskPlan plan = runner.plan();
		throw new AssertionError("The plan never settled inside 5000 ticks: "
				+ (plan == null ? "no plan" : plan.report()));
	}

	// ==================================================================
	// The bar itself: which verbs owe the world, and which owe nothing
	// ==================================================================

	@Test
	void everyVerbIsClassifiedWithoutAGap() {
		Set<String> owesNothing = Set.of(ActionVocabulary.ANSWER, ActionVocabulary.CHAT,
				ActionVocabulary.FOLLOW, ActionVocabulary.STAY, ActionVocabulary.WAVE,
				ActionVocabulary.EXPLORE, ActionVocabulary.LOOKUP, ActionVocabulary.WAIT);

		assertEquals(36, ActionVocabulary.allowed().size(), "the verb list is the thing this table is over");

		int worldVerbs = 0;
		for (String verb : ActionVocabulary.allowed()) {
			boolean owes = ActionVocabulary.provesWorld(verb);
			if (owes) worldVerbs++;
			assertEquals(!owesNothing.contains(verb), owes,
					verb + " is classified on the wrong side of the world line");
		}
		assertEquals(28, worldVerbs, "28 verbs change or read the world; the other 8 claim nothing about it");
		assertEquals(8, owesNothing.size());
	}

	@Test
	void anUnknownVerbHasToShowTheWorld() {
		assertTrue(ActionVocabulary.provesWorld("teleport"), "a verb nobody classified must not get the weak answer");
		assertTrue(ActionVocabulary.provesWorld(null), "a null verb must not get the weak answer either");
	}

	@Test
	void aSuccessCannotBeWrittenWithoutAnObservation() {
		assertThrows(IllegalArgumentException.class, () -> ActionOutcome.ok("mine", "mined it", null),
				"a success built out of nothing observed is the defect this type exists to make unwritable");
		assertThrows(IllegalArgumentException.class,
				() -> new ActionOutcome(true, "mine", "mined it", null));
		assertNotNull(ActionOutcome.fail("mine", "the world would not give it up"),
				"a failure needs no evidence, and demanding one would tempt a handler to invent it");
	}

	@Test
	void anObservationMustSayWhatWasSeen() {
		assertThrows(IllegalArgumentException.class, () -> ActionEvidence.world("  "));
		assertThrows(IllegalArgumentException.class, () -> ActionEvidence.ownAct(""));
	}

	// ==================================================================
	// The four defects of 0.79.0, re-fed as the shapes they were
	// ==================================================================

	/**
	 * Each of these is a verb whose honest ending was fixed in 0.79.0 by adding a read, and each was
	 * reported as done before that fix by a claim with nothing behind it. Fed to the runner in that old
	 * shape, none of them may reach DONE.
	 */
	@Test
	void aWorldVerbClaimingOnlyItsOwnActIsNotDone() {
		List<ActionOutcome> shapes = List.of(
				// the walked write that answered "arrived" over a blank sign
				ActionOutcome.ok(ActionVocabulary.WRITE, "arrived", ActionEvidence.ownAct("i got there")),
				// the farm that counted a crop over a discarded destroyBlock answer
				ActionOutcome.ok(ActionVocabulary.FARM, "harvested 2 ripe wheat",
						ActionEvidence.ownAct("i swung at the crops")),
				// the station that said "mended" with the mended thing existing nowhere
				ActionOutcome.ok(ActionVocabulary.REPAIR, "mended two swords into one",
						ActionEvidence.ownAct("the grid answered yes")),
				// the equip that took the item the description named from whichever slot matched first
				ActionOutcome.ok(ActionVocabulary.EQUIP, "equipped diamond_sword",
						ActionEvidence.ownAct("i put something in the slot")));

		for (ActionOutcome claim : shapes) {
			Scripted world = new Scripted().answering(claim);
			TaskRunner runner = new TaskRunner(world, 4, 1, 60_000);
			assertNull(runner.start("do the thing", List.of(intent(claim.action()))));
			runUntilFinished(runner);

			TaskPlan plan = runner.plan();
			assertEquals(TaskPlan.Status.FAILED, plan.status(),
					claim.action() + " was completed on a claim with no world behind it: " + plan.report());
			assertFalse(plan.log().contains(claim.summary()),
					claim.action() + "'s unearned sentence reached the log as a completion: " + plan.log());
			assertTrue(plan.log().contains("no world to show for it"),
					"the plan must say why it refused the claim, and it says: " + plan.log());
			assertEquals(2, world.asked.size(),
					"the refused claim takes the same retry budget as any other failure, and was asked "
							+ world.asked.size() + " time(s)");
		}
	}

	@Test
	void aClaimBelowTheBarCannotMarkAStepDone() {
		Scripted world = new Scripted().answering(
				ActionOutcome.ok(ActionVocabulary.WRITE, "arrived", ActionEvidence.ownAct("i got there")));
		TaskRunner runner = new TaskRunner(world, 4, 0, 60_000);
		assertNull(runner.start("write on the sign", List.of(intent(ActionVocabulary.WRITE))));
		runUntilFinished(runner);

		TaskStep step = runner.plan().steps().get(0);
		assertEquals(TaskStep.Status.FAILED, step.status());
		assertNull(step.evidence(), "a failed step has nothing it saw, and must not pretend otherwise");
		assertTrue(step.result().toLowerCase(java.util.Locale.ROOT).contains("read back off the world"),
				"the failure has to name what was missing: " + step.result());
	}

	// ==================================================================
	// The other side: what a real completion now carries
	// ==================================================================

	@Test
	void aWorldVerbWithTheWorldBehindItIsDone() {
		Scripted world = new Scripted().answering(ActionOutcome.ok(ActionVocabulary.MINE, "mined minecraft:stone",
				ActionEvidence.world("1 2 3 is air now")));
		TaskRunner runner = new TaskRunner(world, 4, 0, 60_000);
		assertNull(runner.start("dig", List.of(intent(ActionVocabulary.MINE))));
		runUntilFinished(runner);

		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status());
		ActionEvidence seen = runner.plan().steps().get(0).evidence();
		assertNotNull(seen, "a done step keeps what it saw, so a caller can ask it");
		assertEquals("1 2 3 is air now", seen.observed());
	}

	@Test
	void aVerbThatClaimsNothingNeedsOnlyItsOwnAct() {
		Scripted world = new Scripted().answering(
				ActionOutcome.ok(ActionVocabulary.WAVE, "waved hello", ActionEvidence.ownAct("i waved")));
		TaskRunner runner = new TaskRunner(world, 4, 0, 60_000);
		assertNull(runner.start("wave", List.of(intent(ActionVocabulary.WAVE))));
		runUntilFinished(runner);

		assertEquals(TaskPlan.Status.COMPLETED, runner.plan().status(),
				"a wave has no world fact to disagree with: " + runner.plan().report());
	}

	@Test
	void theObjectiveTheWorldAlreadyHeldComesWithWhatWasSeen() {
		Scripted world = new Scripted().alreadyThere(StepCheck.satisfied("that square is already air"));
		TaskRunner runner = new TaskRunner(world, 4, 0, 60_000);
		assertNull(runner.start("dig", List.of(intent(ActionVocabulary.MINE))));
		runUntilFinished(runner);

		TaskStep step = runner.plan().steps().get(0);
		assertEquals(TaskStep.Status.DONE, step.status(), "the world having done it is a completion");
		assertNotNull(step.evidence(), "a step the world finished still has to say what was seen");
		assertTrue(step.evidence().aboutTheWorld());
		assertEquals("that square is already air", step.evidence().observed());
		assertEquals(0, world.asked.size(), "nothing was handed to the world for work it had already done");
	}
}
