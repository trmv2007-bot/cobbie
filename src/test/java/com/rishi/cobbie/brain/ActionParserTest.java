package com.rishi.cobbie.brain;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.brain.ActionParser.ActionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser is the hostile-input boundary, so this is the most important test file in the mod:
 * a 4B local model really does answer with prose, fences, quoted numbers and invented verbs.
 */
class ActionParserTest {

	@Test
	void parsesCleanMultiStepPlanInOrder() {
		String json = "{\"answer\":\"On it.\",\"actions\":["
				+ "{\"action\":\"walk-to\",\"target\":\"\",\"x\":10,\"y\":64,\"z\":-3},"
				+ "{\"action\":\"mine\",\"target\":\"\",\"x\":10,\"y\":63,\"z\":-3}]}";
		ActionPlan plan = ActionParser.parse(json);

		assertEquals("On it.", plan.answer());
		assertEquals(2, plan.actions().size());
		assertEquals(ActionVocabulary.WALK_TO, plan.actions().get(0).action());
		assertEquals(ActionVocabulary.MINE, plan.actions().get(1).action());
		assertEquals(63, plan.actions().get(1).y());
		assertTrue(plan.problems().isEmpty(), "clean reply must not report problems");
		assertTrue(plan.hasWorldAction());
	}

	@Test
	void pullsJsonOutOfSurroundingProse() {
		String reply = "Sure thing!\n```json\n{\"answer\":\"Doing it\",\"actions\":"
				+ "[{\"action\":\"wave\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}]}\n```\nHope that helps.";
		ActionPlan plan = ActionParser.parse(reply);

		assertEquals("Doing it", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals(ActionVocabulary.WAVE, plan.actions().get(0).action());
	}

	@Test
	void bracesInsideStringsDoNotTruncateThePayload() {
		String reply = "{\"answer\":\"I like {curly} braces\",\"actions\":[{\"action\":\"follow\","
				+ "\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}]}";
		ActionPlan plan = ActionParser.parse(reply);

		assertEquals("I like {curly} braces", plan.answer());
		assertEquals(1, plan.actions().size());
		assertTrue(plan.problems().isEmpty(), plan.problems().toString());
	}

	@Test
	void missingCoordinatesAreRejectedNotDefaulted() {
		// 0.4.0 turned a coordinate-less "mine" into mining 0,0,0 — bedrock at world origin.
		ActionPlan plan = ActionParser.parse("{\"answer\":\"hm\",\"actions\":[{\"action\":\"mine\"}]}");

		assertTrue(plan.problems().stream().anyMatch(p -> p.contains("missing coordinate")),
				"expected a missing-coordinate problem, got " + plan.problems());
		assertFalse(plan.actions().stream().anyMatch(a -> a.action().equals("mine")),
				"a mine with no coordinates must never reach the world");
	}

	@Test
	void nonNumericCoordinateIsRejected() {
		ActionPlan plan = ActionParser.parse("{\"answer\":\"x\",\"actions\":[{\"action\":\"walk-to\","
				+ "\"target\":\"\",\"x\":\"ten\",\"y\":64,\"z\":5}]}");
		assertTrue(plan.problems().stream().anyMatch(p -> p.contains("non-numeric")), plan.problems().toString());
	}

	@Test
	void quotedNumbersAreAccepted() {
		ActionPlan plan = ActionParser.parse("{\"answer\":\"ok\",\"actions\":[{\"action\":\"mine\","
				+ "\"target\":\"\",\"x\":\"12\",\"y\":\"64\",\"z\":\"-7\"}]}");
		assertEquals(1, plan.actions().size());
		assertEquals(12, plan.actions().get(0).x());
		assertEquals(-7, plan.actions().get(0).z());
	}

	@Test
	void coordinateBelowTheWorldIsRejected() {
		ActionPlan plan = ActionParser.parse("{\"answer\":\"ok\",\"actions\":[{\"action\":\"place\","
				+ "\"target\":\"stone\",\"x\":1,\"y\":-99999,\"z\":2}]}");
		assertFalse(plan.actions().stream().anyMatch(a -> a.action().equals("place")));
		assertTrue(plan.problems().stream().anyMatch(p -> p.contains("outside the world")), plan.problems().toString());
	}

	@Test
	void unknownVerbIsReportedNotSilentlyIgnored() {
		ActionPlan plan = ActionParser.parse("{\"answer\":\"look\",\"actions\":"
				+ "[{\"action\":\"teleport\",\"target\":\"x\",\"x\":1,\"y\":1,\"z\":1}]}");

		assertTrue(plan.problems().stream().anyMatch(p -> p.contains("unknown verb")), plan.problems().toString());
		assertTrue(plan.actions().size() == 1 && plan.actions().get(0).action().equals(ActionVocabulary.ANSWER),
				"fallback must be a harmless answer, not the invented verb");
	}

	@Test
	void verbAliasesAreNormalised() {
		ActionPlan plan = ActionParser.parse("{\"answer\":\"ok\",\"actions\":["
				+ "{\"action\":\"dig\",\"target\":\"\",\"x\":1,\"y\":2,\"z\":3},"
				+ "{\"action\":\"pick up\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}]}");
		List<String> verbs = plan.actions().stream().map(ActionIntent::action).toList();
		assertTrue(verbs.contains(ActionVocabulary.MINE), verbs.toString());
		assertTrue(verbs.contains(ActionVocabulary.COLLECT), verbs.toString());
	}

	@Test
	void actionListIsCapped() {
		StringBuilder sb = new StringBuilder("{\"answer\":\"busy\",\"actions\":[");
		for (int i = 0; i < ActionVocabulary.MAX_ACTIONS + 5; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"action\":\"wave\",\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}");
		}
		sb.append("]}");
		ActionPlan plan = ActionParser.parse(sb.toString());

		assertEquals(ActionVocabulary.MAX_ACTIONS, plan.actions().size());
		assertTrue(plan.problems().stream().anyMatch(p -> p.contains("capped")), plan.problems().toString());
	}

