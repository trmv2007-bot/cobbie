package com.rishi.cobbie.opportunity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.brain.LlmClient;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.presence.ActionTiming;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks the model which opportunity to take: one id, or a rejection that means WAIT.
 *
 * <p>This is the whole of the model's authority over what he does next. It is not asked to plan, to write
 * prose, or to describe the world; it is handed a shortlist of things he can start this second and
 * answers with a name from that list. The request goes out with the server's own JSON mode so the shape
 * is not a matter of obedience.
 *
 * <p><b>Every failure is WAIT.</b> No brain configured, a dead socket, an empty answer, prose instead of
 * an object, an id that was never offered: each becomes an explicit rejection carrying its reason, never
 * a quiet pick of the first candidate. The distinction matters because the alternative is invisible — a
 * fallback would look exactly like a model that chose, and the shortlist would be the old picker wearing
 * a new coat. {@link Origin} records which happened, so a session log can tell its own story.
 */
public final class OpportunitySelector {

	/** Where a decision came from: the model's own words, or the failure that stood in for them. */
	public enum Origin {
		MODEL, MODEL_REJECTED, FALLBACK_REJECTED;

		public String label() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * One answer. {@code opportunityId} null means WAIT, and {@code why} always says something a person
	 * could read: for a model rejection it is the model's reason, for a fallback it is what went wrong.
	 */
	public record Decision(String opportunityId, String why, Origin origin) {
		public boolean isWait() {
			return opportunityId == null || opportunityId.isBlank();
		}

		public String describe() {
			return isWait() ? "wait (" + why + ")" : opportunityId;
		}

		public static Decision pick(String id) {
			return new Decision(id == null ? "" : id.trim(), "", Origin.MODEL);
		}

		public static Decision rejected(String why) {
			return new Decision(null, clean(why, "no reason given"), Origin.MODEL_REJECTED);
		}

		public static Decision failedClosed(String why) {
			return new Decision(null, clean(why, "no answer"), Origin.FALLBACK_REJECTED);
		}

		private static String clean(String why, String fallback) {
			if (why == null || why.isBlank()) return fallback;
			String v = why.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
			return v.length() <= 120 ? v : v.substring(0, 117) + "...";
		}
	}

	/** One question at a time: two in flight would race for the same body and the same plan runner. */
	private static final AtomicInteger inFlight = new AtomicInteger();

	private OpportunitySelector() {}

	/** Whether a question is already out, so the tick does not stack a second one behind it. */
	public static boolean busy() {
		return inFlight.get() > 0;
	}

	/**
	 * Read the model's answer against the list it was given. Pure, and the seam every test drives.
	 *
	 * <p>Accepted: exactly one JSON object with {@code choice} naming an id on the list, or
	 * {@code choice:"wait"} with an optional {@code why}. Everything else — an empty string, prose, an
	 * array, an id that is not on the list — fails closed to WAIT with a reason naming the problem.
	 */
	public static Decision parse(Shortlist list, String modelText) {
		if (list == null || list.isEmpty()) return Decision.failedClosed("nothing was offered");
		if (modelText == null || modelText.isBlank()) return Decision.failedClosed("the model answered nothing");
		// An array is a different answer shape, and "the first element" is a guess about which of them it
		// meant. Failing closed is the rule; being helpful here would be reading a choice out of a list.
		int arrayAt = modelText.indexOf('[');
		int objectAt = modelText.indexOf('{');
		if (arrayAt >= 0 && (objectAt < 0 || arrayAt < objectAt)) {
			return Decision.failedClosed("the answer was an array, not one object: " + shorten(modelText));
		}
		JsonObject object = firstObject(modelText);
		if (object == null) {
			return Decision.failedClosed("the answer was not a JSON object: " + shorten(modelText));
		}
		if (!object.has("choice") || !object.get("choice").isJsonPrimitive()) {
			return Decision.failedClosed("the answer had no choice in it: " + shorten(modelText));
		}
		String choice = object.get("choice").getAsString().trim();
		if (choice.equalsIgnoreCase("wait")) {
			String why = object.has("why") && object.get("why").isJsonPrimitive()
					? object.get("why").getAsString()
					: object.has("reason") && object.get("reason").isJsonPrimitive()
							? object.get("reason").getAsString()
							: "none of them was worth it";
			return Decision.rejected(why);
		}
		Opportunity picked = list.byId(choice);
		if (picked == null) {
			return Decision.failedClosed("chose '" + choice + "', which was not on the list");
		}
		return Decision.pick(picked.id());
	}

