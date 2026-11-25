package com.rishi.cobbie.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as a promise, because the answer decides whether a claim is repaired or delivered as if it happened.
 *
 * <p>The rule was a hand-written verb list and it missed the words the model actually used — "equipping",
 * "switching", "applying" — so those replies passed the honesty check untouched and the owner read a sentence about
 * work that had not been queued. These cases are the ones the harness caught, kept so the rule cannot quietly
 * narrow again.
 */
class PromiseTest {

    @Test
    void thePhrasingsTheModelActuallyUsedAllCount() {
        assertTrue(CobbieBrain.promisesAnAction("Equipping my worst pickaxe — the worn one — now."));
        assertTrue(CobbieBrain.promisesAnAction("Switching to your worn pickaxe now."));
        assertTrue(CobbieBrain.promisesAnAction("Applying that sharpness book to your best sword."));
        assertTrue(CobbieBrain.promisesAnAction("On it — zooming your map out."));
        assertTrue(CobbieBrain.promisesAnAction("I'll dig those out."));
        assertTrue(CobbieBrain.promisesAnAction("Heading over to the loom."));
    }

    @Test
    void ordinaryConversationIsNotAPromise() {
        assertFalse(CobbieBrain.promisesAnAction("I'm Cobbie — your friendly helper in this world."));
        assertFalse(CobbieBrain.promisesAnAction("Good question. The answer is four."));
        assertFalse(CobbieBrain.promisesAnAction(""));
        assertFalse(CobbieBrain.promisesAnAction(null));
    }

    @Test
    void aRefusalIsNotAPromise() {
        // "I have no hoe" must not trigger a repair call: he is reporting, not claiming.
        assertFalse(CobbieBrain.promisesAnAction("I have no hoe and no seeds, so I cannot till that."));
    }
}
