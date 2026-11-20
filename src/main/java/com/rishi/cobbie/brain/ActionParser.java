package com.rishi.cobbie.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.action.ActionIntent;

/**
 * Parses the LLM's reply into Cobbie's action format.
 *
 * Contract (mirrors Verity's, which we learned the hard way):
 *   {"answer": "what Cobbie says", "actions": [{"action": "...", "target": "...", "x": 0, "y": 0, "z": 0, "data": ""}]}
 *
 * NEVER-SILENT RULE: if parsing fails for ANY reason, return a fallback
 * ActionPlan with the raw text as the answer and a single "answer" action.
 * Cobbie must never go quiet — that was Verity's #1 failure.
 */
public final class ActionParser {

	public record ActionPlan(String answer, java.util.List<ActionIntent> actions) {}

	private ActionParser() {}

	public static ActionPlan parse(String llmResponse) {
		if (llmResponse == null || llmResponse.isBlank()) {
			return new ActionPlan("...", java.util.List.of(new ActionIntent("answer", "", 0, 0, 0, "")));
		}

		String json = extractJson(llmResponse);
		if (json == null) {
			return new ActionPlan(llmResponse.trim(), java.util.List.of(new ActionIntent("answer", "", 0, 0, 0, "")));
		}

		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			String answer = root.has("answer") && !root.get("answer").isJsonNull()
					? root.get("answer").getAsString()
					: llmResponse.trim();

			java.util.List<ActionIntent> actions = new java.util.ArrayList<>();
			if (root.has("actions") && root.get("actions").isJsonArray()) {
				JsonArray arr = root.getAsJsonArray("actions");
				for (JsonElement el : arr) {
					if (el.isJsonObject()) {
						JsonObject ao = el.getAsJsonObject();
						String action = ao.has("action") ? ao.get("action").getAsString() : "answer";
						String target = ao.has("target") ? ao.get("target").getAsString() : "";
						int x = ao.has("x") ? ao.get("x").getAsInt() : 0;
						int y = ao.has("y") ? ao.get("y").getAsInt() : 0;
						int z = ao.has("z") ? ao.get("z").getAsInt() : 0;
						String data = ao.has("data") ? ao.get("data").getAsString() : "";
						actions.add(new ActionIntent(action, target, x, y, z, data));
					}
				}
			}
			if (actions.isEmpty()) {
				actions.add(new ActionIntent("answer", "", 0, 0, 0, ""));
			}
			return new ActionPlan(answer, actions);
		} catch (Exception e) {
			return new ActionPlan(llmResponse.trim(), java.util.List.of(new ActionIntent("answer", "", 0, 0, 0, "")));
		}
	}

	/** Find the outermost {...} block, ignoring any prose around it. */
	private static String extractJson(String text) {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1);
		}
		return null;
	}
}
