package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

import java.util.List;

/**
 * Exploration that goes somewhere and comes back.
 *
 * <p>Two assertions, because either alone is the old behaviour or a bug: he has to get a real
 * distance from where he started (not the 6–16 block shuffle 0.17.0 did), and he has to return,
 * which is the plan's own second step rather than luck.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Explore}.
 */
public final class CobbieExploreTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "exploration", true);

            double[] home = position(server);
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("go and look around", List.of(
                            new ActionIntent(ActionVocabulary.EXPLORE, "", 0, 0, 0, ""))));
            if (refusal != null) throw new AssertionError("explore refused: " + refusal);

            try {
                server.waitFor(s -> distanceFromHome(s, home) >= 20.0, 1200);
            } catch (Throwable t) {
                throw new AssertionError("He never explored: he is still "
                        + String.format("%.1f", distanceFromHome(server, home)) + " blocks from home", t);
            }
            double went = distanceFromHome(server, home);

            try {
                server.waitFor(s -> distanceFromHome(s, home) <= 12.0, 1200);
            } catch (Throwable t) {
                throw new AssertionError("He explored " + String.format("%.1f", went)
                        + " blocks and did not come back; he is "
                        + String.format("%.1f", distanceFromHome(server, home)) + " blocks from where he started", t);
            }

            System.out.println("[gametest] explore ok: reached " + String.format("%.1f", went)
                    + " blocks from home, returned to "
                    + String.format("%.1f", distanceFromHome(server, home)) + " blocks");
        }
    }

    private static double[] position(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            return new double[]{cobbie.getX(), cobbie.getY(), cobbie.getZ()};
        });
    }

    private static double distanceFromHome(TestServerContext server, double[] home) {
        return server.computeOnServer(s -> distanceFromHome(s, home));
    }

    private static double distanceFromHome(MinecraftServer server, double[] home) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return Double.MAX_VALUE;
        double dx = cobbie.getX() - home[0];
        double dz = cobbie.getZ() - home[2];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
