package com.rishi.cobbie.task;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionOutcome;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.presence.Narrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step is narrated at both of its edges — as it starts and as it settles — which is the whole
 * difference between "he describes the plan" and "he describes what he is doing".
 *
 * <p>Also asserts the part that keeps the feature from being a hazard: a listener that throws can
 * never stop the work it is describing.
 */
class TaskRunnerNarrationTest {

	private static final class Fake implements TaskRunner.Executor {
		final Deque<ActionOutcome> script = new ArrayDeque<>();
		final List<ActionIntent> begun = new ArrayList<>();
		boolean asynchronous;

		Fake answering(ActionOutcome... outcomes) {
			for (ActionOutcome o : outcomes) script.add(o);
			return this;
		}

		@Override
		public ActionOutcome begin(ActionIntent intent) {
			begun.add(intent);
			if (asynchronous) return null;
			return script.isEmpty() ? ActionOutcome.ok(intent.action(), "fine") : script.poll();
		}

		@Override
		public ActionOutcome poll() {
			return null;
		}

		@Override
		public void interrupt() {
		}
	}

	private static final class Recorder implements TaskRunner.Listener {
		final List<String> started = new ArrayList<>();
		final List<String> settled = new ArrayList<>();

		@Override
		public void onStepStarted(TaskStep step) {
			started.add(Narrator.stepStart(step.intent()));
		}

		@Override
		public void onStepSettled(TaskStep step, ActionOutcome outcome) {
			settled.add(Narrator.stepResult(step.intent(), outcome.success(), outcome.summary()));
		}
	}

	@Test
	void eachStepIsAnnouncedAsItStartsAndClosedAsItSettles() {
		Fake body = new Fake().answering(
				ActionOutcome.ok(ActionVocabulary.MINE, "mined minecraft:stone at 10 64 -3"),
				ActionOutcome.ok(ActionVocabulary.PLACE, "placed minecraft:torch"));
		TaskRunner runner = new TaskRunner(body, 6, 1, 60_000L);
		Recorder lines = new Recorder();
		runner.setListener(lines);

		String refusal = runner.start("dig one out and light it", List.of(
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 10, 64, -3, ""),
				new ActionIntent(ActionVocabulary.PLACE, "minecraft:torch", 11, 64, -3, "")));
		assertEquals(null, refusal);

		runner.tick(1_000L);
		runner.tick(2_000L);

		assertEquals(List.of("going for that stone", "putting down a torch"), lines.started);
		assertEquals(List.of("got it", "done, that's in place"), lines.settled);
		for (String line : lines.started) {
			assertFalse(Narrator.mentionsCoordinates(line), line);
		}
	}

	@Test
	void aRetriedStepIsAnnouncedAgainButAFailureIsOnlySettledOnce() {
		Fake body = new Fake().answering(
				ActionOutcome.fail(ActionVocabulary.MINE, "could not break it"),
				ActionOutcome.fail(ActionVocabulary.MINE, "could not break it"),
				ActionOutcome.fail(ActionVocabulary.MINE, "could not break it"));
		TaskRunner runner = new TaskRunner(body, 6, 1, 60_000L);
		Recorder lines = new Recorder();
		runner.setListener(lines);

		runner.start("dig that out", List.of(
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 10, 64, -3, "")));
		runner.tick(1_000L);
		runner.tick(2_000L);
		runner.tick(3_000L);

		assertEquals(2, lines.started.size(), "the retry starts the step again: " + lines.started);
		assertEquals(1, lines.settled.size(), "but it is only settled when it truly gave up: " + lines.settled);
		assertTrue(lines.settled.get(0).contains("couldn't get through that"));
	}

	@Test
	void aThrowingNarratorCannotStopTheWork() {
		Fake body = new Fake().answering(ActionOutcome.ok(ActionVocabulary.MINE, "mined"));
		TaskRunner runner = new TaskRunner(body, 6, 1, 60_000L);
		runner.setListener(new TaskRunner.Listener() {
			@Override
			public void onStepStarted(TaskStep step) {
				throw new IllegalStateException("the narrator broke");
			}

			@Override
			public void onStepSettled(TaskStep step, ActionOutcome outcome) {
				throw new IllegalStateException("the narrator broke");
			}
		});

		runner.start("dig that out", List.of(
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 10, 64, -3, "")));
		runner.tick(1_000L);

		TaskPlan plan = runner.plan();
		assertNotNull(plan);
		assertEquals(TaskPlan.Status.COMPLETED, plan.status());
		assertEquals(1, body.begun.size());
	}

	@Test
	void theClosingLineOfAJobSaysWhatHappened() {
		Fake body = new Fake().answering(ActionOutcome.ok(ActionVocabulary.MINE, "mined"));
		TaskRunner runner = new TaskRunner(body, 6, 0, 60_000L);
		runner.start("dig those out", List.of(
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 10, 64, -3, ""),
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 11, 64, -3, "")));
		runner.tick(1_000L);

		TaskPlan plan = runner.plan();
		assertEquals(TaskPlan.Status.COMPLETED, plan.status());
		String line = Narrator.jobLine(plan);
		assertNotNull(line);
		assertFalse(Narrator.mentionsCoordinates(line), line);

		// A cancelled job gets a line of its own rather than the same words as a finished one.
		Fake busy = new Fake();
		busy.asynchronous = true;
		TaskRunner cancelRunner = new TaskRunner(busy, 6, 1, 60_000L);
		cancelRunner.start("dig those out", List.of(
				new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", 10, 64, -3, "")));
		cancelRunner.tick(1_000L);
		assertTrue(cancelRunner.cancel("owner said stop"), "the job was still running");
		assertEquals("stopped, like you asked", Narrator.jobLine(cancelRunner.plan()));
	}
}
