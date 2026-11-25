package com.rishi.cobbie.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Describing what he carries, where every ambiguity is cheap to try: a resolution that picks silently is worse
 * than one that asks, and one that resolves something he does not carry is worse than both.
 */
class ItemQueryTest {

    private static ItemQuery.Candidate axe(String name, int damage, int maxDamage) {
        return new ItemQuery.Candidate("minecraft:iron_axe", name, 1, List.of(), damage, maxDamage);
    }

    private static final ItemQuery.Candidate SWORD_TWO =
            new ItemQuery.Candidate("minecraft:diamond_sword", "", 1, List.of("sharpness 2"), 10, 1561);
    private static final ItemQuery.Candidate SWORD_FIVE =
            new ItemQuery.Candidate("minecraft:diamond_sword", "", 1, List.of("sharpness 5"), 900, 1561);
    private static final ItemQuery.Candidate BOOK =
            new ItemQuery.Candidate("minecraft:enchanted_book", "Sharpness III", 1, List.of("sharpness 3"), 0, 0);
    private static final ItemQuery.Candidate PICK_WORN =
            new ItemQuery.Candidate("minecraft:iron_pickaxe", "", 1, List.of(), 240, 250);
    private static final ItemQuery.Candidate PICK_FRESH =
            new ItemQuery.Candidate("minecraft:iron_pickaxe", "", 1, List.of(), 0, 250);
    private static final ItemQuery.Candidate TORCH =
            new ItemQuery.Candidate("minecraft:torch", "", 64, List.of(), 0, 0);

    private static final List<ItemQuery.Candidate> BAG =
            List.of(SWORD_TWO, SWORD_FIVE, BOOK, PICK_WORN, PICK_FRESH, TORCH, axe("Old Faithful", 30, 250));

    @Test
    void anExactIdWins() {
        ItemQuery.Result result = ItemQuery.resolve("minecraft:torch", BAG);
        assertTrue(result.ok());
        assertEquals(TORCH, result.chosen());

        assertTrue(ItemQuery.resolve("torch", BAG).ok(), "the namespace is optional for a person");
        assertTrue(ItemQuery.resolve("  TORCH  ", BAG).ok(), "and case and space are not a riddle");
    }

    @Test
    void aCustomNameWinsOverAnId() {
        ItemQuery.Result result = ItemQuery.resolve("old faithful", BAG);
        assertTrue(result.ok(), result.refusal());
        assertEquals("Old Faithful", result.chosen().customName());
    }

    @Test
    void anEnchantmentNamesAnItem() {
        ItemQuery.Result result = ItemQuery.resolve("sharpness book", BAG);
        assertTrue(result.ok(), "a book described by its enchantment must resolve: " + result.refusal());
        assertEquals(BOOK, result.chosen());
    }

    @Test
    void bestAndWorstChooseByDurability() {
        ItemQuery.Result worst = ItemQuery.resolve("worst pickaxe", BAG);
        assertTrue(worst.ok(), worst.refusal());
        assertEquals(PICK_WORN, worst.chosen(), "the worst pickaxe is the one with the least left");

        ItemQuery.Result best = ItemQuery.resolve("best pickaxe", BAG);
        assertTrue(best.ok(), best.refusal());
        assertEquals(PICK_FRESH, best.chosen(), "the best is the freshest");
    }

    @Test
    void mostNumerousCountsRatherThanGuesses() {
        ItemQuery.Result result = ItemQuery.resolve("most numerous", BAG);
        assertTrue(result.ok(), result.refusal());
        assertEquals(TORCH, result.chosen());
    }

    @Test
    void twoEquallyGoodCandidatesAreAQuestionNotAPick() {
        // Two sharpness swords: neither id nor name distinguishes them, and the enchantment matches both.
        ItemQuery.Result result = ItemQuery.resolve("sharpness", List.of(SWORD_TWO, SWORD_FIVE));
        assertFalse(result.ok(), "an ambiguous ask must not be resolved silently");
        assertNotNull(result.refusal());
        assertTrue(result.refusal().toLowerCase().contains("which one"), result.refusal());
        assertEquals(2, result.candidates().size(), "the refusal must list what the choices were");
        assertTrue(result.refusal().contains("diamond sword"), result.refusal());
    }

    @Test
    void nothingHeIsNotCarrying() {
        ItemQuery.Result result = ItemQuery.resolve("netherite_sword", BAG);
        assertFalse(result.ok(), "he does not carry that, so it cannot resolve");
        assertTrue(result.refusal().contains("netherite_sword"), result.refusal());

        ItemQuery.Result empty = ItemQuery.resolve("sword", List.of());
        assertFalse(empty.ok());
        assertTrue(empty.refusal().contains("not carrying"), empty.refusal());
    }

    @Test
    void aBlankDescriptionIsRefused() {
        assertFalse(ItemQuery.resolve("", BAG).ok());
        assertFalse(ItemQuery.resolve(null, BAG).ok());
    }

    @Test
    void oneSwordIsNotAmbiguous() {
        ItemQuery.Result result = ItemQuery.resolve("sword", List.of(SWORD_FIVE));
        assertTrue(result.ok(), result.refusal());
        assertEquals(SWORD_FIVE, result.chosen());

        assertNull(ItemQuery.resolve("sword", List.of()).chosen());
    }
}
