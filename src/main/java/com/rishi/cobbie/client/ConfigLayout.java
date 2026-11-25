package com.rishi.cobbie.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where the config screen's rows sit, as arithmetic rather than drawing — the same split as {@link PanelLayout}.
 *
 * <p>Two things this exists to make checkable: a **collapsed section hides its rows and everything below moves up**,
 * and **scrolling is bounded by the content that is actually visible**, not by the rows that happen to exist. A
 * settings screen that scrolls past its end, or that shows an "Advanced" heading with nothing under it, is the kind
 * of thing nobody notices until a player is lost in it.
 *
 * <p>Pure on purpose: the unit tier can try every combination of collapsed sections and window heights without a
 * client, and the screen keeps the drawing.
 */
public final class ConfigLayout {

    /** One line of the screen: a section header, or a field belonging to a section. */
    public record Entry(Kind kind, String section, String label) {
        public enum Kind { HEADER, FIELD }
    }

    /** One visible line with the y it is drawn at. */
    public record Placed(Entry entry, int y) {}

    public final List<Placed> placed;
    public final int contentHeight;
    public final int maxScroll;

    private ConfigLayout(List<Placed> placed, int contentHeight, int maxScroll) {
        this.placed = List.copyOf(placed);
        this.contentHeight = contentHeight;
        this.maxScroll = maxScroll;
    }

    /**
     * @param entries every row the screen knows about
     * @param collapsed sections whose fields are hidden — their header stays, because a collapsed section with no
     *     header cannot be opened again
     * @param scroll how far the player has scrolled already; it is clamped, never trusted
     * @param rowHeight the height of one line
     * @param viewHeight the height the list is allowed to occupy
     */
    public static ConfigLayout compute(List<Entry> entries, Set<String> collapsed, int scroll, int rowHeight,
            int viewHeight) {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() == Entry.Kind.FIELD && collapsed.contains(entry.section())) continue;
            visible.add(entry);
        }

        int contentHeight = visible.size() * rowHeight;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        int clamped = Math.max(0, Math.min(scroll, maxScroll));

        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            int y = i * rowHeight - clamped;
            if (y + rowHeight <= 0 || y >= viewHeight) continue;   // off-screen rows are not drawn
            placed.add(new Placed(visible.get(i), y));
        }
        return new ConfigLayout(placed, contentHeight, maxScroll);
    }

    /** Whether a section has any field at all — a header with no fields is a dead end. */
    public static boolean hasFields(List<Entry> entries, String section) {
        for (Entry entry : entries) {
            if (entry.kind() == Entry.Kind.FIELD && entry.section().equals(section)) return true;
        }
        return false;
    }
}
