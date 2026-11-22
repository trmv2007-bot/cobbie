package com.rishi.cobbie.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The deterministic command path. Every case here is a phrase that, in a real session, the model
 * answered with words and no action.
 */
class ImperativesTest {

	@Test
	void waveIsRecognisedInTheFormsPlayersUse() {
		assertEquals("wave", Imperatives.match("wave").verb());
		assertEquals("wave", Imperatives.match("wave bruh").verb());
		assertEquals("wave", Imperatives.match("cobbie wave hello!").verb());
	}

	@Test
	void comeHereAndFollowMeBothMeanFollow() {
		assertEquals("follow", Imperatives.match("come here").verb());
		assertEquals("follow", Imperatives.match("come to me").verb());
		assertEquals("follow", Imperatives.match("follow me").verb());
		assertEquals("follow", Imperatives.match("cobbie come").verb());
	}

	@Test
	void stayAndStopBothHoldPosition() {
		assertEquals("stay", Imperatives.match("stay").verb());
		assertEquals("stay", Imperatives.match("stop").verb());
		assertEquals("stay", Imperatives.match("wait there").verb());
	}

	@Test
	void dropKeepsTheItemAndDropsTheFiller() {
		Imperatives.Hit hit = Imperatives.match("drop your netherite pickaxe");
		assertNotNull(hit);
		assertEquals("drop", hit.verb());
		assertEquals("netherite_pickaxe", hit.target());

		assertEquals("diamond", Imperatives.match("drop all your diamonds bro").target());
		assertEquals("", Imperatives.match("drop it").target());
	}

	@Test
	void inventoryQuestionsAreAnsweredFromTheBagItself() {
		assertEquals("bag", Imperatives.match("what do you have").verb());
		assertEquals("bag", Imperatives.match("inventory").verb());
	}

	@Test
	void lookupIsRecognisedWithItsQuery() {
		Imperatives.Hit hit = Imperatives.match("look up how to build a starter house");
		assertNotNull(hit);
		assertEquals("lookup", hit.verb());
		assertEquals("how to build a starter house", hit.target());
	}

	@Test
	void normalConversationIsNotHijacked() {
		assertNull(Imperatives.match("how are you doing today, friend"));
		assertNull(Imperatives.match("that was a really fun adventure we had together yesterday"));
		assertNull(Imperatives.match(""));
		assertNull(Imperatives.match(null));
	}
}
