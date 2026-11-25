package com.rishi.cobbie.presence;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskStep;

import java.util.Locale;

/**
 * The line he says next — what he is about to do, then what came of it.
 *
 * <p>Rules that make him bearable to listen to:
 * <ul>
 *   <li><b>Act, then speak.</b> One short line about the step he is starting, the step happens, one
 *       short line about how it went. No announcing a whole plan before any of it has happened.</li>
 *   <li><b>Never coordinates.</b> "on my way" is a thing a friend says; "heading to 128 64 -33" is a
 *       debug log. Every line is scrubbed of numbers that look like a position.</li>
 *   <li><b>Never a flood.</b> Only one line can be waiting: a newer one replaces an older one, so a
 *       plan whose steps land faster than the cooldown produces fewer lines, never more.</li>
 * </ul>
 *
 * <p>The queue is deliberately tiny and clock-injected, so the cadence is a unit test rather than a
 * thing you have to play the game to judge.
 */
public final class Narrator {

	/** Longest line he will ever say about his own work. */
	private static final int MAX_LINE = 110;
	/** How much of a failure reason is worth repeating. */
	private static final int MAX_REASON = 60;

	/** A position, in the several shapes the game and the planner print one in. */
	private static final java.util.regex.Pattern COORDS =
			java.util.regex.Pattern.compile("-?\\d+\\s+-?\\d+(\\s+-?\\d+)?|\\bat\\s+-?\\d|\\[\\s*-?\\d+.*?]");

	private String pending;
	/**
	 * When he last spoke. "Never" is {@link Long#MIN_VALUE} rather than 0: 0 is a real timestamp, so using it
	 * as the sentinel let a call at epoch zero past the gap — which the cadence test caught by starting its
	 * clock at zero instead of at the wall clock.
	 */
	private long lastSpokeAt = Long.MIN_VALUE;
	private static final long NEVER = Long.MIN_VALUE;

	/** Offer a line. The newest one wins; an older unsaid line is dropped rather than queued up. */
	public synchronized void offer(String line) {
		String clean = scrub(line);
		if (clean == null || clean.isBlank()) return;
		pending = clean;
	}

	/**
	 * @return the line to say now, or null while the minimum gap has not passed or nothing is
	 *         waiting. The caller is responsible for actually saying it.
	 */
	public synchronized String next(long nowMillis, int minGapMillis) {
		if (pending == null) return null;
		if (lastSpokeAt != NEVER && nowMillis - lastSpokeAt < Math.max(0, minGapMillis)) return null;
		String line = pending;
		pending = null;
		lastSpokeAt = nowMillis;
		return line;
	}

	/** Somebody else just said something out loud; hold the next line for the usual gap. */
	public synchronized void noteSpokenAt(long nowMillis) {
		this.lastSpokeAt = nowMillis;
	}

	/** Forget anything unsaid — a cancelled job's last line should not arrive two minutes later. */
	public synchronized void clear() {
		pending = null;
	}

	public synchronized boolean hasPending() {
		return pending != null;
	}

	// ==================================================================
	// Wording
	// ==================================================================

	/** What he says as a step begins. Null means "say nothing about this one". */
	public static String stepStart(ActionIntent intent) {
		if (intent == null) return null;
		String verb = intent.action() == null ? "" : intent.action();
		String what = shortTarget(intent.target());
		return switch (verb) {
			case ActionVocabulary.WALK_TO -> "on my way";
			case ActionVocabulary.MINE -> what == null ? "digging in" : "going for that " + what;
			case ActionVocabulary.PLACE -> what == null ? "setting this down" : "putting down a " + what;
			case ActionVocabulary.LIGHT -> "putting a torch down";
			case ActionVocabulary.ATTACK -> what == null ? "right, let's have it" : "careful, a " + what;
			case ActionVocabulary.COLLECT -> "grabbing that";
			case ActionVocabulary.EQUIP -> what == null ? "getting ready" : "getting my " + what;
			case ActionVocabulary.GIVE -> what == null ? "here you go" : "here is that " + what;
			case ActionVocabulary.DROP -> what == null ? "dropping this" : "dropping the " + what;
			case ActionVocabulary.FOLLOW -> "right behind you";
			case ActionVocabulary.STAY -> "staying put";
			case ActionVocabulary.WAVE -> "hullo";
			case ActionVocabulary.EXPLORE -> "going to have a look";
			case ActionVocabulary.CRAFT -> what == null ? "making something" : "making " + article(what) + what;
			case ActionVocabulary.SMELT -> what == null ? "getting the furnace going" : "cooking that " + what;
			case ActionVocabulary.TAKE -> what == null ? "having a look in there" : "getting the " + what;
			case ActionVocabulary.PUT -> what == null ? "putting this away" : "putting the " + what + " away";
			default -> null;
		};
	}

