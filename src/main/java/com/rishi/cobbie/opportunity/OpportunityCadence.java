package com.rishi.cobbie.opportunity;

/**
 * When he is allowed to ask the model what to do. Event-driven, with one slow heartbeat.
 *
 * <p>The rule from the brief is one sentence: <b>never re-ask the model about an unchanged situation.</b>
 * An 8B model costs a second or two of its owner's battery and a slice of a small budget, and asking it
 * the same question twice in a row gets the same answer with a fresh download. So the cadence is a
 * strict gate around {@link Situation}:
 *
 * <ul>
 *   <li>a wake the first time, because nothing has been asked yet;</li>
 *   <li>a wake on any change of {@link Situation} — owner moved bucket, health bucket, hunger bucket,
 *       threat state, bag, a job starting or finishing, a different plan, the owner speaking;</li>
 *   <li>a wake after {@link #HEARTBEAT_TICKS} of a scene that has not changed, so a long quiet spell
 *       does not freeze him on a stale decision forever;</li>
 *   <li>never twice inside {@link #MIN_GAP_TICKS}, so a scene that flaps — a dropped item picked up and
 *       dropped again — cannot turn into a poll;</li>
 *   <li>and a quiet spell of {@link #QUIET_AFTER_CHOICE_TICKS} after he starts something of his own, so
 *       a finish is not instantly followed by another start.</li>
 * </ul>
 *
 * <p>State is per body and lives in the entity, not in a static map: a companion who dies must not
 * leave a cadence behind that his next body inherits. Every rule here is arithmetic over integers, so
 * the tests are exact ticks rather than sleeps.
 */
public final class OpportunityCadence {

	/** The slow heartbeat: 1200 ticks is one minute at twenty ticks a second. */
	public static final int HEARTBEAT_TICKS = 1200;

	/** Never two questions inside two seconds, whatever the situation does. */
	public static final int MIN_GAP_TICKS = 40;

	/** After he starts something of his own, two minutes of quiet — the cooldown the old pick kept. */
	public static final int QUIET_AFTER_CHOICE_TICKS = 2400;

	/**
	 * After a WAIT on an unchanged scene, stretch the heartbeat: one minute, one minute, 90 s, two minutes.
	 *
	 * <p>A rejection is an answer, and asking again a minute later gets the same rejection. Rounding up to
	 * the heartbeat keeps one rule instead of two — the slow heartbeat is the floor, and repetition only
	 * lengthens it. A real change still wakes him at once, because the scene really has changed.
	 */
	public static final int WAIT_BACKOFF_TICKS = 600;
	public static final int MAX_WAIT_STREAK = 4;

	/** How long an unchanged scene is held off after {@code streak} rejections in a row. */
	public static long backoffFor(int streak) {
		int bounded = Math.max(1, Math.min(MAX_WAIT_STREAK, streak));
		return Math.max(HEARTBEAT_TICKS, (long) WAIT_BACKOFF_TICKS * bounded);
	}

	private int waitStreak;

	private Situation lastAsked;
	private long lastAskedTick = Long.MIN_VALUE / 4;
	/** Hard quiet after he started his own work: nothing wakes him, because the work is the answer. */
	private long quietUntil = Long.MIN_VALUE / 4;
	/** Soft quiet after a rejection: only an unchanged scene is held off, a real change still wakes him. */
	private long backoffUntil = Long.MIN_VALUE / 4;
	private long heartbeatAt = Long.MIN_VALUE / 4;

	/**
	 * Whether this tick's scene deserves a question.
	 *
	 * <p>The heartbeat is anchored to the last wake rather than to a modulo of the tick count, which is
	 * the shape that produced `waitFor(tickCount % 20 == 0)` — a predicate that matches the first tick
	 * and waits nothing. Here the arithmetic is a subtraction against a stored tick.
	 */
	public boolean shouldAsk(long now, Situation scene) {
		if (scene == null) return false;
		if (now < quietUntil) return false;
		if (lastAsked == null) return true;
		long since = now - lastAskedTick;
		if (since < MIN_GAP_TICKS) return false;
		if (!scene.equals(lastAsked)) return true;   // a real change is always a new question
		if (now < backoffUntil) return false;        // the same unchanged scene, already rejected
		return now >= heartbeatAt;
	}

	/**
	 * Record that the question was asked about this scene, so the next tick compares against it. A scene
	 * that has moved on clears the WAIT streak: the backoff is for repetition, not for having waited.
	 */
	public void noted(long now, Situation scene) {
		if (scene == null) return;
		if (lastAsked != null && !scene.equals(lastAsked)) waitStreak = 0;
		lastAsked = scene;
		lastAskedTick = now;
		heartbeatAt = now + HEARTBEAT_TICKS;
	}

	/** He started something of his own: hold the next question off for the quiet spell. */
	public void chosen(long now) {
		waitStreak = 0;
		quietUntil = now + QUIET_AFTER_CHOICE_TICKS;
	}

	/**
	 * The model rejected everything: wait longer before asking the <b>same unchanged</b> question again.
	 *
	 * <p>Soft on purpose. A rejection is an answer about the scene it was given, so a scene that has moved
	 * on deserves a fresh question immediately; only repetition is held off.
	 */
	public void waited(long now) {
		waitStreak = Math.min(MAX_WAIT_STREAK, waitStreak + 1);
		backoffUntil = now + backoffFor(waitStreak);
		heartbeatAt = backoffUntil;
	}

	/** How many rejections in a row this unchanged scene has had, for a log line. */
	public int waitStreak() {
		return waitStreak;
	}

	/** What a log line can say about the current gate. */
	public String why(long now, Situation scene) {
		if (scene == null) return "no scene";
		if (now < quietUntil) {
			return "quiet for " + (quietUntil - now) + " more ticks after his own project";
		}
		if (lastAsked == null) return "nothing asked yet";
		long since = now - lastAskedTick;
		if (since < MIN_GAP_TICKS) return "asked " + since + " ticks ago";
		if (!scene.equals(lastAsked)) return "changed: " + scene.changesSince(lastAsked);
		if (now < backoffUntil) {
			return "the same scene was rejected " + waitStreak + " time(s); backoff for "
					+ (backoffUntil - now) + " more ticks";
		}
		if (now >= heartbeatAt) return "heartbeat after " + since + " unchanged ticks";
		return "unchanged (" + scene.changesSince(lastAsked) + "), heartbeat in " + (heartbeatAt - now);
	}

	/** The scene of the last question, for tests and for the log. */
	public Situation lastAsked() {
		return lastAsked;
	}
}
