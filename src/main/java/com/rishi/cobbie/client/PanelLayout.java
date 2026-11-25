package com.rishi.cobbie.client;

/**
 * The panel's geometry, as arithmetic rather than drawing.
 *
 * <p>Split out because two of its rules were wrong in a way nobody notices without a full bag or a long
 * name: the header placed the level line at a fixed x, so a twenty-four character name drew straight
 * through it, and a full bag on a short window made the panel taller than the screen — putting its own
 * Done button past the bottom edge, when the panel is the only way to close it besides Esc.
 *
 * <p>Pure on purpose: the unit tier can check every window size without a client, and the screen keeps the
 * drawing. Widths arrive in pixels, measured by the caller's font, because a pixel width is the only honest
 * unit for "does this text fit".
 */
public final class PanelLayout {

    /** Column count of the bag grid. */
    public static final int COLS = 9;
    /** Slot size in pixels. */
    public static final int SLOT = 18;
    /** Everything in the panel that is not the bag grid. */
    private static final int CHROME = 34 + 10 + 14 + 40;

    public final int panelH;
    public final int shownRows;
    public final int hiddenSlots;
    /** How much of the name there is room to draw, in pixels. */
    public final int nameBudget;
    /** Where the level line starts relative to the panel's left edge. */
    public final int levelOffset;

    private PanelLayout(int panelH, int shownRows, int hiddenSlots, int nameBudget, int levelOffset) {
        this.panelH = panelH;
        this.shownRows = shownRows;
        this.hiddenSlots = hiddenSlots;
        this.nameBudget = nameBudget;
        this.levelOffset = levelOffset;
    }

    /**
     * @param bagSize how many slots he has, empty ones included
     * @param steps how many steps the job has
     * @param windowH the window height the panel must fit inside
     * @param nameWidthPx the name's width in pixels
     * @param levelWidthPx the level line's width in pixels
     * @param headerRoomPx space between the name's left edge and the status pill
     */
    public static PanelLayout compute(int bagSize, int steps, int windowH, int nameWidthPx,
            int levelWidthPx, int headerRoomPx) {
        int jobH = 24 + Math.max(1, steps) * 11;
        int bodyH = Math.max(104, jobH);

        int wantedRows = Math.max(1, (bagSize + COLS - 1) / COLS);
        // The bag gets whatever is left after the fixed chrome, the body and a margin.
        int maxRows = Math.max(1, (windowH - (CHROME + bodyH) - 12) / SLOT);
        int shownRows = Math.min(wantedRows, maxRows);
        int hidden = Math.max(0, bagSize - shownRows * COLS);

        int height = Math.min(CHROME + bodyH + shownRows * SLOT, Math.max(SLOT + CHROME, windowH - 12));

        // The name may take what is left after the level line and the pill; anything wider is trimmed.
        int budget = Math.max(24, headerRoomPx - levelWidthPx - 12);
        int levelOffset = Math.min(14 + Math.min(nameWidthPx, budget) + 12, 14 + budget + 12);

        return new PanelLayout(height, shownRows, hidden, budget, levelOffset);
    }
}
