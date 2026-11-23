package com.rishi.cobbie.brain;

/**
 * How a secret is displayed. One rule: the full value never reaches a pixel, a chat line or a log
 * line.
 *
 * <p>Kept separate from the screens so the rule is unit-tested rather than eyeballed: the API-key
 * field renders through {@link #display}, which keeps only the last four characters, and the
 * status labels use {@link #label}, which says "(not set)" instead of an empty string a player
 * could misread as a broken key.
 */
public final class SecretMask {

	public static final String NOT_SET = "(not set)";

	private static final char BULLET = '\u2022';
	private static final int MAX_BULLETS = 24;

	private SecretMask() {}

	/**
	 * What the field draws for {@code raw}. Blank stays blank so the hint text shows; anything
	 * short enough to be a typo is fully hidden rather than mostly shown.
	 */
	public static String display(String raw) {
		if (raw == null || raw.isBlank()) return "";
		String key = raw.trim();
		if (key.length() <= 8) return String.valueOf(BULLET).repeat(4);
		int bullets = Math.min(MAX_BULLETS, Math.max(4, key.length() - 4));
		return String.valueOf(BULLET).repeat(bullets) + key.substring(key.length() - 4);
	}

	/** Label form: never an empty string, never the key. */
	public static String label(String raw) {
		return raw == null || raw.isBlank() ? NOT_SET : display(raw);
	}

	/** True when the text carries no characters of the secret beyond its masked tail. */
	public static boolean hides(String raw, String rendered) {
		if (raw == null || raw.isBlank() || rendered == null) return true;
		String key = raw.trim();
		if (key.length() <= 4) return !rendered.contains(key);
		return !rendered.contains(key.substring(0, key.length() - 4));
	}
}
