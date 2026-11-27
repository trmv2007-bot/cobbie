package com.rishi.cobbie.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an owner's sentence tells him to remember, read without the model.
 *
 * <p>Two ways to teach him something exist and both end at {@link WorldMemory}: the model can queue a
 * {@code remember} action, and this path reads the sentence directly when the owner says
 * <em>"cobbie remember home is at 12 64 -7"</em>. The second exists because the first is a model call
 * away from working, and a correction that silently fails is worse than no correction at all.
 *
 * <h2>What it reads, and what a miss means</h2>
 * <ol>
 *   <li>a leading {@code remember} / {@code note that} / {@code don't forget};</li>
 *   <li>a trailing coordinate triple — {@code at 12 64 -7} or {@code 12, 64, -7} — which makes this a
 *       <b>place</b>;</li>
 *   <li>the first {@code is} / {@code =} / {@code :} splits what it is about from what he is being told;</li>
 *   <li>with no coordinates the statement is the owner's word and nothing else, so it is stored as
 *       something he wants, never as a fact about the world he has not checked.</li>
 * </ol>
 *
 * <p>A sentence this cannot read is not a claim that it was stored: it returns null and the sentence
 * goes to the model exactly as before. That is the difference between a parser that guesses and one
 * that declines — here a miss costs a model call, never a false "noted".
 */
public final class OwnerWords {

	/** One statement, ready to store: subject and fact, and coordinates when it is a place. */
	public record Said(String subject, String fact, boolean at, int x, int y, int z) {}

	private static final List<String> OPENERS = List.of(
			"please remember that ", "please remember ", "remember that ", "remember ",
			"don't forget that ", "dont forget that ", "don't forget ", "dont forget ",
			"note that ", "note ");

	private static final Pattern COORDS = Pattern.compile(
			"\\s+(?:at\\s+|near\\s+)?(-?\\d{1,6})\\s*[, ]\\s*(-?\\d{1,6})\\s*[, ]\\s*(-?\\d{1,6})\\s*$");

	private static final List<String> SEPARATORS = List.of(" is ", " = ", ": ", ":");

	/**
	 * Words that name nothing on their own. A subject made only of these is the owner thinking out loud
	 * ("remember that"), not a thing to store.
	 */
	private static final java.util.Set<String> POINTERS = java.util.Set.of(
			"it", "that", "this", "them", "these", "those", "something", "anything", "everything",
			"remember", "note", "forget", "thing", "stuff");

	private OwnerWords() {}

	/** The statement in this text, or null when it is not one. */
	public static Said parse(String raw) {
		if (raw == null) return null;
		String text = raw.trim();
		String lower = text.toLowerCase(Locale.ROOT);
		boolean opened = false;
		for (String opener : OPENERS) {
			if (lower.startsWith(opener)) {
				text = text.substring(opener.length()).trim();
				opened = true;
				break;
			}
		}
		if (text.isEmpty()) return null;

		// A trailing triple is a place. It is only believed when the word before it is one an owner uses
		// for coordinates ("at", "near") or when the triple is the whole remainder — "my base is 320 64 -11".
		int x = 0;
		int y = 0;
		int z = 0;
		boolean at = false;
		Matcher coords = COORDS.matcher(text);
		if (coords.find()) {
			String head = text.substring(0, coords.start()).trim();
			if (!head.isEmpty() || opened) {
				x = Integer.parseInt(coords.group(1));
				y = Integer.parseInt(coords.group(2));
				z = Integer.parseInt(coords.group(3));
				at = true;
				text = head;
				text = text.replaceAll("(?i)\\s+(?:is\\s+)?(?:at|near|in)$", "").trim();
			}
		}

		if (text.isEmpty()) return null;
		String subject = text;
		String fact = "";
		for (String separator : SEPARATORS) {
			int cut = text.indexOf(separator);
			if (cut > 0) {
				subject = text.substring(0, cut).trim();
				fact = text.substring(cut + separator.length()).trim();
				break;
			}
		}
		subject = subject.replaceAll("(?i)\\s+is$", "").trim();
		if (subject.length() < 2) return null;
		if (namesNothing(subject)) return null;

		// Without coordinates the sentence is a wish or an opinion, so the coordinates it may still
		// mention are none of his business: only a statement with a place in it is a place.
		return new Said(subject, fact, at, x, y, z);
	}

	/** True when every word of the subject is a pointer: "that", "it", "remember that". */
	private static boolean namesNothing(String subject) {
		String bare = subject.toLowerCase(Locale.ROOT).trim();
		for (String article : List.of("the ", "my ", "our ", "a ", "an ")) {
			if (bare.startsWith(article)) {
				bare = bare.substring(article.length()).trim();
				break;
			}
		}
		if (bare.isEmpty()) return true;
		for (String word : bare.split("[^a-z0-9]+")) {
			if (word.isEmpty()) continue;
			if (!POINTERS.contains(word)) return false;
		}
		return true;
	}
}
