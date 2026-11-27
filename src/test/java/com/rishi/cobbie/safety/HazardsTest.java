package com.rishi.cobbie.safety;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The radius, the nearest-wins rule and the wording of the one line a refusal says.
 *
 * <p>These are the parts of the guard that must not need a world to check: a fire is refused because of a
 * distance, and a distance is arithmetic. The wiring — which owner, which bed, which remembered place — is
 * proved in game by {@code CobbiePermissionTest}, because that half is a question about what the world
 * holds and nothing here can answer it.
 */
class HazardsTest {

    private static Hazards.Protected at(String what, int x, int y, int z) {
        return new Hazards.Protected(what, x, y, z);
    }

    @Test
    void anActWellClearOfEverythingIsAllowed() {
        assertNull(Hazards.refusal(Hazards.Act.TNT, 0, 64, 0, List.of(at("you", 400, 64, 400))),
                "four hundred blocks away is not near anything");
    }

    @Test
    void anActInsideTheRadiusIsRefusedAndNamesTheNearestThing() {
        List<Hazards.Protected> near = List.of(
                at("you", 40, 64, 0),
                at("a bed", 3, 64, 4),
                at("the place you call home", 0, 64, 10));

        String line = Hazards.refusal(Hazards.Act.TNT, 0, 64, 0, near);

        assertTrue(line != null && line.startsWith("I will not prime that TNT 5 blocks from a bed:"),
                "the nearest protected thing decides the wording, and the bed at 3,64,4 is five blocks off: "
                        + line);
        assertTrue(line.contains("16 blocks clear"), "the radius is stated, not implied: " + line);
    }

    @Test
    void theRadiusIsTheBoundaryAndNotAFuzzyEdge() {
        assertNull(Hazards.refusal(Hazards.Act.FIRE, 0, 64, 0, List.of(at("you", Hazards.RADIUS + 1, 64, 0))),
                "one block past the radius is allowed");
        assertTrue(Hazards.refusal(Hazards.Act.FIRE, 0, 64, 0, List.of(at("you", Hazards.RADIUS, 64, 0))) != null,
                "exactly on the radius is refused: the rule says within it");
    }

    @Test
    void everyActReadsAsItsOwnSentence() {
        String fire = Hazards.refusal(Hazards.Act.FIRE, 0, 64, 0, List.of(at("you", 1, 64, 0)));
        String lava = Hazards.refusal(Hazards.Act.LAVA, 0, 64, 0, List.of(at("you", 1, 64, 0)));
        String tnt = Hazards.refusal(Hazards.Act.TNT, 0, 64, 0, List.of(at("you", 1, 64, 0)));

        assertEquals("I will not set that alight 1 block from you: " + Hazards.WHY, fire);
        assertEquals("I will not pour that lava 1 block from you: " + Hazards.WHY, lava);
        assertEquals("I will not prime that TNT 1 block from you: " + Hazards.WHY, tnt);
    }

    @Test
    void nothingToProtectMeansNothingRefused() {
        assertNull(Hazards.refusal(Hazards.Act.TNT, 0, 64, 0, List.of()),
                "a companion alone in the world may light a fire in it");
        assertNull(Hazards.refusal(null, 0, 64, 0, List.of(at("you", 0, 64, 0))),
                "no act, no rule");
    }
}
