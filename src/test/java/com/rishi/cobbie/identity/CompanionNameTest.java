package com.rishi.cobbie.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a name has to pass, checked where every awkward case is cheap: a name is rendered inside a
 * fixed format on the nameplate and his chat lines, so a name that carries its own formatting breaks both.
 */
class CompanionNameTest {

    @Test
    void aPlainNameIsKept() {
        CompanionName.Check check = CompanionName.check("Sir Fluff");
        assertTrue(check.ok());
        assertEquals("Sir Fluff", check.name());
    }

    @Test
    void surroundingSpaceIsTrimmedRatherThanRefused() {
        CompanionName.Check check = CompanionName.check("   Mallow \t");
        assertTrue(check.ok());
        assertEquals("Mallow", check.name());
    }

    @Test
    void anEmptyNameIsRefused() {
        assertFalse(CompanionName.check("").ok());
        assertFalse(CompanionName.check("   ").ok());
        assertFalse(CompanionName.check(null).ok());
    }

    @Test
    void aNameOf25CharactersIsRefusedAnd24IsNot() {
        String at24 = "A".repeat(CompanionName.MAX_LENGTH);
        String at25 = "A".repeat(CompanionName.MAX_LENGTH + 1);
        assertTrue(CompanionName.check(at24).ok(), "24 characters must be allowed");
        CompanionName.Check tooLong = CompanionName.check(at25);
        assertFalse(tooLong.ok());
        assertTrue(tooLong.refusal().contains("25"), "the refusal should say how long it was: "
                + tooLong.refusal());
    }

    @Test
    void colourCodesAreRefusedRatherThanRenderedOrStripped() {
        CompanionName.Check check = CompanionName.check("Sir §cFluff");
        assertFalse(check.ok(), "a colour code inside a name would recolour the nameplate and the chat");
        assertTrue(check.refusal().contains("§"), "the refusal should name the character: "
                + check.refusal());
        // And a name that is nothing but formatting is not silently turned into a blank name either.
        assertFalse(CompanionName.check("§c").ok());
    }

    @Test
    void controlCharactersAreRefused() {
        assertFalse(CompanionName.check("Sir\u0007Fluff").ok());
        assertFalse(CompanionName.check("Sir\u0000Fluff").ok());
        assertFalse(CompanionName.check("Sir\nFluff").ok());
    }

    @Test
    void squareBracketsAreRefusedBecauseTheyWrapTheNameInChat() {
        CompanionName.Check check = CompanionName.check("Sir [Fluff]");
        assertFalse(check.ok());
        assertTrue(check.refusal().contains("brackets"), check.refusal());
        assertFalse(CompanionName.check("[Fluff").ok());
        assertFalse(CompanionName.check("Fluff]").ok());
    }

    @Test
    void aNameThatIsOnlySeparatorPunctuationIsRefused() {
        assertFalse(CompanionName.check("·").ok());
        assertFalse(CompanionName.check(" · — ").ok());
        assertTrue(CompanionName.check("· Mallow").ok(), "a real name may still start with one");
    }

    @Test
    void tabsAndNewlinesInsideANameAreRefused() {
        assertFalse(CompanionName.check("Sir\tFluff").ok());
        assertFalse(CompanionName.check("Sir\nFluff").ok());
    }

    @Test
    void theDefaultAppliesToMissingOrInvalidNames() {
        assertEquals(CompanionName.DEFAULT, CompanionName.orDefault(null));
        assertEquals(CompanionName.DEFAULT, CompanionName.orDefault(""));
        assertEquals(CompanionName.DEFAULT, CompanionName.orDefault("§cbad"));
        assertEquals("Mallow", CompanionName.orDefault(" Mallow "));
    }

    @Test
    void aLongNameInWideCharactersIsMeasuredInCodePoints() {
        // 24 emoji is 48 chars but 24 code points: cutting it at 24 chars would split one in half.
        String emoji = "\uD83D\uDC3E".repeat(CompanionName.MAX_LENGTH / 2);
        assertTrue(CompanionName.check(emoji).ok(), "12 emoji must fit");
        assertFalse(CompanionName.check(emoji.repeat(3)).ok());
    }
}
