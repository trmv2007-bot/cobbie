package com.rishi.cobbie.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vocabulary is what the model is told it can do. If a verb exists but is never described, the
 * model cannot use it; if it is described but cannot be normalised, the model's phrasing is thrown
 * away. This file keeps those three lists — allowed, described, normalised — in lockstep.
 */
class ActionVocabularyTest {

	@Test
	void everyAllowedVerbIsDescribedToTheModel() {
		String described = ActionVocabulary.describe();

		for (String verb : ActionVocabulary.allowed()) {
			assertTrue(described.contains(verb), "verb \"" + verb + "\" is allowed but never described: " + described);
		}
	}

	@Test
	void everyAllowedVerbSurvivesNormalisation() {
		for (String verb : ActionVocabulary.allowed()) {
			assertEquals(verb, ActionVocabulary.normalise(verb), "a verb the model is told about must normalise to itself");
			assertEquals(verb, ActionVocabulary.normalise(verb.toUpperCase()), "case must not matter");
			assertEquals(verb, ActionVocabulary.normalise(verb.replace('-', '_')), "underscores must be tolerated");
		}
	}

	@Test
	void positionVerbsAreMarkedForThePrompt() {
		assertTrue(ActionVocabulary.describe().contains(ActionVocabulary.WALK_TO + " (needs x,y,z)"));
		assertTrue(ActionVocabulary.needsPosition(ActionVocabulary.MINE));
		assertTrue(ActionVocabulary.needsPosition(ActionVocabulary.PLACE));
		assertFalse(ActionVocabulary.needsPosition(ActionVocabulary.CHAT));
	}

	@Test
	void inventedVerbsAreRejected() {
		assertNull(ActionVocabulary.normalise("teleport"));
		assertNull(ActionVocabulary.normalise(""));
		assertNull(ActionVocabulary.normalise(null));
		assertFalse(ActionVocabulary.isAllowed("summon"));
	}
}
