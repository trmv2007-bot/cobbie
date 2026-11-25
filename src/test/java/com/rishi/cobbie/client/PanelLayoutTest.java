package com.rishi.cobbie.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's two readability rules, checked as arithmetic across window sizes.
 *
 * <p>Both were wrong in 0.52.0 and neither was visible without an extreme: the header put the level line at a
 * fixed x, so a twenty-four character name drew through it, and a full bag on a short window made the panel
 * taller than the screen, putting its own Done button past the bottom edge.
 */
class PanelLayoutTest {

    /** What a normal window looks like, in GUI pixels, with a mid-length name. */
    private static final int NORMAL_WIDTH = 360;
    private static final int NORMAL_HEIGHT = 240;

    @Test
    void thePanelAlwaysFitsTheWindow() {
        for (int windowH : new int[] {130, 180, 240, 320, 480}) {
            for (int bag : new int[] {9, 27, 45}) {
                PanelLayout layout = PanelLayout.compute(bag, 3, windowH, 90, 60, 200);
                assertTrue(layout.panelH <= windowH,
                        "bag " + bag + " in a " + windowH + "-high window: panel is " + layout.panelH);
            }
        }
    }

    @Test
    void aFullBagOnAShortWindowReportsWhatItLeftOut() {
        PanelLayout shortWindow = PanelLayout.compute(45, 3, 130, 90, 60, 200);
        assertTrue(shortWindow.hiddenSlots > 0,
                "a 45-slot bag cannot fit a 130-high window, but nothing was reported hidden");
        assertTrue(shortWindow.shownRows >= 1, "at least one row must always be drawn");

        PanelLayout tallWindow = PanelLayout.compute(45, 3, 480, 90, 60, 200);
        assertEquals(0, tallWindow.hiddenSlots, "a 45-slot bag fits a 480-high window");
    }

    @Test
    void theLevelLineStartsAfterTheNameEnds() {
        // A name far wider than the space available must be trimmed rather than drawn under the level line.
        PanelLayout layout = PanelLayout.compute(45, 3, NORMAL_HEIGHT, 300, 60, NORMAL_WIDTH - 28 - 40);
        assertTrue(layout.nameBudget > 0, "there must be room for something of the name");
        assertTrue(layout.nameBudget <= 300, "the name may not keep a width the panel cannot afford");
        assertTrue(layout.nameBudget + 12 <= layout.levelOffset,
                "the level line starts at " + layout.levelOffset + " but the name may run to "
                        + layout.nameBudget + ", which overlaps");
    }

    @Test
    void aShortNameLeavesTheLevelLineWhereItWas() {
        PanelLayout layout = PanelLayout.compute(9, 1, NORMAL_HEIGHT, 40, 60, NORMAL_WIDTH - 28 - 40);
        assertEquals(40 + 12 + 14, layout.levelOffset,
                "a short name should not push the level line: it starts just after the name");
    }

    @Test
    void everyStepOfAJobStillFits() {
        PanelLayout layout = PanelLayout.compute(45, 8, NORMAL_HEIGHT, 90, 60, 200);
        assertTrue(layout.panelH <= NORMAL_HEIGHT,
                "a long job pushes the panel past the window: " + layout.panelH);
    }
}
