package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suppression, numbering and the cap — the three things that stop a shortlist from wasting the model's
 * budget or, worse, from deciding by shape rather than by content.
 *
 * <p>The brief's number is "5 to 10 validated opportunities", and the honest reading of it is a <b>cap</b>,
 * never a quota: a shortlist is never padded to reach five, because a filler candidate is by definition
 * one that cannot start, and offering it would be the assessor choosing. The test that matters here is
 * therefore the one asserting three candidates stay three.
 */
class ShortlistTest {

	private static Opportunity mine(String request, int x) {
		return new Opportunity(null, Opportunity.Kind.MINE, "three logs within reach", "wood", List.of(), 60,
				Opportunity.Risk.LOW, 1, List.of("you speak"), request,
				List.of(new ActionIntent(ActionVocabulary.MINE, "", x, 64, 0, "")));
	}

	private static Opportunity build(int x) {
		return new Opportunity(null, Opportunity.Kind.BUILD, "flat ground beside me", "a marker", List.of("cobblestone x3"),
				45, Opportunity.Risk.LOW, 1, List.of("you speak"), "building a cairn of cobblestone here",
				List.of(new ActionIntent(ActionVocabulary.PLACE, "minecraft:cobblestone", x, 64, 2, "")));
	}

	@Test
	void idsAreDenseAndAssignedInOrder() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3),
				build(6), mine("clearing the rubble around me", 9)));
		assertEquals(List.of("op-1", "op-2", "op-3"), list.ids());
		assertEquals(Opportunity.Kind.BUILD, list.byId("op-2").kind());
	}

	@Test
	void theSameThingOfferedTwiceIsOfferedOnce() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3),
				mine("gathering the oak log nearby", 3), mine("gathering the oak log nearby", 3)));
		assertEquals(1, list.size(), "three copies of one pile is one opportunity, not three");
	}

	@Test
	void threeCandidatesStayThree() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3), build(6),
				mine("clearing the rubble around me", 9)));
		assertEquals(3, list.size(), "a shortlist is never padded up to a minimum");
	}

	@Test
	void nothingWithoutStepsSurvives() {
		List<Opportunity> raw = new ArrayList<>();
		raw.add(mine("gathering the oak log nearby", 3));
		raw.add(null);
		assertEquals(1, Shortlist.assemble(raw).size());
	}

	@Test
	void theCapIsTenAndTheOrderIsTheAssessors() {
		List<Opportunity> many = new ArrayList<>();
		for (int i = 0; i < 14; i++) many.add(mine("gathering the oak log nearby", i * 10));
		Shortlist list = Shortlist.assemble(many);
		assertEquals(Shortlist.MAX, list.size());
		assertEquals(10, list.size());
		assertEquals("op-1", list.items().get(0).id());
		assertEquals("op-10", list.items().get(9).id());
	}

	@Test
	void aCandidateOfTheSameKindOnDifferentSquaresIsKept() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3),
				mine("gathering the oak log nearby", 40)));
		assertEquals(2, list.size(), "two piles are two choices, not a duplicate");
	}

	@Test
	void byIdIsExactAndCaseInsensitiveAndNothingElseIs() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3), build(6)));
		assertNotNull(list.byId("op-1"));
		assertNotNull(list.byId(" OP-2 "));
		assertNull(list.byId("op-3"));
		assertNull(list.byId("mine"));
		assertNull(list.byId(null));
	}

	@Test
	void theBlockTheModelReadsListsEveryIdAndSaysTheOrderMeansNothing() {
		Shortlist list = Shortlist.assemble(List.of(mine("gathering the oak log nearby", 3), build(6)));
		String block = list.promptBlock();
		assertTrue(block.contains("op-1 ·"), block);
		assertTrue(block.contains("op-2 ·"), block);
		assertTrue(block.contains("every one of them can be started this second"), block);
		assertTrue(block.toLowerCase().contains("order means nothing"), block);
		assertTrue(block.contains("{\"choice\":\"op-N\"}"), block);
		assertTrue(block.contains("\"wait\""), block);
		assertFalse(block.endsWith("\n"), "a trailing newline is a token nobody reads");
	}

	@Test
	void anEmptyAssembleIsAnEmptyShortlistRatherThanAnError() {
		assertTrue(Shortlist.assemble(List.of()).isEmpty());
		assertTrue(Shortlist.assemble(null).isEmpty());
		assertEquals("", Shortlist.assemble(List.of()).describe());
	}
}
