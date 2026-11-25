package com.rishi.cobbie.presence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narration cadence over a long session, measured rather than listened to.
 *
 * <p>The rule the narrator promises is a floor on the gap between lines and no backlog behind it: a job whose
 * steps land faster than the cooldown produces fewer lines, never a queue that empties two minutes later. The
 * gametest for narration needs a live model to produce anything to narrate, so this checks the part that does
 * not: what the throttle does when it is offered thousands of lines.
 */
class NarratorCadenceTest {

    /** The shipped default for {@code chat_cooldown_ms}. */
    private static final int GAP = 2000;

    @Test
    void noTwoLinesArriveCloserThanTheGap() {
        Narrator narrator = new Narrator();
        List<Long> spokenAt = new ArrayList<>();
        // Ten minutes of a job whose steps land every 150 ms — far faster than the gap.
        for (long now = 0; now < 600_000; now += 150) {
            narrator.offer("step at " + now);
            String line = narrator.next(now, GAP);
            if (line != null) spokenAt.add(now);
        }
        for (int i = 1; i < spokenAt.size(); i++) {
            long gap = spokenAt.get(i) - spokenAt.get(i - 1);
            assertTrue(gap >= GAP,
                    "lines " + (i - 1) + " and " + i + " arrived " + gap + " ms apart, under the " + GAP
                            + " ms gap");
        }
        int ceiling = (int) (600_000 / GAP) + 1;
        assertTrue(spokenAt.size() <= ceiling,
                spokenAt.size() + " lines in ten minutes, which is more than the gap allows (" + ceiling + ")");
        assertTrue(spokenAt.size() >= 200,
                spokenAt.size() + " lines in ten minutes is quieter than the gap requires — the narrator is"
                        + " dropping lines it could have said");
    }

    @Test
    void aBacklogNeverBuildsUp() {
        Narrator narrator = new Narrator();
        for (int i = 0; i < 500; i++) {
            narrator.offer("line " + i);
        }
        String said = narrator.next(0, GAP);
        assertNotNull(said);
        assertEquals("line 499", said, "the newest line wins; older ones are dropped rather than queued");
        assertNull(narrator.next(GAP / 2, GAP), "the gap applies to the next line too");
        assertNull(narrator.next(GAP * 2, GAP), "nothing is left to say: 499 lines were dropped, not queued");
    }

    @Test
    void somethingTheOwnerSaidHoldsTheNextLine() {
        Narrator narrator = new Narrator();
        narrator.offer("I found a cave");
        narrator.noteSpokenAt(1_000);
        assertNull(narrator.next(1_500, GAP), "the owner just spoke; the narrator waits its turn");
        assertNotNull(narrator.next(3_000, GAP), "and then it says the line it was holding");
    }

    @Test
    void aCancelledJobsLineNeverArrivesLate() {
        Narrator narrator = new Narrator();
        narrator.offer("mined the oak log");
        narrator.clear();
        assertNull(narrator.next(10_000, GAP), "a cancelled job's line must not turn up minutes later");
    }

    @Test
    void anEmptyOrNoisyOfferIsNotSpoken() {
        Narrator narrator = new Narrator();
        narrator.offer("");
        narrator.offer("   ");
        narrator.offer(null);
        assertNull(narrator.next(0, GAP), "nothing worth saying was offered");
    }
}
