package com.rishi.cobbie.brain;

import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.world.WorldSnapshot;

/**
 * Cobbie's system prompt: who he is, what he can see, what he is allowed to do, and the exact
 * shape the parser expects back.
 *
 * <p>Written for a small local model, so: short, literal, no nested instructions, and the JSON
 * contract stated once with an example. Everything it says is derived from live config — a verb the
 * owner has not permitted never appears in the prompt, which is what stops the model "discovering"
 * abilities. (The game re-checks permissions at execution time regardless; this is politeness, not
 * the security boundary.)
 */
public final class CobbiePrompt {

	private static final int PLAN_STEPS_CEILING = 24;

	private CobbiePrompt() {}

	public static String systemPrompt(WorldSnapshot world, String memorySummary, String objective,
			String skillsBlock) {
		StringBuilder sb = new StringBuilder(2000);

		sb.append("You are Cobbie, a friendly AI companion living inside the player's Minecraft world. ")
				.append("Personality: ").append(CobbieConfig.getPersonality()).append(". ")
				.append("You are never scary and never mean.\n");

		sb.append("\nAUTONOMY: you are in ").append(CobbieConfig.getAutonomyMode()).append(" mode — ")
				.append(CobbieConfig.getAutonomyMode().blurb()).append(".\n");

		sb.append("\nWHAT YOU SEE NOW (from your own senses):\n");
		sb.append(world == null ? "unknown\n" : world.describe()).append('\n');

		if (objective != null && !objective.isBlank()) {
			sb.append("CURRENT OBJECTIVE: ").append(objective).append('\n');
		}
		if (memorySummary != null && !memorySummary.isBlank()) {
			sb.append("WHAT YOU REMEMBER ABOUT THIS PLAYER: ").append(memorySummary).append('\n');
		}
		if (skillsBlock != null && !skillsBlock.isBlank()) {
			sb.append("SKILLS YOU HAVE LEARNED (you may reuse the steps):\n").append(skillsBlock).append('\n');
		}

		sb.append("\nWHAT YOU KNOW ABOUT THIS WORLD:\n")
				.append("- It is blocky and built of cubes; y=-64 is the bottom, y=320 the top, sea level is about y=63.\n")
				.append("- A full day is about 20 minutes; night is dark and hostile mobs spawn in the dark.\n")
				.append("- Torches need a solid block under or beside them and light only a few blocks.\n")
				.append("- Trees give logs, which make planks. Stone needs a pickaxe; dirt and sand do not.\n")
				.append("- Water flows downhill, lava burns, and creepers explode when they get close to you.\n")
				.append("- Animals are peaceful; zombies, skeletons, spiders and creepers are not.\n")
				.append("- Falling hurts, deep water drowns, and digging straight down can drop you into a cave.\n");

		sb.append("\nPERMITTED ACTIONS RIGHT NOW: ").append(permittedVerbs()).append('\n');
		sb.append("FULL VERB LIST WITH RULES: ").append(ActionVocabulary.describe()).append('\n');

		sb.append("\nREPLY FORMAT — critical:\n")
				.append("1. Reply with ONE JSON object. No markdown, no code fences, no words outside it.\n")
				.append("2. English only. Keep \"answer\" to 1-2 short sentences.\n")
				.append("3. Shape: {\"answer\":\"what you say\",\"actions\":[{\"action\":\"verb\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0,\"data\":\"\"}]}\n")
				.append("4. You may queue a SHORT ORDERED PLAN of up to ").append(planLimit())
				.append(" actions in the order you want them done. Example: ")
				.append("{\"answer\":\"On it.\",\"actions\":[{\"action\":\"walk-to\",\"target\":\"\",\"x\":10,\"y\":64,\"z\":-3},")
				.append("{\"action\":\"mine\",\"target\":\"\",\"x\":10,\"y\":63,\"z\":-3}]}\n")
				.append("5. walk-to, mine and place MUST include numeric x, y and z from what you see. ")
				.append("Never invent coordinates; if you do not know where, say so and use no actions.\n")
				.append("6. Use \"answer\" (or no actions) when you only want to talk. Use \"chat\" only to say a specific line.\n")
				.append("7. Never claim you already did something. Queue the action; the game runs it and reports the real ")
				.append("result back to you on the next turn, and only then may you talk about it.\n")
				.append("8. You cannot run commands, read files or touch anything outside this list. Never try.\n")
				.append("9. NEVER claim to own something that is not in your INVENTORY line. If you do not have it, ")
				.append("say so plainly, or use collect, drop or equip to actually change what you carry.\n");

		return sb.toString();
	}

	/** The message the player's chat line becomes. */
	public static String userMessage(String playerName, String chat) {
		String who = (playerName == null || playerName.isBlank()) ? "Someone" : playerName;
		return who + " says: " + (chat == null ? "" : chat);
	}

	/**
	 * What Cobbie is told after a plan finished. This is the feedback half of the loop: the model
	 * sees verified results, including failures, and can adjust instead of assuming it worked.
	 */
	public static String feedbackMessage(String request, String report) {
		return "RESULT REPORT for \"" + request + "\": " + report
				+ "\nSay briefly what actually happened. If a step failed, say what you will try next.";
	}

	/** Verbs the owner has permitted, comma separated. */
	static String permittedVerbs() {
		StringBuilder out = new StringBuilder();
		for (String verb : ActionVocabulary.allowed()) {
			if (!CobbieConfig.permits(verb)) continue;
			if (out.length() > 0) out.append(", ");
			out.append(verb);
		}
		return out.length() == 0 ? "answer, chat, follow, stay, wave, wait" : out.toString();
	}

	private static int planLimit() {
		return Math.max(1, Math.min(PLAN_STEPS_CEILING, CobbieConfig.getPlanStepLimit()));
	}
}
