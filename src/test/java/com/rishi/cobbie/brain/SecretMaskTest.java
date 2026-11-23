package com.rishi.cobbie.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The masked-key rules. These exist because "never in a screenshot, never in a log line" is only
 * true if the code that draws the field cannot draw the key.
 */
class SecretMaskTest {

	private static final String KEY = "gsk_live_51H8xQ2eZvKYlo2Cabcdefghij";

	@Test
	void onlyTheTailOfARealKeyIsShown() {
		String shown = SecretMask.display(KEY);

		assertTrue(shown.endsWith(KEY.substring(KEY.length() - 4)), shown);
		assertTrue(SecretMask.hides(KEY, shown), "the body of the key leaked: " + shown);
		assertFalse(shown.contains("gsk_live_51"), shown);
	}

	@Test
	void aShortSecretIsHiddenCompletely() {
		// Short enough that showing "the last four" would be most of it.
		String shown = SecretMask.display("abc12");
		assertEquals("\u2022\u2022\u2022\u2022", shown);
		assertTrue(SecretMask.hides("abc12", shown));
	}

	@Test
	void aLongSecretIsNotAllowedToBecomeAWallOfBullets() {
		String huge = "k".repeat(400);
		assertTrue(SecretMask.display(huge).length() <= 24 + 4, "a 400-char key must not widen the field");
	}

	@Test
	void blankMeansNotSetRatherThanAnEmptyLabel() {
		assertEquals("", SecretMask.display(""), "the field keeps the hint visible when empty");
		assertEquals("", SecretMask.display(null));
		assertEquals(SecretMask.NOT_SET, SecretMask.label(""));
		assertEquals(SecretMask.NOT_SET, SecretMask.label(null));
		assertEquals(SecretMask.display(KEY), SecretMask.label(KEY));
	}

	@Test
	void nothingIsHiddenForAnEmptyKey() {
		assertTrue(SecretMask.hides("", "anything"));
		assertTrue(SecretMask.hides(null, null));
	}
}
