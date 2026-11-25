package com.rishi.cobbie.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns one raw LLM reply into a validated, ordered action plan.
 *
 * <p>This is the hostile-input boundary: a local model will happily answer with prose, markdown
 * fences, half a JSON object, invented verbs or {@code "x": "ten"}. Rules:
 * <ul>
 *   <li>Brace matching is depth- and string-aware, so trailing prose or an early {@code {} in
 *       the answer text cannot truncate or over-extend the payload.</li>
 *   <li>Every verb must be in {@link ActionVocabulary}; anything else is dropped <em>and
 *       reported</em> in {@link ActionPlan#problems()} so the model can be told next turn.</li>
 *   <li>{@code walk-to}/{@code mine}/{@code place} must actually carry numeric x, y and z — no
 *       silent defaulting to 0,0,0 (which in 0.4.0 meant mining bedrock at world origin).</li>
 *   <li>At most {@link ActionVocabulary#MAX_ACTIONS} actions; extras are counted as a problem.</li>
 *   <li>Parsing never throws and never leaves Cobbie mute: worst case is his raw text as the
 *       answer with a single {@code answer} action.</li>
 * </ul>
 */
public final class ActionParser {

	/** Coordinate sanity. y is deliberately generous to cover the Nether/End height ranges. */
	private static final int MIN_Y = -128;
	private static final int MAX_Y = 512;
	private static final int MAX_HORIZON = 30_000_000;
	private static final int MAX_ANSWER_CHARS = 4000;
	private static final int MAX_TARGET_CHARS = 120;

	/**
	 * @param answer   what Cobbie says out loud (never blank)
	 * @param actions  validated intents, in the order the model asked for them
	 * @param problems what was rejected, empty when the reply was clean
	 */
	public record ActionPlan(String answer, List<ActionIntent> actions, List<String> problems,
			Boolean doing) {

		/** Plans built without a declaration: the caller decides from the words. */
		public ActionPlan(String answer, List<ActionIntent> actions, List<String> problems) {
			this(answer, actions, problems, null);
		}

		public ActionPlan {
			actions = actions == null ? List.of() : List.copyOf(actions);
			problems = problems == null ? List.of() : List.copyOf(problems);
		}

		/**
		 * Whether the reply <em>declared</em> it was acting, or null when it did not say.
		 *
		 * <p>This is the contract that replaced a regex over the answer's wording: the model marks its
		 * own reply, so "he promised and did nothing" becomes a contradiction the code can see rather
		 * than a phrase it has to guess at. Null means an older or malformed reply, and the caller falls
		 * back to reading the words.
		 */
		public Boolean declaredAction() {
			return doing;
		}

		public boolean hasWorldAction() {
			for (ActionIntent a : actions) {
				if (ActionVocabulary.isWorldChanging(a.action())) return true;
			}
			return false;
		}

		/** Feed these back to the model so it can correct itself instead of guessing. */
		public String problemsAsText() {
			return problems.isEmpty() ? "" : String.join("; ", problems);
		}
	}

	private ActionParser() {}

	public static ActionPlan parse(String llmResponse) {
		if (llmResponse == null || llmResponse.isBlank()) {
			return new ActionPlan("...", List.of(silentAnswer()), List.of("the model returned nothing"));
		}

		String json = extractJson(llmResponse);
		if (json == null) {
			// No JSON at all: plain chatter. Not an error, but no actions either.
			return new ActionPlan(clean(llmResponse), List.of(silentAnswer()), List.of());
		}

		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return new ActionPlan(salvageAnswer(llmResponse), List.of(silentAnswer()),
						List.of("reply was JSON but not an object"));
			}
			root = parsed.getAsJsonObject();
		} catch (Exception malformed) {
			// The model fumbled its braces. Its words are still in there: show those, never the payload.
			return new ActionPlan(salvageAnswer(llmResponse), List.of(silentAnswer()),
					List.of("malformed JSON (" + malformed.getClass().getSimpleName() + ")"));
		}

		String answer = clean(root.has("answer") && root.get("answer").isJsonPrimitive()
				? root.get("answer").getAsString()
				: salvageAnswer(llmResponse));
		Boolean doing = null;
		if (root.has("doing") && root.get("doing").isJsonPrimitive()) {
			var d = root.get("doing");
			if (d.getAsJsonPrimitive().isBoolean()) doing = d.getAsBoolean();
		}

		List<ActionIntent> actions = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		JsonElement actionsEl = root.get("actions");
		if (actionsEl == null || actionsEl.isJsonNull()) {
			actions.add(silentAnswer());
			return new ActionPlan(answer, actions, problems, doing);
		}
		if (!actionsEl.isJsonArray()) {
			problems.add("\"actions\" must be an array");
			actions.add(silentAnswer());
			return new ActionPlan(answer, actions, problems, doing);
		}

		JsonArray arr = actionsEl.getAsJsonArray();
		if (arr.size() > ActionVocabulary.MAX_ACTIONS) {
			problems.add("asked for " + arr.size() + " actions, capped at " + ActionVocabulary.MAX_ACTIONS);
		}
		int index = 0;
		for (JsonElement el : arr) {
			index++;
			if (index > ActionVocabulary.MAX_ACTIONS) break;
			if (!el.isJsonObject()) {
				problems.add("action #" + index + " is not an object");
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			ActionIntent intent = toIntent(o, index, problems);
			if (intent != null) actions.add(intent);
		}

		if (actions.isEmpty()) {
			actions.add(silentAnswer());
			if (problems.isEmpty()) problems.add("no usable actions in reply");
		}
		return new ActionPlan(answer, actions, problems, doing);
	}

	// ---- one action entry ----

	private static ActionIntent toIntent(JsonObject o, int index, List<String> problems) {
		JsonElement nameEl = o.get("action");
		if (nameEl == null || !nameEl.isJsonPrimitive()) {
			problems.add("action #" + index + " has no \"action\" name");
			return null;
		}
		String raw = nameEl.getAsString();
		String verb = ActionVocabulary.normalise(raw);
		if (verb == null) {
			problems.add("action #" + index + " uses unknown verb \"" + trim(raw, 40) + "\"");
			return null;
		}

		String target = "";
		JsonElement targetEl = o.get("target");
		if (targetEl != null && targetEl.isJsonPrimitive()) {
			try {
				target = trim(targetEl.getAsString(), MAX_TARGET_CHARS);
			} catch (Exception e) {
				problems.add("action #" + index + " has a non-text target");
			}
		}
		if ((verb.equals(ActionVocabulary.PLACE) || verb.equals(ActionVocabulary.ATTACK)
				|| verb.equals(ActionVocabulary.CHAT) || verb.equals(ActionVocabulary.CRAFT))
				&& target.isBlank()) {
			problems.add("action #" + index + " (" + verb + ") needs a non-empty \"target\"");
			return null;
		}

		int x = 0, y = 0, z = 0;
		if (ActionVocabulary.needsPosition(verb)) {
			Integer px = coord(o, "x", index, problems);
			Integer py = coord(o, "y", index, problems);
			Integer pz = coord(o, "z", index, problems);
			if (px == null || py == null || pz == null) return null;
			if (py < MIN_Y || py > MAX_Y) {
				problems.add("action #" + index + " has y=" + py + " outside the world");
				return null;
			}
			if (Math.abs(px) > MAX_HORIZON || Math.abs(pz) > MAX_HORIZON) {
				problems.add("action #" + index + " has coordinates outside the map");
				return null;
			}
			x = px;
			y = py;
			z = pz;
			// (0,0,0) is legal but almost always a model mistake — say so without blocking it.
			if (x == 0 && y == 0 && z == 0) {
				problems.add("action #" + index + " targets exactly 0 0 0, which is usually wrong");
			}
		}

		String data = "";
		JsonElement dataEl = o.get("data");
		if (dataEl != null && dataEl.isJsonPrimitive()) {
			data = trim(dataEl.getAsString(), MAX_TARGET_CHARS);
		}
		return new ActionIntent(verb, target, x, y, z, data);
	}

	/** Required numeric coordinate, or null (with a recorded problem) when absent/unusable. */
	private static Integer coord(JsonObject o, String key, int index, List<String> problems) {
		JsonElement el = o.get(key);
		if (el == null || el.isJsonNull()) {
			problems.add("action #" + index + " is missing coordinate \"" + key + "\"");
			return null;
		}
		try {
			if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
			// Models love quoting numbers.
			return Integer.valueOf(el.getAsString().trim());
		} catch (Exception e) {
			problems.add("action #" + index + " has a non-numeric \"" + key + "\"");
			return null;
		}
	}

	// ---- helpers ----

	private static ActionIntent silentAnswer() {
		return new ActionIntent(ActionVocabulary.ANSWER, "", 0, 0, 0, "");
	}


	/**
	 * Last resort for a payload too broken to parse: lift the text of the {@code answer} field out
	 * of it. A friend who replies with {@code {"answer":"hi","actions":[}]}} is still saying hello;
	 * a friend who replies with braces is a JSON parser with a mouth.
	 */
	private static String salvageAnswer(String raw) {
		if (raw == null || raw.isBlank()) return "...";
		int key = raw.indexOf("\"answer\"");
		if (key >= 0) {
			int colon = raw.indexOf(':', key + "\"answer\"".length());
			int quote = colon < 0 ? -1 : raw.indexOf('"', colon + 1);
			if (quote >= 0) {
				StringBuilder text = new StringBuilder();
				boolean escaped = false;
				for (int i = quote + 1; i < raw.length(); i++) {
					char c = raw.charAt(i);
					if (escaped) {
						text.append(c == 'n' ? ' ' : c);
						escaped = false;
					} else if (c == '\\') {
						escaped = true;
					} else if (c == '"') {
						break;
					} else {
						text.append(c);
					}
				}
				if (!text.toString().isBlank()) return clean(text.toString());
			}
		}
		// No answer field at all: drop the JSON scaffolding and keep whatever words are left.
		String text = raw.trim();
		int actions = text.indexOf("\"actions\"");
		if (actions > 0) text = text.substring(0, actions);
		text = text.replaceAll("^[\s{,\"]+", "").replaceAll("[\s},\"]+$", "");
		return clean(text.isBlank() ? "..." : text);
	}

	/** Unwrap an answer that is itself a JSON object: models sometimes nest one inside the other. */
	private static String unwrapNestedJson(String text) {
		if (text == null) return null;
		String t = text.trim();
		if (!t.startsWith("{") || !t.contains("\"answer\"")) return text;
		int key = t.indexOf("\"answer\"");
		int colon = t.indexOf(':', key);
		int quote = colon < 0 ? -1 : t.indexOf('"', colon + 1);
		if (quote < 0) return text;
		StringBuilder out = new StringBuilder();
		boolean escaped = false;
		for (int i = quote + 1; i < t.length(); i++) {
			char c = t.charAt(i);
			if (escaped) {
				out.append(c == 'n' ? ' ' : c);
				escaped = false;
			} else if (c == 92) {   // backslash
				escaped = true;
			} else if (c == '"') {
				break;
			} else {
				out.append(c);
			}
		}
		return out.length() == 0 ? text : out.toString();
	}

	private static String clean(String text) {
		if (text == null) return "...";
		text = unwrapNestedJson(text);
		// "*waves*" is a stage direction, not speech: he has a wave action for that.
		text = text.replaceAll("\\*[^*\\n]{1,60}\\*", " ").replaceAll("\\s{2,}", " ").trim();
		String s = text.replace("\r", " ").trim();
		// Some models wrap everything in a code fence even when told not to.
		s = s.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
		if (s.isEmpty()) return "...";
		s = s.replaceAll("\\s+", " ");
		return s.length() > MAX_ANSWER_CHARS ? s.substring(0, MAX_ANSWER_CHARS) + "..." : s;
	}

	private static String trim(String s, int max) {
		if (s == null) return "";
		String v = s.trim();
		return v.length() > max ? v.substring(0, max) : v;
	}

	/**
	 * First balanced {@code {...}} in the text, ignoring braces inside JSON strings.
	 * Returns null when there is no complete object.
	 */
	static String extractJson(String text) {
		if (text == null) return null;
		String s = text;
		int start = s.indexOf('{');
		if (start < 0) return null;

		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) return s.substring(start, i + 1);
			}
		}
		// Unbalanced: try the fenced-object case where the model cut off mid-stream.
		return null;
	}

	/** Lower-case verb list, used in the prompt and by tests. */
	public static String allowedVerbs() {
		return ActionVocabulary.describe().toLowerCase(Locale.ROOT);
	}
}
