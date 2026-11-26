package com.rishi.cobbie.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tool he raises, where the answer is arithmetic over what the game said.
 *
 * <p>The defect this replaces was ordered by tier alone, so the finest pickaxe in the bag wore itself out on
 * cobblestone. Every case here is one a player would notice over a session, and the ones that matter most are
 * the two nobody can see happen: a tool that was passed over because it cannot finish the job, and a job that
 * has nothing but a nearly-spent tool to do it with.
 */
class ToolChoiceTest {

    private static ToolChoice.Candidate tool(int slot, String id, int maxDamage, int usesLeft) {
        return new ToolChoice.Candidate(slot, id, true, maxDamage, usesLeft);
    }

    private static final ToolChoice.Candidate WOODEN = tool(4, "minecraft:wooden_pickaxe", 59, 59);
    private static final ToolChoice.Candidate STONE = tool(3, "minecraft:stone_pickaxe", 131, 131);
    private static final ToolChoice.Candidate IRON = tool(2, "minecraft:iron_pickaxe", 250, 250);
    private static final ToolChoice.Candidate DIAMOND = tool(1, "minecraft:diamond_pickaxe", 1561, 1561);
    /** One use left: vanilla destroys a stack whose damage reaches its maximum, so this finishes nothing. */
    private static final ToolChoice.Candidate DIAMOND_SPENT = tool(1, "minecraft:diamond_pickaxe", 1561, 1);

    @Test
    void theCheapestToolThatWillDoTheJobIsTheOneRaised() {
        ToolChoice.Pick pick = ToolChoice.choose(List.of(DIAMOND, IRON, STONE), 1);

        assertEquals(STONE.slot(), pick.slot(), "stone, iron and diamond pickaxes on one block: wear the stone one");
        assertEquals(ToolChoice.Note.NONE, pick.note());
        assertFalse(pick.slot() == DIAMOND.slot(), "the diamond one is never the cheap answer");
    }

    @Test
    void theJunkToolIsSpentFirst() {
        // Gold and wood are cheaper than stone by the game's own numbers, and both are worth spending first.
        ToolChoice.Candidate gold = tool(5, "minecraft:golden_pickaxe", 32, 32);
        assertEquals(gold.slot(), ToolChoice.choose(List.of(STONE, gold), 1).slot());
        assertEquals(WOODEN.slot(), ToolChoice.choose(List.of(STONE, WOODEN), 1).slot());
    }

    @Test
    void aToolThatCannotFinishTheJobIsPassedOverAndNamed() {
        ToolChoice.Pick pick = ToolChoice.choose(List.of(DIAMOND_SPENT, IRON), 1);

        assertEquals(IRON.slot(), pick.slot(), "a diamond pickaxe on its last use is not the tool for any job");
        assertEquals(ToolChoice.Note.STEPPED_DOWN, pick.note());
        assertEquals(DIAMOND_SPENT.slot(), pick.passedOver(), "the sentence has to be able to name what it skipped");
    }

    @Test
    void aCheapToolThatCannotFinishTheJobGivesWayQuietly() {
        ToolChoice.Candidate stoneLow = tool(3, "minecraft:stone_pickaxe", 131, 4);
        // Four uses left is three blocks of work before the fourth breaks it: the cheap pickaxe for three,
        // the iron one for four.
        ToolChoice.Pick three = ToolChoice.choose(List.of(stoneLow, IRON), 3);
        assertEquals(stoneLow.slot(), three.slot());
        assertEquals(ToolChoice.Note.NONE, three.note(), "a tool that lasts the job is no news to the owner");

        // Stepping up to the finer tool because the cheap one is short is not a warning: nothing of the
        // owner's is being risked. It is the other direction that has to be said out loud.
        ToolChoice.Pick four = ToolChoice.choose(List.of(stoneLow, IRON), 4);
        assertEquals(IRON.slot(), four.slot());
        assertEquals(ToolChoice.Note.NONE, four.note());
    }

    @Test
    void theLastUseIsTheOneThatBreaksIt() {
        ToolChoice.Candidate ironExact = tool(2, "minecraft:iron_pickaxe", 250, 3);
        // Three uses left breaks on the third block, so the job of three is already one too many — and the
        // finer tool is the one he is left with, which is not a thing worth remarking on.
        assertEquals(ironExact.slot(), ToolChoice.choose(List.of(ironExact, DIAMOND), 2).slot());
        ToolChoice.Pick three = ToolChoice.choose(List.of(ironExact, DIAMOND), 3);
        assertEquals(DIAMOND.slot(), three.slot());
        assertEquals(ToolChoice.Note.NONE, three.note());
    }

    @Test
    void withNothingThatLastsItUsesTheLongestAndSaysSo() {
        ToolChoice.Candidate worn = tool(2, "minecraft:iron_pickaxe", 250, 2);
        ToolChoice.Candidate wornMore = tool(3, "minecraft:stone_pickaxe", 131, 6);
        ToolChoice.Pick pick = ToolChoice.choose(List.of(worn, wornMore), 40);

        assertEquals(wornMore.slot(), pick.slot(), "the one with the most uses left is the best of a bad set");
        assertEquals(ToolChoice.Note.LAST_RESORT, pick.note(), "wearing a tool out without a word is the defect");
    }

    @Test
    void aToolTheGameSaysWillNotHarvestIsNotATool() {
        ToolChoice.Candidate shovel = new ToolChoice.Candidate(0, "minecraft:diamond_shovel", false, 1561, 1561);
        ToolChoice.Pick pick = ToolChoice.choose(List.of(shovel, STONE), 1);

        assertEquals(STONE.slot(), pick.slot(), "dirt wants a shovel and stone does not: the game decides which");
        assertEquals(ToolChoice.Note.MISSING, ToolChoice.choose(List.of(shovel), 1).note());
        assertFalse(ToolChoice.choose(List.of(shovel), 1).found());
        assertFalse(ToolChoice.choose(List.of(), 1).found(), "an empty bag has no answer to give");
    }

    @Test
    void aTieGoesToTheToolWithMoreLeftAndThenToTheHand() {
        ToolChoice.Candidate wornStone = tool(7, "minecraft:stone_pickaxe", 131, 20);
        assertEquals(STONE.slot(), ToolChoice.choose(List.of(wornStone, STONE), 1).slot());

        ToolChoice.Candidate inHand = tool(ToolChoice.HAND, "minecraft:stone_pickaxe", 131, 131);
        assertEquals(ToolChoice.HAND, ToolChoice.choose(List.of(inHand, STONE), 1).slot(),
                "the one already raised is not put down to pick up its twin");
    }

    @Test
    void aBrokenToolIsNeverRaised() {
        ToolChoice.Candidate broken = tool(1, "minecraft:diamond_pickaxe", 1561, 0);
        ToolChoice.Pick pick = ToolChoice.choose(List.of(broken, STONE), 1);
        assertEquals(STONE.slot(), pick.slot());
        assertTrue(pick.found());
    }

    @Test
    void aJobOfNothingIsStillAJobOfOne() {
        ToolChoice.Candidate oneUse = tool(1, "minecraft:iron_pickaxe", 250, 1);
        assertEquals(ToolChoice.Note.LAST_RESORT, ToolChoice.choose(List.of(oneUse), 0).note());
        assertEquals(ToolChoice.Note.LAST_RESORT, ToolChoice.choose(List.of(oneUse), -5).note());
    }
}
