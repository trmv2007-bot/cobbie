package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Persistence: he is the same Cobbie with the same bag after the world is closed and reopened, and a
 * project that did not survive the reload is owned up to out loud, exactly once.
 *
 * <p>That first half is the claim 0.5.0 made loudest and never tested. The second half is 0.74.0: a plan
 * is memory, so a reload ends it — and the two ways that can go are not equally bad. A companion who
 * quietly stands there has silently lost the owner's work; a companion who says he lost it, once, has
 * told the truth in the only voice he has. The other half of the rule is the negative, and it is the
 * harder one: a reload with nothing in flight must not produce a line, or the sentence becomes noise
 * nobody reads.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Persistence}.
 */
public final class CobbiePersistenceTest implements FabricClientGameTest {

    private static final int STONE = 7;
    private static final String WALK_REQUEST = "walk over to the rise";

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        UUID ownerId;
        UUID cobbieId;
        TestWorldSave interruptedSave;
        TestWorldSave quietSave;

        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            CobbieEntity cobbie = CobbieTestSupport.spawn(context, world.getServer());
            CobbieTestSupport.command(world.getServer(), "cobbie stay");

            cobbie.addItem(new ItemStack(Items.STONE, STONE));
            // His hunger is saved beside his bag: three of the game's own numbers, off full so the
            // round trip is visible and with no food in the bag so he cannot eat them away.
            cobbie.setHunger(12, 3.0f);
            cobbie.addExhaustion(1.5f);
            int stocked = cobbie.countItem("minecraft:stone");
            if (stocked != STONE) {
                throw new AssertionError("Test setup failed: gave " + STONE + " stone but he holds " + stocked);
            }