	/** What he says when a step settles. One line either way; the failure keeps its reason. */
	public static String stepResult(ActionIntent intent, boolean success, String summary) {
		if (intent == null) return null;
		String verb = intent.action() == null ? "" : intent.action();
		if (success) {
			return switch (verb) {
				case ActionVocabulary.MINE -> "got it";
				case ActionVocabulary.PLACE, ActionVocabulary.LIGHT -> "done, that's in place";
				case ActionVocabulary.WALK_TO -> "here";
				case ActionVocabulary.COLLECT -> "picked it up";
				case ActionVocabulary.ATTACK -> "that one's dealt with";
				case ActionVocabulary.EQUIP -> "ready";
				case ActionVocabulary.GIVE -> "yours";
				case ActionVocabulary.DROP -> "dropped it";
				case ActionVocabulary.CRAFT -> "made it";
				case ActionVocabulary.SMELT -> "that's cooked";
				case ActionVocabulary.TAKE -> "got it";
				case ActionVocabulary.PUT -> "put away";
				case ActionVocabulary.FOLLOW, ActionVocabulary.STAY, ActionVocabulary.WAVE,
						ActionVocabulary.EXPLORE -> null;
				default -> null;
			};
		}
		String reason = reason(summary);
		String why = reason == null ? "" : " — " + reason;
		return switch (verb) {
			case ActionVocabulary.MINE -> "couldn't get through that" + why;
			case ActionVocabulary.PLACE, ActionVocabulary.LIGHT -> "couldn't put that down" + why;
			case ActionVocabulary.WALK_TO -> "couldn't get over there" + why;
			case ActionVocabulary.COLLECT -> "couldn't pick that up" + why;
			case ActionVocabulary.ATTACK -> "couldn't finish that fight" + why;
			case ActionVocabulary.EQUIP -> "couldn't put that on" + why;
			case ActionVocabulary.GIVE, ActionVocabulary.DROP -> "couldn't handle that" + why;
			case ActionVocabulary.CRAFT -> "couldn't make that" + why;
			case ActionVocabulary.SMELT -> "couldn't get that cooked" + why;
			case ActionVocabulary.TAKE -> "couldn't take that" + why;
			case ActionVocabulary.PUT -> "couldn't put that away" + why;
			default -> "that didn't work" + why;
		};
	}

	/** A neutral label for one step, as the panel lists it: "mining stone". */
	public static String stepLabel(ActionIntent intent) {
		if (intent == null) return "standing by";
		String verb = intent.action() == null ? "" : intent.action();
		String what = shortTarget(intent.target());
		String word = switch (verb) {
			case ActionVocabulary.WALK_TO -> "walking over";
			case ActionVocabulary.MINE -> "mining";
			case ActionVocabulary.PLACE -> "placing";
			case ActionVocabulary.LIGHT -> "placing a torch";
			case ActionVocabulary.ATTACK -> "fighting";
			case ActionVocabulary.COLLECT -> "picking up";
			case ActionVocabulary.EQUIP -> "equipping";
			case ActionVocabulary.GIVE -> "handing over";
			case ActionVocabulary.DROP -> "dropping";
			case ActionVocabulary.FOLLOW -> "following you";
			case ActionVocabulary.STAY -> "staying put";
			case ActionVocabulary.WAVE -> "waving";
			case ActionVocabulary.EXPLORE -> "exploring";
			case ActionVocabulary.CRAFT -> "making";
			case ActionVocabulary.SMELT -> "cooking";
			case ActionVocabulary.TAKE -> "taking";
			case ActionVocabulary.PUT -> "storing";
			case ActionVocabulary.CHAT -> "talking";
			case ActionVocabulary.LOOKUP -> "looking something up";
			case ActionVocabulary.WAIT -> "waiting";
			default -> "standing by";
		};
		if (what == null || word.contains(" ")) return word;
		return word + " " + what;
	}

