package com.rishi.cobbie.presence;

import com.rishi.cobbie.Cobbie;

/**
 * Where a player's instruction spends its time, measured rather than guessed.
 *
 * <p>One record per instruction, closed when Cobbie says what came of it. The marks are taken at
 * the five places the chain can wait: the chat batch window, the model round trip, the handover
 * from reply to the first action, the action itself, and the gap between the job settling and the
 * line he says about it. Everything is plain arithmetic on millisecond stamps, so the table in the
 * release note can be reproduced from the log.
 *
 * <p>Server thread only, except {@link #chat} which is called from the chat event (also server
 * thread). No allocation on the hot path, no state read by any decision.
 */
public final class ActionTiming {

	/** One measured instruction. Zero means "this instruction never reached that stage". */
	public record Timings(long batchMillis, long modelMillis, long startMillis, long workMillis,
			long resultMillis, int steps, int modelCalls) {

		public long totalMillis() {
			return batchMillis + modelMillis + startMillis + workMillis + resultMillis;
		}

		@Override
		public String toString() {
			return "chat->dispatch=" + batchMillis + "ms dispatch->reply=" + modelMillis
					+ "ms reply->first step=" + startMillis + "ms first step->settled=" + workMillis
					+ "ms settled->result=" + resultMillis + "ms steps=" + steps
					+ " model calls=" + modelCalls;
		}
	}

	private static boolean open;
	private static long chatAt;
	private static long dispatchAt;
	private static long replyAt;
	private static long firstStepAt;
	private static long settledAt;
	private static int modelCalls;
	private static int steps;
	private static volatile Timings last = new Timings(0, 0, 0, 0, 0, 0, 0);

	private ActionTiming() {}

	/** A line reached Cobbie. Starts a fresh record. */
	public static synchronized void chat(long now) {
		open = true;
		chatAt = now;
		dispatchAt = 0;
		replyAt = 0;
		firstStepAt = 0;
		settledAt = 0;
		modelCalls = 0;
		steps = 0;
	}

	/** A model request went out. Counted, because the count is the thing that was wrong. */
	public static synchronized void dispatched(long now) {
		dispatchAt = now;
		modelCalls++;
	}

	/** A second request for the same instruction (the result round trip). */
	public static synchronized void extraModelCall() {
		modelCalls++;
	}

	/** The model answered (successfully or not). */
	public static synchronized void replied(long now) {
		replyAt = now;
	}

	/** The first world action of this instruction actually began. */
	public static synchronized void firstStep(long now) {
		if (firstStepAt == 0) firstStepAt = now;
	}

	/** The plan settled. */
	public static synchronized void settled(long now, int stepCount) {
		settledAt = now;
		steps = stepCount;
	}

	/** He said what came of it. Closes the record and logs the line the release note quotes. */
	public static synchronized void resultSpoken(long now) {
		if (!open) return;
		open = false;
		long batch = dispatchAt == 0 ? 0 : Math.max(0, dispatchAt - chatAt);
		long model = replyAt == 0 || dispatchAt == 0 ? 0 : Math.max(0, replyAt - dispatchAt);
		long start = firstStepAt == 0 || replyAt == 0 ? 0 : Math.max(0, firstStepAt - replyAt);
		long work = settledAt == 0 || firstStepAt == 0 ? 0 : Math.max(0, settledAt - firstStepAt);
		long result = settledAt == 0 ? 0 : Math.max(0, now - settledAt);
		Timings done = new Timings(batch, model, start, work, result, steps, modelCalls);
		last = done;
		Cobbie.LOGGER.info("[timing] {}", done);
	}

	public static Timings last() {
		return last;
	}
}
