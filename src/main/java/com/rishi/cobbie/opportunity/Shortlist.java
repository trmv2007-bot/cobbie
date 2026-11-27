package com.rishi.cobbie.opportunity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shortlist the model chooses from: at most ten validated opportunities, each with a stable id.
 *
 * <p>Assembling it is where suppression lives, in one place rather than in each candidate builder:
 *
 * <ul>
 *   <li><b>duplicates go</b> — same kind on the same squares is one opportunity, not two lines of the
 *       model's budget saying the same thing;</li>
 *   <li><b>nothing without steps</b> — an opportunity the executor cannot start is not offered, and
 *       that is enforced in {@link Opportunity}'s constructor as well as here;</li>
 *   <li><b>the cap is ten</b>, and it is never padded. A shortlist shorter than five is offered as it
 *       is. Filling the gap with a cheap candidate would be the assessor choosing — a "look around"
 *       nobody can start is exactly the promise-before-the-check this project keeps removing.</li>
 * </ul>
 *
 * <p>Ids are assigned after suppression, so they are dense ({@code op-1} … {@code op-n}) and stable
 * for as long as the shortlist itself is. The order is the assessor's fixed kind order and carries no
 * meaning; the prompt says so in as many words, and the in-game test picks the second candidate to
 * prove the model's choice is the one that runs.
 */
public record Shortlist(List<Opportunity> items) {

	/** How many candidates the model is ever shown. Ten compact lines is about 350 tokens. */
	public static final int MAX = 10;

	public Shortlist {
		items = List.copyOf(items == null ? List.of() : items);
	}

	/** Suppress, number, cap. The only way a shortlist is built. */
	public static Shortlist assemble(List<Opportunity> candidates) {
		List<Opportunity> kept = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (candidates != null) {
			for (Opportunity o : candidates) {
				if (o == null || o.steps().isEmpty()) continue;
				if (!seen.add(o.signature())) continue;
				kept.add(o);
				if (kept.size() == MAX) break;
			}
		}
		List<Opportunity> numbered = new ArrayList<>(kept.size());
		for (int i = 0; i < kept.size(); i++) {
			numbered.add(kept.get(i).withId("op-" + (i + 1)));
		}
		return new Shortlist(numbered);
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	public int size() {
		return items.size();
	}

	/** The opportunity with this id, or null when the answer named something he was not offered. */
	public Opportunity byId(String id) {
		if (id == null) return null;
		String wanted = id.trim().toLowerCase(Locale.ROOT);
		for (Opportunity o : items) {
			if (o.id().equalsIgnoreCase(wanted)) return o;
		}
		return null;
	}

	public List<String> ids() {
		List<String> out = new ArrayList<>(items.size());
		for (Opportunity o : items) out.add(o.id());
		return out;
	}

	/** For a log line: `op-1(mine) op-2(protect)`. */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		for (Opportunity o : items) {
			if (sb.length() > 0) sb.append(' ');
			sb.append(o.id()).append('(').append(o.kind().label()).append(')');
		}
		return sb.toString();
	}

	/** The block the model reads: what it may pick, and the exact shape of the answer. */
	public String promptBlock() {
		StringBuilder sb = new StringBuilder(700);
		sb.append("THINGS YOU COULD START RIGHT NOW (worked out from what you can see; every one of them"
				+ " can be started this second):").append('\n');
		for (Opportunity o : items) {
			sb.append(o.brief()).append('\n');
		}
		sb.append("The ids are only so you can name one. The order means nothing — do not take the first"
				+ " because it is first.").append('\n');
		sb.append("Pick at most one, or none. Reply with {\"choice\":\"op-N\"} to do that one, or ")
				.append("{\"choice\":\"wait\",\"why\":\"<one short reason>\"} if none of them is worth it now.");
		return sb.toString();
	}
}