	/** The one line that closes a job. */
	public static String jobLine(TaskPlan plan) {
		if (plan == null) return null;
		int done = 0;
		int failed = 0;
		for (TaskStep step : plan.steps()) {
			if (step.status() == TaskStep.Status.DONE) done++;
			if (step.status() == TaskStep.Status.FAILED) failed++;
		}
		return switch (plan.status()) {
			case COMPLETED -> plan.alreadyDoneCount() >= done && done > 0
					? "nothing left to do — the world had already done that"
					: done <= 1 ? "all done" : "all done — " + done + " things sorted";
			case CANCELLED -> "stopped, like you asked";
			case EXPIRED -> "gave up on that one, it was taking too long";
			case FAILED -> failed == 0
					? "that did not come together"
					: "partly done — " + (done > 0 ? done + " done, " : "") + failed
							+ (failed == 1 ? " I couldn't manage" : " I couldn't manage");
			default -> "done";
		};
	}

	/**
	 * The first sentence of a model reply, for the moment before he starts work. The whole answer is
	 * still what memory keeps and what a purely conversational reply shows.
	 */
	public static String firstSentence(String answer) {
		if (answer == null) return null;
		String text = answer.replaceAll("\\s+", " ").trim();
		if (text.isEmpty()) return null;
		int end = text.length();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '.' || c == '!' || c == '?') {
				// Not a decimal point or a dotted id.
				boolean digitBefore = i > 0 && Character.isDigit(text.charAt(i - 1));
				boolean digitAfter = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
				if (digitBefore && digitAfter) continue;
				end = i + 1;
				break;
			}
		}
		return scrub(text.substring(0, end));
	}

	// ---- helpers ----

	/** "an iron ingot", "a wooden pickaxe", "sticks" — the article a person would use, or none. */
	private static String article(String noun) {
		if (noun == null || noun.isEmpty()) return "";
		String n = noun.toLowerCase(Locale.ROOT);
		if (n.endsWith("s")) return "";
		return (n.startsWith("a") || n.startsWith("e") || n.startsWith("i") || n.startsWith("o")
				|| n.startsWith("u")) ? "an " : "a ";
	}

	/** "minecraft:oak_log" -> "oak log"; blank and nonsense -> null. */
	static String shortTarget(String target) {
		if (target == null) return null;
		String t = target.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty()) return null;
		if (t.startsWith("minecraft:")) t = t.substring("minecraft:".length());
		if (t.contains(":")) t = t.substring(t.indexOf(':') + 1);
		t = t.replace('_', ' ').trim();
		if (t.isEmpty() || t.length() > 24) return null;
		// A model sometimes puts a sentence in the target field; that is not a noun he can use.
		if (t.contains(" ") && t.split(" ").length > 3) return null;
		return t;
	}

	/** A failure reason worth repeating: coordinate-free, lower case, short. */
	private static String reason(String summary) {
		String s = scrub(summary);
		if (s == null || s.isBlank()) return null;
		s = s.toLowerCase(Locale.ROOT);
		// Drop the "could not ... <noun phrase>" framing the executor already implies.
		if (s.length() > MAX_REASON) s = s.substring(0, MAX_REASON).trim();
		return s.isBlank() ? null : s;
	}

	/** Strip positions and JSON debris, collapse whitespace, clamp the length. */
	static String scrub(String line) {
		if (line == null) return null;
		String v = COORDS.matcher(line).replaceAll("");
		v = v.replaceAll("[\\[\\]{}]", " ").replaceAll("\\s+", " ").trim();
		v = v.replaceAll("^[\\s,;:-]+", "").replaceAll("[\\s,;:-]+$", "");
		// Removing a position leaves its preposition behind: "heading over to" -> "heading over".
		v = v.replaceAll("(?i)\\s+(to|at|in|on|near|by|from|around|towards?)$", "").trim();
		if (v.isEmpty()) return null;
		if (v.length() > MAX_LINE) {
			int cut = v.lastIndexOf(' ', MAX_LINE);
			v = v.substring(0, cut > 20 ? cut : MAX_LINE).trim();
		}
		return v;
	}

	/** Does this text still carry something that looks like a position? Used by the tests. */
	public static boolean mentionsCoordinates(String line) {
		return line != null && COORDS.matcher(line).find();
	}
}