            // Nothing is running, so nothing is owed: the marker must be blank on the way out.
            String marker = world.getServer().computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .interruptedPlanRequest());
            if (!marker.isEmpty()) {
                throw new AssertionError("An idle companion went to the save with \"" + marker
                        + "\" marked as in flight");
            }

            ownerId = cobbie.getOwner();
            cobbieId = cobbie.getUUID();
            save = world.getWorldSave();
        }

        try (TestSingleplayerContext reopened = save.open()) {
            TestServerContext server = reopened.getServer();
            CobbieTestSupport.awaitWorld(context);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie was not there after relogging into the saved world", t);
            }

            CobbieEntity again = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow());
            if (!cobbieId.equals(again.getUUID())) {
                throw new AssertionError("A different companion came back: was " + cobbieId
                        + ", now " + again.getUUID());
            }
            if (!ownerId.equals(again.getOwner())) {
                throw new AssertionError("Owner changed across the relog: was " + ownerId
                        + ", now " + again.getOwner());
            }
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:stone"));
            if (carried != STONE) {
                throw new AssertionError("He came back with " + carried + " stone instead of " + STONE);
            }
            // And the food bar came back with him, the same three numbers the game writes.
            int[] hunger = server.computeOnServer(s -> {
                CobbieEntity c = CobbieTestSupport.cobbie(s).orElseThrow();
                return new int[] {c.foodLevel(), Math.round(c.saturationLevel() * 10.0f),
                        Math.round(c.exhaustionLevel() * 10.0f)};
            });
            if (hunger[0] != 12 || hunger[1] != 30 || hunger[2] != 15) {
                throw new AssertionError("His hunger did not survive the relog: food " + hunger[0]
                        + ", saturation " + (hunger[1] / 10.0f) + ", exhaustion " + (hunger[2] / 10.0f)
                        + " (saved 12 / 3.0 / 1.5)");
            }

            // The negative: this save had nothing in flight, so waking must be silent. Sixty ticks is
            // long enough for the first-tick line and for anything the brain might add afterwards.
            CobbieTestSupport.elapsed(server, 60);
            String quietWake = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastSpokenLine());
            if (CobbieEntity.WAKE_LINE.equals(quietWake)) {
                throw new AssertionError("He announced a lost project after a reload that had none: "
                        + quietWake);
            }
            String quietMarker = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .interruptedPlanRequest());
            if (!quietMarker.isEmpty()) {
                throw new AssertionError("A reload with nothing in flight left a marker behind: \""
                        + quietMarker + "\"");
            }
            System.out.println("[gametest] persistence ok: same uuid, same owner, stone intact at " + carried
                    + ", and a reload with nothing in flight was silent");

            // The positive: hand him work that cannot finish inside the save window, check the marker
            // really carries that request, and save while it runs. A short walk can finish while a full
            // run's save is still being written, and then the save legitimately carries nothing — a
            // save-timing race in the fixture, not a defect in him. Eight legs of eight blocks is a walk
            // no save can outlast, where three legs stopped being enough once the suite grew.
            interruptedSave = null;
            for (int attempt = 1; attempt <= 2 && interruptedSave == null; attempt++) {
                // Short legs rather than one long one: each has to be reachable in a normal world or the
                // plan dies before the write; together they take far longer than a save.
                String refused = server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    BlockPos from = cobbie.blockPosition();
                    // The same three legs that are known walkable here, repeated three times: no new
                    // terrain to path across, and a walk three times longer than the old fixture's.
                    List<ActionIntent> legs = new java.util.ArrayList<>();
                    for (int round = 0; round < 3; round++) {
                        legs.add(new ActionIntent(ActionVocabulary.WALK_TO, "",
                                from.getX() + 8, from.getY(), from.getZ(), ""));
                        legs.add(new ActionIntent(ActionVocabulary.WALK_TO, "",
                                from.getX() + 8, from.getY(), from.getZ() + 8, ""));
                        legs.add(new ActionIntent(ActionVocabulary.WALK_TO, "",
                                from.getX(), from.getY(), from.getZ() + 8, ""));
                    }
                    return cobbie.offerPlan(WALK_REQUEST, legs);
                });
                if (refused != null) {
                    throw new AssertionError("The test's own plan was refused: " + refused);
                }
                String inFlight = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .interruptedPlanRequest());
                if (!WALK_REQUEST.equals(inFlight)) {
                    throw new AssertionError("A started project was not marked in flight: the marker reads \""
                            + inFlight + "\"");
                }
                boolean stillWalking = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .isBusy());
                if (!stillWalking) {
                    throw new AssertionError("The project settled before the save, so nothing was in flight"
                            + " to interrupt");
                }
                interruptedSave = reopened.getWorldSave();
                boolean survivedTheSave = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .isBusy());
                if (!survivedTheSave) {
                    // It finished in the pause after the save request; that save cannot be used.
                    interruptedSave = null;
                    CobbieTestSupport.elapsed(server, 40);
                }
            }
            if (interruptedSave == null) {
                throw new AssertionError("Two attempts and the project always finished inside the save window,"
                        + " so a save with work in flight could not be produced");
            }
        }

        try (TestSingleplayerContext woken = interruptedSave.open()) {
            TestServerContext server = woken.getServer();
            CobbieTestSupport.awaitWorld(context);
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie was not there after the interrupted save reopened", t);
            }

            String said;
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> CobbieEntity.WAKE_LINE.equals(c.lastSpokenLine())).orElse(false), 400);
                said = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().lastSpokenLine());
            } catch (Throwable t) {
                // Which half failed: a save carrying nothing is the fixture's race, a save carrying the marker
                // and saying nothing is the mechanism. The message says which, so the next reader does not guess.
                String state = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> "marker=\"" + c.interruptedPlanRequest() + "\" pending=" + c.wakeLinePending()
                                + " last=\"" + c.lastSpokenLine() + "\"")
                        .orElse("no companion"));
                throw new AssertionError("He woke into a save with a project in flight and said nothing; " + state, t);
            }
            if (!CobbieEntity.WAKE_LINE.equals(said)) {
                throw new AssertionError("The wake-up line was not the one the brief asks for: \"" + said + "\"");
            }
            // Nothing may resume: a plan does not survive a reload, and pretending it did would be worse
            // than saying it is gone.
            boolean resumed = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().isBusy());
            if (resumed) {
                throw new AssertionError("A project came back with the save instead of being reported lost");
            }
            String clearedMarker = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .interruptedPlanRequest());
            if (!clearedMarker.isEmpty()) {
                throw new AssertionError("The marker survived the line, so it can say it twice: \""
                        + clearedMarker + "\"");
            }
            System.out.println("[gametest] wake-up ok: \"" + said + "\", nothing resumed, marker cleared");

            quietSave = woken.getWorldSave();
        }

        // The line is once-only: the next login into a save taken after it was spoken says nothing. That
        // needs its own open, because a world cannot be opened while another one's server is running.
        try (TestSingleplayerContext later = quietSave.open()) {
            TestServerContext again = later.getServer();
            CobbieTestSupport.awaitWorld(context);
            try {
                again.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie was not there after the third open", t);
            }
            CobbieTestSupport.elapsed(again, 60);
            String repeat = again.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastSpokenLine());
            if (CobbieEntity.WAKE_LINE.equals(repeat)) {
                throw new AssertionError("He said it again on the next login: it is not a once-only line");
            }
            System.out.println("[gametest] wake-up is once-only: the next login said \"" + repeat + "\"");
        }
    }
}
