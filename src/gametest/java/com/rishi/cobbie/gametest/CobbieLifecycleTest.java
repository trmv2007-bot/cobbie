package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The autonomous project lifecycle: interrupted, impossible, and not silently repeated.
 *
 * <p>The fifth failure a player meets, and the one that leaves no trace when it goes wrong — a project that vanishes
 * without a word, or one that starts itself again behind the owner's back. The rule is the same two endings as the
 * adversity run: **he finishes, or he stops and says why.** The unacceptable outcome is a project that silently
 * disappears, silently repeats, or claims completion it did not achieve.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Lifecycle}.
 */
public final class CobbieLifecycleTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);
            CobbieTestSupport.command(server, "cobbie stay");

            // --- 1. The owner interrupts HIS OWN project. ---
            //
            // The contract is that a self-started project is cancelled by an owner instruction; two owner
            // instructions queue behind each other, which is a different rule. So the project here comes from
            // initiative: creative autonomy, a pile in sight, and nothing asked for.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(3, 0, 3);
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + i) + " " + base.getY() + " "
                            + base.getZ() + " minecraft:oak_log");
                }
                CobbieTestSupport.command(s, "cobbie autonomy creative");
                return null;
            });
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.lastPlan() != null
                        && c.lastPlan().request().startsWith("gathering")).orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("No initiative project started, so the interruption cannot be tested");
            }
            long initiativePlan = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().id());
            String beforeInterrupt = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            System.out.println("[lifecycle] the initiative project (" + initiativePlan + ") is " + beforeInterrupt);

            BlockPos ownerJob = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(-4, 0, -4);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan("actually, mine the log over here", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
                return base;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);

            // The owner's instruction must have run...
            boolean ownerJobDone = server.computeOnServer(s -> s.overworld().getBlockState(ownerJob).isAir());
            String ownerReport = lastReport(server);
            if (!ownerJobDone) {
                throw new AssertionError("The owner's instruction did not run: " + ownerReport);
            }
            // ...and the self-started project must be cancelled, in words, not merely dropped.
            String cancelled = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .planRecord(initiativePlan));
            if (cancelled == null || !cancelled.toLowerCase(java.util.Locale.ROOT).contains("cancel")) {
                throw new AssertionError("The initiative project was not recorded as cancelled: " + cancelled);
            }
            // And it must not come back on its own inside the cooldown.
            long latest = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            CobbieTestSupport.elapsed(server, 200);
            long later = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            // Anything created in this window is fine unless it is the cancelled project again: a follow-up after
            // the owner's job is the brain commenting on a result, which is not the project coming back.
            for (long id = latest + 1; id <= later; id++) {
                final long probe = id;
                String record = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .planRecord(probe));
                if (record != null && record.startsWith("gathering the oak log nearby")) {
                    throw new AssertionError("The cancelled project came back: " + record);
                }
            }
            System.out.println("[lifecycle] interrupted: owner instruction done, the project recorded \""
                    + cancelled + "\", and nothing restarted");

            // --- 2. A project whose target is taken away abandons once, with a reason, and does not repeat. ---
            long before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(2, 0, 5);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan("mine that far log", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
                // The world removes it before he gets there.
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:air");
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);
            String impossibleState = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            String impossibleReport = lastReport(server);
            if ("COMPLETED".equals(impossibleState)) {
                throw new AssertionError("An impossible project reported completion: " + impossibleReport);
            }
            CobbieTestSupport.elapsed(server, 120);
            // A follow-up plan after a failure is the brain asking the model what to do next — legitimate, and not
            // the same thing as the impossible project re-announcing itself. Only the second would be the defect.
            long after = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            // The impossible project is plan `before + 1`; only ids after it can be a re-announcement of it.
            for (long id = before + 2; id <= after; id++) {
                final long probe = id;
                String record = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .planRecord(probe));
                if (record != null && record.startsWith("mine that far log")) {
                    throw new AssertionError("The impossible project re-announced itself: " + record);
                }
            }
            System.out.println("[lifecycle] impossible: " + impossibleState + " — " + impossibleReport
                    + " (and it did not re-announce)");

            System.out.println("[lifecycle] done");
        }
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(TaskPlan::report).orElse("no plan"));
    }
}
