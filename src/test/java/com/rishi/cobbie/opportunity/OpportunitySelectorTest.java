package com.rishi.cobbie.opportunity;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's answer, read against the list it was given — including every way an 8B model gets it wrong.
 *
 * <p>These are the cases that decide whether the inversion is real. A fallback to "the first candidate"
 * on a bad answer would make the shortlist the decision, and it would be invisible: it looks exactly like
 * a model that chose. So an empty answer, prose, a list, an object with no {@code choice}, and an id that
 * was never offered all end in WAIT with the reason on record — and the last test here is the one that
 * proves nothing is picked by position.
 */
class OpportunitySelectorTest {

	private static Opportunity mine(int x) {
		return new Opportunity(null, Opportunity.Kind.MINE, "three logs within reach", "wood", List.of(), 60,
				Opportunity.Risk.LOW, 1, List.of("you speak"), "gathering the oak log nearby",
				List.of(new ActionIntent(ActionVocabulary.MINE, "", x, 64, 0, "")));
	}

	private static Opportunity build(int x) {
		return new Opportunity(null, Opportunity.Kind.BUILD, "flat ground beside me", "a marker",
				List.of("cobblestone x3"), 45, Opportunity.Risk.LOW, 1, List.of("you speak"),
				"building a cairn of cobblestone here",
				List.of(new ActionIntent(ActionVocabulary.PLACE, "minecraft:cobblestone", x, 64, 2, "")));
	}

	private static Shortlist two() {
		return Shortlist.assemble(List.of(mine(3), build(6)));
	}

	@Test
	void anIdOnTheListIsAPickAndItIsTheModels() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "{\"choice\":\"op-2\"}");
		assertFalse(d.isWait());
		assertEquals("op-2", d.opportunityId());
		assertEquals(OpportunitySelector.Origin.MODEL, d.origin());
	}

	@Test
	void aModelRejectionIsAWaitCarryingItsReason() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(),
				"{\"choice\":\"wait\",\"why\":\"my owner is building and I should stay\"}");
		assertTrue(d.isWait());
		assertEquals("my owner is building and I should stay", d.why());
		assertEquals(OpportunitySelector.Origin.MODEL_REJECTED, d.origin());
	}

	@Test
	void waitWithoutAReasonStillCarriesOne() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "{\"choice\":\"wait\"}");
		assertTrue(d.isWait());
		assertFalse(d.why().isBlank());
	}

	@Test
	void anEmptyAnswerFailsClosedToWaitAndSaysSo() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "");
		assertTrue(d.isWait());
		assertEquals(OpportunitySelector.Origin.FALLBACK_REJECTED, d.origin());
		assertTrue(d.why().contains("nothing"), d.why());
	}

	@Test
	void nullTextFailsClosed() {
		assertTrue(OpportunitySelector.parse(two(), null).isWait());
	}

	@Test
	void proseBeforeTheObjectIsTolerated() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(),
				"Sure — I'll get the stone.\n```json\n{\"choice\":\"op-1\"}\n```");
		assertEquals("op-1", d.opportunityId());
		assertEquals(OpportunitySelector.Origin.MODEL, d.origin());
	}

	@Test
	void proseWithNoObjectFailsClosedInsteadOfReadingAnIdOutOfIt() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "I think op-2 is the best one.");
		assertTrue(d.isWait(), "a sentence naming an id is not an answer in the agreed shape");
		assertEquals(OpportunitySelector.Origin.FALLBACK_REJECTED, d.origin());
		assertTrue(d.why().contains("not a JSON object"), d.why());
	}

	@Test
	void anAnswerWithNoChoiceFailsClosed() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "{\"answer\":\"op-1\"}");
		assertTrue(d.isWait());
		assertTrue(d.why().contains("no choice"), d.why());
	}

	@Test
	void anIdThatWasNotOfferedIsARejectionNotAPick() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "{\"choice\":\"op-9\"}");
		assertTrue(d.isWait());
		assertEquals(OpportunitySelector.Origin.FALLBACK_REJECTED, d.origin());
		assertTrue(d.why().contains("op-9"), d.why());
	}

	@Test
	void anArrayIsNotAnAnswer() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(two(), "[{\"choice\":\"op-1\"}]");
		assertTrue(d.isWait());
	}

	@Test
	void thePositionNeverDecides() {
		// The same id, offered first in one list and second in the other, resolves to the same work both
		// times. This is the unit-level half of the in-game proof that choosing B runs B.
		Shortlist forward = two();
		Shortlist reversed = Shortlist.assemble(List.of(build(6), mine(3)));
		OpportunitySelector.Decision fromForward = OpportunitySelector.parse(forward, "{\"choice\":\"op-2\"}");
		OpportunitySelector.Decision fromReversed = OpportunitySelector.parse(reversed, "{\"choice\":\"op-2\"}");
		assertEquals("build", forward.byId(fromForward.opportunityId()).kind().label());
		assertEquals("mine", reversed.byId(fromReversed.opportunityId()).kind().label());
	}

	@Test
	void anEmptyShortlistCannotBeAnsweredAtAll() {
		OpportunitySelector.Decision d = OpportunitySelector.parse(Shortlist.assemble(List.of()),
				"{\"choice\":\"op-1\"}");
		assertTrue(d.isWait());
		assertTrue(d.why().contains("nothing was offered"), d.why());
	}
}
