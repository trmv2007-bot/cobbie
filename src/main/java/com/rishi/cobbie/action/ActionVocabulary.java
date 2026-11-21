package com.rishi.cobbie.action;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single source of truth for verbs Cobbie is allowed to execute.
 *
 * <p>Everything that accepts an action name — the LLM response parser, saved skills, the task
 * runner and the entity's execution switch — validates against this class, and the entity checks
 * it AGAIN at execution time together with {@link com.rishi.cobbie.config.CobbieConfig#permits(String)}.
 * A model-generated verb can therefore never reach the world without passing two gates.
 */
public final class ActionVocabulary {

	/** Hard ceiling on how many actions one reply may queue. */
	public static final int MAX_ACTIONS = 8;

	public static final String ANSWER = "answer";
	public static final String CHAT = "chat";
	public static final String FOLLOW = "follow";
	public static final String STAY = "stay";
	public static final String WAVE = "wave";
	public static final String WALK_TO = "walk-to";
	public static final String MINE = "mine";
	public static final String PLACE = "place";
	public static final String ATTACK = "attack";
	public static final String COLLECT = "collect";
	public static final String EXPLORE = "explore";
	public static final String LIGHT = "light";
	public static final String GIVE = "give";
	public static final String WAIT = "wait";

	private static final Set<String> ALLOWED = new LinkedHashSet<>(List.of(
			ANSWER, CHAT, FOLLOW, STAY, WAVE, WALK_TO, MINE, PLACE, ATTACK, COLLECT, EXPLORE, LIGHT, GIVE, WAIT));

	/** Actions that are meaningless without an explicit target position. */
	private static final Set<String> NEEDS_POSITION = Set.of(WALK_TO, MINE, PLACE, LIGHT);

	/** Actions that change the world and therefore need verification afterwards. */
	private static final Set<String> WORLD_CHANGING = Set.of(MINE, PLACE, ATTACK, COLLECT, LIGHT, GIVE);

	private ActionVocabulary() {}

	public static Set<String> allowed() {
		return ALLOWED;
	}

	public static boolean isAllowed(String action) {
		return action != null && ALLOWED.contains(action.toLowerCase(Locale.ROOT));
	}

	public static boolean needsPosition(String action) {
		return action != null && NEEDS_POSITION.contains(action);
	}

	public static boolean isWorldChanging(String action) {
		return action != null && WORLD_CHANGING.contains(action);
	}

	/** Normalise what the model wrote into a known verb, or return null when it is nonsense. */
	public static String normalise(String raw) {
		if (raw == null) return null;
		String v = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
		v = switch (v) {
			case "walk", "move", "move-to", "goto", "go-to", "path-to" -> WALK_TO;
			case "break", "dig", "harvest", "mine-block" -> MINE;
			case "build", "put", "set-block", "place-block" -> PLACE;
			case "hit", "fight", "kill", "attack-mob" -> ATTACK;
			case "pick-up", "pickup", "grab", "gather" -> COLLECT;
			case "torch", "light-up", "illuminate", "place-torch" -> LIGHT;
			case "hand", "hand-over", "handover", "deliver", "give-item" -> GIVE;
			case "say", "speak", "tell" -> CHAT;
			case "wander", "roam", "look-around" -> EXPLORE;
			case "come", "come-here" -> FOLLOW;
			case "halt", "wait-here", "idle" -> WAIT;
			default -> v;
		};
		return isAllowed(v) ? v : null;
	}

	/** One-line verb list for the prompt. */
	public static String describe() {
		StringBuilder sb = new StringBuilder();
		for (String verb : ALLOWED) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(verb);
			if (needsPosition(verb)) sb.append(" (needs x,y,z)");
		}
		return sb.toString();
	}
}
