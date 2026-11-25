package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

/**
 * He starts a project of his own — and does not, when the switch or the mode says otherwise.
 *
 * <p>Milestone 8, with both halves of the evidence the brief asks for. The positive: the owner stands
 * still, the companion is idle in CREATIVE, and within a bounded time a plan appears that nobody asked
 * for and reaches a terminal state. The negative matters as much: in ASSISTIVE he starts nothing, even
 * with the same world and the same waiting.
 *
 * <p>A project is a plan, so "terminal state" is the {@link TaskPlan}'s own notion of finished — he
 * either completes it or abandons it with a reason, and the plan says which.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Initiative}.
 */
public final class CobbieInitiativeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);

            // Something worth doing: a pile of logs beside him, and rubble at his feet level.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + 1 + i) + " " + base.getY() + " "
                            + (base.getZ() + 2) + " minecraft:oak_log");
                }
                return null;
            });

            // --- 1. ASSISTIVE starts nothing. ---
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            int plansBefore = server.computeOnServer(CobbieInitiativeTest::planCount);
            server.waitFor(s -> s.getTickCount() % 20 == 0, 400);
            int plansAfter = server.computeOnServer(CobbieInitiativeTest::planCount);
            if (plansAfter != plansBefore) {
                throw new AssertionError("He started " + (plansAfter - plansBefore) + " project(s) in"
                        + " ASSISTIVE, which is exactly what assistive means it must not do: "
                        + lastReport(server));
            }

            // --- 2. CREATIVE starts one, and it ends. ---
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            boolean started;
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.lastPlan() != null && !c.lastPlan().request().isBlank()
                                && c.lastPlan().request().startsWith("gathering"))
                        .orElse(false), 900);
                started = true;
            } catch (Throwable t) {
                started = false;
            }
            if (!started) {
                throw new AssertionError("No project started within 45s of being idle in CREATIVE; the"
                        + " last plan was " + lastReport(server));
            }
            String request = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().request());
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String status = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                throw new AssertionError("The project never reached a terminal state (" + status + "): "
                        + lastReport(server));
            }

            System.out.println("[gametest] initiative ok: started no project in ASSISTIVE over 20s, then"
                    + " started \"" + request + "\" unprompted in CREATIVE and it reached " + status
                    + " — " + lastReport(server));
        }
    }

    /** How many plans have been created: only ever grows when something is handed to the runner. */
    private static int planCount(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(c -> {
            TaskPlan plan = c.lastPlan();
            return plan == null ? 0 : (int) plan.id();
        }).orElse(-1);
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