	/**
	 * Ask, and never throw: the answer arrives as a {@link Decision} that is WAIT whenever anything at all
	 * went wrong.
	 */
	public static CompletableFuture<Decision> ask(CobbieEntity cobbie, Shortlist list) {
		if (cobbie == null || list == null || list.isEmpty()) {
			return CompletableFuture.completedFuture(Decision.failedClosed("nothing was offered"));
		}
		LlmClient client = CobbieBrain.client();
		if (client == null) {
			return CompletableFuture.completedFuture(Decision.failedClosed("my brain is not configured"));
		}
		if (inFlight.incrementAndGet() > 1) {
			inFlight.decrementAndGet();
			return CompletableFuture.completedFuture(Decision.failedClosed("a choice was already being made"));
		}
		ActionTiming.extraModelCall();
		CompletableFuture<LlmClient.Result> answer;
		try {
			answer = client.completeJson(systemPrompt(cobbie), userPrompt(cobbie, list));
		} catch (Exception e) {
			inFlight.decrementAndGet();
			return CompletableFuture.completedFuture(
					Decision.failedClosed("the request could not be built: " + e.getClass().getSimpleName()));
		}
		return answer.handle((result, error) -> {
			inFlight.decrementAndGet();
			if (error != null) {
				return Decision.failedClosed("the choice request failed: " + error.getClass().getSimpleName());
			}
			if (result == null) return Decision.failedClosed("no answer came back");
			if (!result.success()) return Decision.failedClosed("the choice request failed: " + result.error());
			return parse(list, result.content());
		});
	}

	/** Who he is, and the one thing he is being asked. Deliberately short: the list is the content. */
	static String systemPrompt(CobbieEntity cobbie) {
		String name = cobbie == null ? "Cobbie" : cobbie.companionName();
		return "You are " + name + ", a companion living in a Minecraft world, choosing what to do next.\n"
				+ "You are not planning and you are not inventing anything: a list of things you could start"
				+ " RIGHT NOW has been worked out from what you can actually see, and every one of them can"
				+ " be started this second.\n"
				+ "Reply with ONE JSON object and nothing else, no code fences, no words around it:\n"
				+ "{\"choice\":\"op-N\"} to do that one, or {\"choice\":\"wait\",\"why\":\"<one short reason>\"}"
				+ " if none of them is worth doing now.\n"
				+ "Rules:\n"
				+ "1. Only ids from the list. An id you were not given is not a choice.\n"
				+ "2. The order of the list means nothing. Do not take the first one because it is first; take"
				+ " the one worth doing.\n"
				+ "3. If you are beside your owner, what serves them is worth more than what is merely"
				+ " convenient for you.\n"
				+ "4. Rejecting all of them is a real answer and never a failure. Do not pick one you do not"
				+ " think is worth it.";
	}

	/** The scene in one line, then the shortlist. Nothing here is a fact he has not just read. */
	static String userPrompt(CobbieEntity cobbie, Shortlist list) {
		return "Where things stand: " + sceneLine(cobbie) + "\n\n" + list.promptBlock();
	}

	private static String sceneLine(CobbieEntity cobbie) {
		if (cobbie == null) return "unknown";
		StringBuilder sb = new StringBuilder();
		sb.append("health ").append((int) Math.ceil(cobbie.getHealth())).append("/")
				.append((int) cobbie.getMaxHealth())
				.append(", food ").append(cobbie.foodLevel()).append("/20");
		var owner = cobbie.owner();
		if (owner == null) {
			sb.append(", your owner is not here");
		} else {
			sb.append(", your owner is ").append((int) Math.sqrt(cobbie.distanceToSqr(owner))).append(" blocks away");
		}
		sb.append(", ").append(switch (cobbie.threatsNear(16)) {
			case 1 -> "something hostile is coming for you or me";
			case 2 -> "something hostile is in sight";
			case 3 -> "something hostile is coming and something else is in sight";
			default -> "nothing hostile in sight";
		});
		sb.append(", carrying ").append(cobbie.carriedSummary()).append('.');
		return sb.toString();
	}

	/** The first syntactically valid object in the text, tolerating fences and a sentence in front. */
	private static JsonObject firstObject(String text) {
		String body = text.trim();
		int start = body.indexOf('{');
		int end = body.lastIndexOf('}');
		if (start < 0 || end <= start) return null;
		try {
			JsonElement parsed = JsonParser.parseString(body.substring(start, end + 1));
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (Exception malformed) {
			return null;
		}
	}

	private static String shorten(String text) {
		String v = text.replaceAll("\\s+", " ").trim();
		return v.length() <= 60 ? v : v.substring(0, 57) + "...";
	}
}
