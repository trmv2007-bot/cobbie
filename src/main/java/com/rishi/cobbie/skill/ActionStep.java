package com.rishi.cobbie.skill;

/**
 * One executable step inside a {@link Skill} — same shape as an ActionIntent so
 * the task executor can run steps without a special case per skill.
 *
 * Coordinate convention (kept in {@code data} because the record must stay a
 * plain value type):
 *  - default: {@code x,y,z} are ABSOLUTE world coordinates.
 *  - if {@code data} contains the token {@code "rel"} (case-insensitive,
 *    whitespace/semicolon separated, e.g. {@code "rel below"}), then
 *    {@code x,y,z} are OFFSETS from where Cobbie stood when the skill was
 *    recorded, and the executor must add his current position at run time.
 *  - a step with x=y=z=0 and no "rel" token means "no coordinates" (say, wait,
 *    follow-by-name, attack-nearest-of-type).
 *
 * {@code target} is the thing the action works on: player name, block id,
 * entity type id, or literal chat text — exactly as in ActionIntent.
 */
public record ActionStep(String action, String target, int x, int y, int z, String data) {

	/** True when x/y/z are offsets rather than absolute coordinates. */
	public boolean relative() {
		if (data == null || data.isBlank()) return false;
		for (String token : data.toLowerCase().split("[\\s;,+]+")) {
			if (token.equals("rel") || token.equals("relative")) return true;
		}
		return false;
	}

	/** One-line rendering used by prompt blocks and /cobbie skills. */
	public String summary() {
		StringBuilder sb = new StringBuilder(action == null || action.isBlank() ? "?" : action);
		if (target != null && !target.isBlank()) sb.append(' ').append(target);
		if (x != 0 || y != 0 || z != 0 || (target != null && !target.isBlank())) {
			sb.append(relative() ? " ~" : " @").append(x).append(' ').append(y).append(' ').append(z);
		}
		return sb.toString();
	}
}
