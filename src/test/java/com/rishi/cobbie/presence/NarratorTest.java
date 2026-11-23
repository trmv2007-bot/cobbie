package com.rishi.cobbie.presence;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * He acts, then speaks — and never reads a coordinate out loud.
 *
 * <p>The cadence is the part players feel, so it is a test rather than a thing you have to sit in
 * game to judge: one line per step, one line per cooldown window, and the newest line wins.
 */
class NarratorTest {

	private static ActionIntent intent(String verb, String target) {
		return new ActionIntent(verb, target, 10, 64, -3, "");
	}

	@Test
	void aStepStartSaysWhatHeIsDoingWithoutACoordinate() {
		String mine = Narrator.stepStart(intent(ActionVocabulary.MINE, "minecraft:stone"));
		assertEquals("going for that stone", mine);
		assertFalse(Narrator.mentionsCoordinates(mine), mine);

		assertEquals("on my way", Narrator.stepStart(intent(ActionVocabulary.WALK_TO, "")));
		assertEquals("putting a torch down", Narrator.stepStart(intent(ActionVocabulary.LIGHT, "")));
		assertEquals("careful, a zombie", Narrator.stepStart(intent(ActionVocabulary.ATTACK, "zombie")));
	}

	@Test
	void talkingAndWaitingAreNotNarrated() {
		assertNull(Narrator.stepStart(intent(ActionVocabulary.CHAT, "hello")));
		assertNull(Narrator.stepStart(intent(ActionVocabulary.ANSWER, "")));
		assertNull(Narrator.stepStart(null));
	}

	@Test
	void aStepResultIsOneLineAndAFailureKeepsOnlyTheReason() {
		assertEquals("got it", Narrator.stepResult(intent(ActionVocabulary.MINE, "stone"), true, "mined stone"));
		String failed = Narrator.stepResult(intent(ActionVocabulary.MINE, "stone"), false,
				"could not get close enough to 10 64 -3");
		assertTrue(failed.startsWith("couldn't get through that"), failed);
		assertFalse(Narrator.mentionsCoordinates(failed), failed);
		assertFalse(failed.contains("10"), "a position leaked into the sentence: " + failed);

		// Talking steps already said their own line; they must not say a second one.
		assertNull(Narrator.stepResult(intent(ActionVocabulary.FOLLOW, ""), true, "following"));
	}

	@Test
	void theNewestLineWinsAndTheGapHolds() {
		Narrator narrator = new Narrator();
		narrator.offer("on my way");
		assertEquals("on my way", narrator.next(1_000, 900));
		assertNull(narrator.next(1_100, 900), "the cooldown must hold the next line");

		narrator.offer("going for that stone");
		narrator.offer("got it");
		assertEquals("got it", narrator.next(2_000, 900), "a newer line replaces an older unsaid one");
		assertNull(narrator.next(2_100, 900));
		assertFalse(narrator.hasPending());
	}

	@Test
	void sayingOtherThingsHoldsTheQueueToo() {
		Narrator narrator = new Narrator();
		narrator.noteSpokenAt(5_000);
		narrator.offer("got it");
		assertNull(narrator.next(5_400, 900));
		assertEquals("got it", narrator.next(5_950, 900));
	}

	@Test
	void theLineBeforeTheWorkIsOneSentence() {
		assertEquals("On it.", Narrator.firstSentence("On it. I'll get that stone out of your way."));
		assertEquals("Fine by me", Narrator.firstSentence("Fine by me"));
		assertNull(Narrator.firstSentence("   "));
		assertNull(Narrator.firstSentence(null));
		// A decimal point is not a sentence end, and an id must survive intact.
		assertEquals("That slope is 3.5 blocks up", Narrator.firstSentence("That slope is 3.5 blocks up"));
	}

	@Test
	void scrubbingRemovesEveryShapeOfPosition() {
		assertEquals("heading over", Narrator.scrub("heading over to 10 64 -3"));
		assertEquals("nothing there", Narrator.scrub("nothing there at -120 5 900"));
		assertNull(Narrator.scrub("   "));
		// A line that was nothing but a position leaves no sentence at all.
		assertNull(Narrator.scrub("at 10 64 -3"));
		assertNull(Narrator.scrub(null));
	}

	@Test
	void aLongLineIsCutAtAWordBoundary() {
		String longLine = "a ".repeat(80).trim();
		String cut = Narrator.scrub(longLine);
		assertNotNull(cut);
		assertTrue(cut.length() <= 110, "length was " + cut.length());
		assertFalse(cut.endsWith(" "));
	}
}
