package com.rishi.cobbie.brain;

import static org.junit.jupiter.api.Assertions.*;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.brain.ActionParser.ActionPlan;
import org.junit.jupiter.api.Test;

/**
 * Tests for ActionParser — LLM response parsing with never-silent fallback.
 */
class ActionParserTest {

	@Test
	void testValidJsonResponse() {
		String response = "{\"answer\":\"Hello player!\",\"actions\":[{\"action\":\"wave\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);

		assertEquals("Hello player!", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals("wave", plan.actions().get(0).action());
	}

	@Test
	void testPlainTextResponse() {
		String response = "Hey there, friend!";
		ActionPlan plan = ActionParser.parse(response);

		assertEquals("Hey there, friend!", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals("answer", plan.actions().get(0).action());
	}

	@Test
	void testNullResponseNeverSilent() {
		ActionPlan plan = ActionParser.parse(null);
		assertNotNull(plan);
		assertEquals("...", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals("answer", plan.actions().get(0).action());
	}

	@Test
	void testBlankResponseNeverSilent() {
		ActionPlan plan = ActionParser.parse("   ");
		assertNotNull(plan);
		assertEquals("...", plan.answer()); // blank input → never-silent fallback
		assertEquals(1, plan.actions().size());
		assertEquals("answer", plan.actions().get(0).action());
	}

	@Test
	void testMultipleActionsOnlyOneKept() {
		String response = "{\"answer\":\"Doing things\",\"actions\":[{\"action\":\"walk-to\",\"x\":10,\"y\":64,\"z\":10},{\"action\":\"mine\",\"x\":10,\"y\":63,\"z\":10}]}";
		ActionPlan plan = ActionParser.parse(response);

		// Parser keeps all actions in array; the brain executes them
		assertEquals(2, plan.actions().size());
		assertEquals("walk-to", plan.actions().get(0).action());
		assertEquals("mine", plan.actions().get(1).action());
	}

	@Test
	void testMalformedJsonFallsBackToRawText() {
		String response = "This is not json { broken";
		ActionPlan plan = ActionParser.parse(response);

		// extractJson finds first { to last }, which would be "{ broken" — invalid JSON
		// falls back to raw text as answer
		assertEquals("This is not json { broken", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals("answer", plan.actions().get(0).action());
	}

	@Test
	void testFollowAction() {
		String response = "{\"answer\":\"Coming!\",\"actions\":[{\"action\":\"follow\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		assertEquals("follow", plan.actions().get(0).action());
	}

	@Test
	void testWalkToAction() {
		String response = "{\"answer\":\"Going there\",\"actions\":[{\"action\":\"walk-to\",\"target\":\"\",\"x\":100,\"y\":64,\"z\":200,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		ActionIntent intent = plan.actions().get(0);
		assertEquals("walk-to", intent.action());
		assertEquals(100, intent.x());
		assertEquals(64, intent.y());
		assertEquals(200, intent.z());
	}

	@Test
	void testMineAction() {
		String response = "{\"answer\":\"Mining that\",\"actions\":[{\"action\":\"mine\",\"target\":\"\",\"x\":50,\"y\":60,\"z\":30,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		ActionIntent intent = plan.actions().get(0);
		assertEquals("mine", intent.action());
		assertEquals(50, intent.x());
		assertEquals(60, intent.y());
		assertEquals(30, intent.z());
	}

	@Test
	void testPlaceAction() {
		String response = "{\"answer\":\"Placing block\",\"actions\":[{\"action\":\"place\",\"target\":\"cobblestone\",\"x\":5,\"y\":64,\"z\":5,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		ActionIntent intent = plan.actions().get(0);
		assertEquals("place", intent.action());
		assertEquals("cobblestone", intent.target());
	}

	@Test
	void testAttackAction() {
		String response = "{\"answer\":\"Attacking!\",\"actions\":[{\"action\":\"attack\",\"target\":\"zombie\",\"x\":0,\"y\":0,\"z\":0,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		ActionIntent intent = plan.actions().get(0);
		assertEquals("attack", intent.action());
		assertEquals("zombie", intent.target());
	}

	@Test
	void testChatAction() {
		String response = "{\"answer\":\"Saying something\",\"actions\":[{\"action\":\"chat\",\"target\":\"hello everyone\",\"x\":0,\"y\":0,\"z\":0,\"data\":\"\"}]}";
		ActionPlan plan = ActionParser.parse(response);
		ActionIntent intent = plan.actions().get(0);
		assertEquals("chat", intent.action());
		assertEquals("hello everyone", intent.target());
	}
}
