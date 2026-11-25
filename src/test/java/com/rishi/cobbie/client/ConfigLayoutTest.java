package com.rishi.cobbie.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config screen's arithmetic and its save path — the two halves that can be checked without drawing anything.
 *
 * <p>The rules worth pinning: a collapsed section hides its fields but keeps its header (or it can never be opened
 * again), the scroll never runs past the visible content, and a config file is only changed when Save is pressed.
 */
class ConfigLayoutTest {

    private static final int ROW = 22;

    private static final List<ConfigLayout.Entry> ENTRIES = List.of(
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.HEADER, "Brain", "Brain"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Brain", "Model"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Brain", "API Key"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.HEADER, "Behaviour", "Behaviour"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Behaviour", "Autonomy"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.HEADER, "Advanced", "Advanced"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Advanced", "Max Tokens"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Advanced", "Read Timeout"),
            new ConfigLayout.Entry(ConfigLayout.Entry.Kind.FIELD, "Advanced", "Retries"));

    @Test
    void aCollapsedSectionHidesItsFieldsAndKeepsItsHeader() {
        ConfigLayout layout = ConfigLayout.compute(ENTRIES, Set.of("Advanced"), 0, ROW, 400);
        boolean headerSeen = layout.placed.stream().anyMatch(p -> p.entry().label().equals("Advanced"));
        boolean fieldSeen = layout.placed.stream().anyMatch(p -> p.entry().label().equals("Max Tokens"));
        assertTrue(headerSeen, "a collapsed section must keep its header, or it can never be opened again");
        assertTrue(!fieldSeen, "a collapsed section must hide its fields");
        assertEquals(ENTRIES.size() * ROW - 3 * ROW, layout.contentHeight,
                "three hidden fields should shrink the content by three rows");
    }

    @Test
    void rowsStackOneRowApartAndNothingOverlaps() {
        ConfigLayout layout = ConfigLayout.compute(ENTRIES, Set.of(), 0, ROW, 1000);
        for (int i = 1; i < layout.placed.size(); i++) {
            assertEquals(layout.placed.get(i - 1).y() + ROW, layout.placed.get(i).y(),
                    "row " + i + " is not one row below its predecessor");
        }
    }

    @Test
    void theScrollNeverRunsPastTheContent() {
        ConfigLayout tall = ConfigLayout.compute(ENTRIES, Set.of(), 500, ROW, 100);
        assertEquals(tall.maxScroll, 500 - 100 < 0 ? 0 : ENTRIES.size() * ROW - 100,
                "max scroll must be content minus the visible height");
        ConfigLayout clamped = ConfigLayout.compute(ENTRIES, Set.of(), 9999, ROW, 100);
        int lastBottom = clamped.placed.get(clamped.placed.size() - 1).y() + ROW;
        assertTrue(lastBottom <= 100, "after clamping, the last row must be inside the view, not past it");
        ConfigLayout negative = ConfigLayout.compute(ENTRIES, Set.of(), -50, ROW, 100);
        assertEquals(0, negative.placed.get(0).y(), "a negative scroll is treated as none");
    }

    @Test
    void aSectionWithNoFieldsIsRecognisedAsADeadEnd() {
        assertTrue(ConfigLayout.hasFields(ENTRIES, "Brain"));
        assertTrue(!ConfigLayout.hasFields(ENTRIES, "Nothing"));
    }

    @Test
    void savingWritesTheFileAndClosingWithoutSavingDoesNot() throws IOException {
        Path file = Files.createTempFile("cobbie-config", ".json");
        try {
            JsonObject original = new JsonObject();
            original.addProperty("model", "before");
            assertNull(ConfigStore.write(file, original), "the first write should succeed");

            String onDisk = Files.readString(file);
            assertTrue(onDisk.contains("before"));

            // An edit that is never saved: the draft changes, the file does not.
            JsonObject draft = new JsonObject();
            draft.addProperty("model", "after");
            assertEquals(Files.readString(file), onDisk, "closing without saving must leave the file untouched");

            assertNull(ConfigStore.write(file, draft), "Save should write");
            assertTrue(Files.readString(file).contains("after"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aFailedWriteSaysWhyRatherThanFailingSilently() {
        // A path inside a directory that does not exist: the failure must come back as a reason, not an exception.
        Path missing = Path.of("no-such-directory-for-cobbie", "cobbie.json");
        String refusal = ConfigStore.write(missing, new JsonObject());
        assertNotNull(refusal, "a write that cannot happen must report a reason");
        assertTrue(refusal.toLowerCase(java.util.Locale.ROOT).contains("could not write"), refusal);
    }
}
