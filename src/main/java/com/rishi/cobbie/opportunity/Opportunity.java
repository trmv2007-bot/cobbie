package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;

import java.util.List;
import java.util.Locale;

/**
 * One thing worth doing here, already proved <b>startable</b>, and the steps that would start it.
 *
 * <p>This is the unit the assessor produces and the model chooses between. Every field exists because
 * the choice is not the assessor's to make: the model is handed the reason, the benefit, what it costs,
 * how long it should take, how risky it is, how much it has to do with its owner, and what would make
 * the job pointless — so the pick rests on the situation rather than on an id.
 *
 * <p><b>The invariants are the constructor's, not a caller's.</b> An opportunity with no steps is a
 * promise with nothing under it: the executor would start an empty plan and the chat would describe
 * work that never existed. A step naming a verb the vocabulary does not know is the same lie one
 * level down. A reason or request carrying a newline is a way to write structure into a prompt. All
 * three throw here, so no caller can build one by forgetting.
 *
 * <p>Fields: {@code kind} is the family; {@code reason} is one line about this moment; {@code benefit}
 * is what it is for; {@code requires} is what it spends (empty means nothing); {@code estimatedTicks}
 * is roughly how long it should take at 20 ticks a second; {@code risk} is how much can go wrong;
 * {@code ownerRelevance} is 0..3, 3 being "the owner is what this is about"; {@code cancelsOn} names
 * the events that would make it pointless; {@code request} is the plan's name, the one a person reads;
 * {@code steps} are the exact actions the runner would get.
 */
public record Opportunity(
		String id,
		Kind kind,
		String reason,
		String benefit,
		List<String> requires,
		int estimatedTicks,
		Risk risk,
		int ownerRelevance,
		List<String> cancelsOn,
		String request,
		List<ActionIntent> steps) {

	/** The families the brief names. Declared in full; not all of them are produced — see the assessor. */
	public enum Kind {
		FIND, HELP, PROTECT, RETURN, REPLENISH, INTERACT, BUILD, FARM, MINE, FIGHT, TRADE, WAIT;

		public String label() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum Risk {
		LOW, MEDIUM, HIGH;

		public String label() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Longest one-line field: a prompt is paid for in tokens and a long reason crowds out the rest. */
	public static final int MAX_TEXT = 160;
	public static final int OWNER_RELEVANCE_MAX = 3;

	public Opportunity {
		if (kind == null) throw new IllegalArgumentException("an opportunity needs a kind");
		id = id == null ? "" : id.trim();
		reason = oneLine(reason, "reason");
		benefit = oneLine(benefit, "benefit");
		request = oneLine(request, "request");
		requires = copy(requires);
		cancelsOn = copy(cancelsOn);
		if (estimatedTicks < 1) {
			throw new IllegalArgumentException("opportunity " + id + " estimates " + estimatedTicks
					+ " ticks, which is not a duration");
		}
		if (risk == null) throw new IllegalArgumentException("opportunity " + id + " has no risk");
		if (ownerRelevance < 0 || ownerRelevance > OWNER_RELEVANCE_MAX) {
			throw new IllegalArgumentException("opportunity " + id + " says owner relevance "
					+ ownerRelevance + ", outside 0.." + OWNER_RELEVANCE_MAX);
		}
		if (steps == null || steps.isEmpty()) {
			throw new IllegalArgumentException("opportunity " + id + " (" + kind.label()
					+ ") has no steps — that is a promise with nothing under it");
		}
		steps = List.copyOf(steps);
		for (ActionIntent step : steps) {
			if (step == null || !ActionVocabulary.isAllowed(step.action())) {
				throw new IllegalArgumentException("opportunity " + id + " carries a step no verb knows: "
						+ (step == null ? "null" : step.action()));
			}
		}
	}

	/** The same opportunity under the id the shortlist gives it. */
	public Opportunity withId(String newId) {
		return new Opportunity(newId, kind, reason, benefit, requires, estimatedTicks, risk, ownerRelevance,
				cancelsOn, request, steps);
	}

	/**
	 * What makes two candidates the same thing: the kind, and the exact squares it would act on.
	 *
	 * <p>Two piles of logs in different places are two opportunities; the same pile offered twice is a
	 * shortlist that wastes a line of the model's budget on a duplicate.
	 */
	public String signature() {
		StringBuilder sb = new StringBuilder(kind.label());
		for (ActionIntent step : steps) {
			sb.append('|').append(step.action()).append('@')
					.append(step.x()).append(',').append(step.y()).append(',').append(step.z());
		}
		return sb.toString();
	}

	/** One compact line for the shortlist the model reads: about 35 tokens each. */
	public String brief() {
		return id + " · " + kind.label() + " · " + reason
				+ " · gives: " + benefit
				+ " · needs: " + (requires.isEmpty() ? "nothing" : String.join(", ", requires))
				+ " · ~" + estimatedTicks + "t"
				+ " · risk " + risk.label()
				+ " · owner " + ownerRelevance + "/" + OWNER_RELEVANCE_MAX
				+ " · cancels if: " + (cancelsOn.isEmpty() ? "anything changes" : String.join("; ", cancelsOn));
	}

	private static String oneLine(String raw, String field) {
		if (raw == null) throw new IllegalArgumentException("an opportunity needs a " + field);
		String v = raw.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
		if (v.isEmpty()) throw new IllegalArgumentException("an opportunity needs a " + field);
		return v.length() <= MAX_TEXT ? v : v.substring(0, MAX_TEXT - 3) + "...";
	}

	private static List<String> copy(List<String> raw) {
		if (raw == null) return List.of();
		return raw.stream().filter(s -> s != null && !s.isBlank())
				.map(s -> s.replaceAll("[\\p{Cntrl}]+", " ").trim())
				.toList();
	}
}
