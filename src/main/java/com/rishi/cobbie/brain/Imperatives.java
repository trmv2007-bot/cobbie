package com.rishi.cobbie.brain;

import java.util.List;
import java.util.Locale;

/**
 * Short commands a player actually types, handled without asking the model.
 *
 * <p>The model is good at conversation and bad at "do exactly what I said": in a real session
 * "wave bruh" produced "Waving hello!" with no action behind it, and "come here" produced "Coming
 * right over!" without moving. Prompt rules did not fix that. This is the deterministic path for the
 * handful of imperatives that need no coordinates and no judgement — if one of these matches, the verb
 * runs, whatever the model would have said.
 *
 * <p>Deliberately conservative: only messages of a few words, only at the start of the message, and
 * only verbs the owner has permitted. Anything ambiguous goes to the model as before.
 */
final class Imperatives {

	/** A recognised command: the verb to queue and its target (item id, usually empty). */
	record Hit(String verb, String target) {}

	/** Words that are noise around the verb, stripped before matching a target item. */
	private static final List<String> FILLER = List.of(
			"cobbie", "please", "pls", "bro", "bruh", "buddy", "man", "now", "quick", "quickly",
			"your", "ur", "youre", "the", "a", "an", "some", "all", "of", "those", "these", "that", "this", "it");

	private Imperatives() {}

	/** "diamonds" is "diamond": players pluralise, the registry does not. */
	private static String singular(String word) {
		if (word.length() > 4 && word.endsWith("s") && !word.endsWith("ss")) {
			return word.substring(0, word.length() - 1);
		}
		return word;
	}


	/**
	 * @return the command to run, or null when this is normal conversation.
	 */
	static Hit match(String raw) {
		if (raw == null) return null;
		String text = raw.toLowerCase(Locale.ROOT).replaceAll("[!.,?]+", " ").trim().replaceAll("\\s+", " ");
		if (text.isEmpty() || text.split(" ").length > 8) return null;

		List<String> words = List.of(text.split(" ")).stream()
				.filter(w -> !FILLER.contains(w))
				.toList();
		if (words.isEmpty()) return null;
		String head = words.get(0);
		// Matching happens on the words that are left, so "cobbie come" and "come" are the same request.
		String core = String.join(" ", words);

		if (head.equals("wave") || core.equals("wave hello") || core.equals("wave hi") || core.equals("say hi")) {
			return new Hit("wave", "");
		}
		if (head.equals("stay") || head.equals("stop") || core.equals("wait there") || head.equals("halt")) {
			return new Hit("stay", "");
		}
		if (core.equals("come") || core.equals("come here") || core.equals("come to me")
				|| core.equals("follow me") || core.equals("follow") || core.equals("come with me")) {
			return new Hit("follow", "");
		}
		if (head.equals("pick") && (text.contains("up") || text.contains("item") || text.contains("that"))) {
			return new Hit("collect", "");
		}
		if (head.equals("inventory") || head.equals("bag") || text.contains("what do you have")
				|| text.contains("what have you got") || text.contains("carrying")) {
			return new Hit("bag", "");
		}
		if (head.equals("drop") || core.equals("drop it") || head.equals("toss")) {
			String target = words.size() > 1 ? singular(String.join("_", words.subList(1, words.size()))) : "";
			return new Hit("drop", target);
		}
		if (head.equals("look") && words.contains("up")) {
			String query = text.substring(text.indexOf("up") + 2).trim();
			return query.isEmpty() ? null : new Hit("lookup", query);
		}
		if (text.startsWith("search for ")) {
			return new Hit("lookup", text.substring("search for ".length()).trim());
		}
		return null;
	}
}
