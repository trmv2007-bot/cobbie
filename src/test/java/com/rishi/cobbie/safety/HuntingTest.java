package com.rishi.cobbie.safety;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "In a pen" as arithmetic: a barrier within the radius, in the animal's own column and three blocks above
 * and below it.
 *
 * <p>The one thing worth testing here rather than in game is the boundary, because the rule's whole claim
 * is that it is a distance and not a judgement: one block inside is kept, one block outside is wild.
 */
class HuntingTest {

    /** A world that is empty except for the squares a test builds. */
    private static Hunting.Barrier walls(Set<String> squares) {
        return (x, y, z) -> squares.contains(x + "," + y + "," + z);
    }

    private static Set<String> square(int x, int y, int z) {
        Set<String> out = new HashSet<>();
        out.add(x + "," + y + "," + z);
        return out;
    }

    @Test
    void openGroundIsNotAPen() {
        assertFalse(Hunting.inPen(walls(Set.of()), 0, 64, 0, Hunting.PEN_RADIUS),
                "nothing built anywhere near him");
    }

    @Test
    void aFenceAtTheEdgeOfTheRadiusStillCounts() {
        assertTrue(Hunting.inPen(walls(square(Hunting.PEN_RADIUS, 64, 0)), 0, 64, 0, Hunting.PEN_RADIUS),
                "exactly on the radius is inside the pen the rule describes");
    }

    @Test
    void oneBlockFurtherIsAnotherField() {
        assertFalse(Hunting.inPen(walls(square(Hunting.PEN_RADIUS + 1, 64, 0)), 0, 64, 0, Hunting.PEN_RADIUS),
                "the radius is the boundary, so a fence past it is not this animal's pen");
    }

    @Test
    void aWallAboveTheAnimalsHeadIsStillItsPen() {
        assertTrue(Hunting.inPen(walls(square(2, 66, 2)), 0, 64, 0, Hunting.PEN_RADIUS),
                "a two-block wall's upper half is the same wall");
        assertFalse(Hunting.inPen(walls(square(2, 70, 2)), 0, 64, 0, Hunting.PEN_RADIUS),
                "a roof five blocks up is not a fence around the animal");
    }
}