	@Test
	void placeAndAttackNeedTargets() {
		ActionPlan place = ActionParser.parse("{\"answer\":\"a\",\"actions\":[{\"action\":\"place\","
				+ "\"target\":\"\",\"x\":1,\"y\":2,\"z\":3}]}");
		assertTrue(place.problems().stream().anyMatch(p -> p.contains("non-empty")), place.problems().toString());

		ActionPlan attack = ActionParser.parse("{\"answer\":\"a\",\"actions\":[{\"action\":\"attack\"}]}");
		assertTrue(attack.problems().stream().anyMatch(p -> p.contains("non-empty")), attack.problems().toString());
	}

	@Test
	void plainChatterStaysChatterAndNeverThrows() {
		ActionPlan plan = ActionParser.parse("I am just thinking out loud, no JSON here.");
		assertEquals("I am just thinking out loud, no JSON here.", plan.answer());
		assertEquals(1, plan.actions().size());
		assertEquals(ActionVocabulary.ANSWER, plan.actions().get(0).action());
		assertTrue(plan.problems().isEmpty());
	}

	@Test
	void nullBlankAndTruncatedJsonAllDegradeSafely() {
		assertEquals(ActionVocabulary.ANSWER, ActionParser.parse(null).actions().get(0).action());
		assertEquals("...", ActionParser.parse("   ").answer());
		ActionPlan cut = ActionParser.parse("{\"answer\":\"half a sentence\",\"actions\":[{\"action\":\"wave\"");
		assertEquals(1, cut.actions().size());
		assertEquals(ActionVocabulary.ANSWER, cut.actions().get(0).action());
	}

	@Test
	void answerIsNeverBlank() {
		ActionPlan plan = ActionParser.parse("{\"actions\":[{\"action\":\"wave\",\"target\":\"\","
				+ "\"x\":0,\"y\":0,\"z\":0}]}");
		assertFalse(plan.answer().isBlank());
	}

	@Test
	void overlongAnswerIsTruncatedRatherThanSentWhole() {
		String huge = "x".repeat(4000);
		ActionPlan plan = ActionParser.parse("{\"answer\":\"" + huge + "\",\"actions\":[{\"action\":\"answer\","
				+ "\"target\":\"\",\"x\":0,\"y\":0,\"z\":0}]}");
		assertTrue(plan.answer().length() <= 1000, "answer should be capped, was " + plan.answer().length());
	}
}
