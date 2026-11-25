package com.rishi.cobbie.identity;

import java.util.Locale;

/**
 * What the owner is allowed to call his companion, and what a companion is called when nobody has said.
 *
 * <p>Pure on purpose: no Minecraft types, so the rules are checked in the unit tier where every awkward
 * name is cheap to try, rather than in game where each case costs a world load.
 *
 * <p>The rules exist because the name is rendered inside a fixed format —
 * {@code §e<name> §8· §7<status>} on the nameplate and {@code §e[<name>] §f} on his chat lines — and a
 * name that carries its own formatting breaks both. So colour codes are <b>refused</b> rather than
 * stripped: a player who typed {@code §cRed} gets told why, instead of silently owning a companion
 * called "Red" in a colour they did not pick.
 */
public final class CompanionName {

    /** What he is called until someone says otherwise. */
    public static final String DEFAULT = "Cobbie";

    /** Long enough for a real name, short enough that the nameplate still reads. */
    public static final int MAX_LENGTH = 24;

    private CompanionName() {}

    /** A name that passed, or the reason it did not. */
    public record Check(String name, String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    public static Check check(String raw) {
        if (raw == null) return new Check(null, "a name cannot be empty");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return new Check(null, "a name cannot be empty");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\u00a7') {
                return new Check(null, "no colour codes (§) in a name — it is already coloured");
            }
            if (Character.isISOControl(c)) {
                return new Check(null, "no control characters in a name");
            }
        }
        // The nameplate renders "§e<name> §8· §7<status>", so a name made only of separator punctuation
        // reads as part of the format rather than as a name.
        if (trimmed.chars().allMatch(c -> Character.isWhitespace(c) || "·•-–—_=.|".indexOf(c) >= 0)) {
            return new Check(null, "that name is only punctuation, which reads as part of the nameplate");
        }
        // Code points, not chars: an emoji or an accented letter must not be cut in half.
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length > MAX_LENGTH) {
            return new Check(null, "that name is " + length + " characters; the limit is " + MAX_LENGTH);
        }
        return new Check(trimmed, null);
    }

    /** A stored name, or the default when it is missing or would not pass today's rules. */
    public static String orDefault(String raw) {
        Check checked = check(raw);
        return checked.ok() ? checked.name() : DEFAULT;
    }

    /** Lower-cased, for matching a name a player typed against the one he carries. */
    public static String key(String raw) {
        return orDefault(raw).toLowerCase(Locale.ROOT);
    }
}
