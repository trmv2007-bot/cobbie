package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * Following at a distance a pathfinder will not plan in one go.
 *
 * <p>The owner teleports 64 blocks away, which is past what a single {@code moveTo} can plan for
 * on foot. The test asserts three separate things, because any one of them alone can be faked:
 * he closes most of the gap, he does it by walking (no single tick moves him more than a few
 * blocks, and the recall last resort is off), and he ends up next to the owner.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Reach}.
 */
public final class CobbieReachTest implements FabricClientGameTest {

    private static final int DISTANCE = 64;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            CobbieTestSupport.teleportOwner(server, 0, 0, DISTANCE);
            try {
                server.waitFor(s -> s.overworld().isLoaded(CobbieTestSupport.owner(s).blockPosition()), 200);
            } catch (Throwable t) {
                throw new AssertionError("Owner's chunk never loaded after the teleport", t);
            }
            context.waitTicks(10);

            double before = distanceToOwner(server);
            if (before < 40) {
                throw new AssertionError("Setup failed: he started only " + before + " blocks away");
            }

            // Sample his position while he walks. A recall or a hidden teleport would show up as one
            // jump of tens of blocks between two samples 10 ticks apart.
            List<double[]> trail = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                context.waitTicks(10);
                trail.add(position(server));
            }
            double biggestJump = 0;
            for (int i = 1; i < trail.size(); i++) {
                double[] a = trail.get(i - 1);
                double[] b = trail.get(i);
                biggestJump = Math.max(biggestJump, Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[2] - b[2], 2)));
            }

            try {
                server.waitFor(s -> distanceToOwner(s) < 6.0, 1200);
            } catch (Throwable t) {
                throw new AssertionError("He never reached the owner from " + before + " blocks; he is at "
                        + distanceToOwner(server) + " and standing at " + positionText(server), t);
            }
            double after = distanceToOwner(server);

            if (after > before - 40) {
                throw new AssertionError("Distance barely moved: before=" + before + " after=" + after);
            }
            if (biggestJump > 8.0) {
                throw new AssertionError("He moved " + String.format("%.1f", biggestJump)
                        + " blocks between samples — that is a teleport, not a walk");
            }

            System.out.println("[gametest] reach ok: came " + String.format("%.1f", before - after)
                    + " blocks (from " + String.format("%.1f", before) + " to " + String.format("%.1f", after)
                    + "), largest sampled step " + String.format("%.2f", biggestJump) + " blocks");
        }
    }

    private static double distanceToOwner(TestServerContext server) {
        return server.computeOnServer(CobbieReachTest::distanceToOwner);
    }

    private static double distanceToOwner(MinecraftServer server) {
        ServerPlayer owner = CobbieTestSupport.owner(server);
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server, 96).orElse(null);
        return cobbie == null ? -1.0 : (double) owner.distanceTo(cobbie);
    }

    private static double[] position(TestServerContext server) {
        return server.computeOnServer(CobbieReachTest::position);
    }

    private static double[] position(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server, 96).orElseThrow();
        return new double[]{cobbie.getX(), cobbie.getY(), cobbie.getZ()};
    }

    private static String positionText(TestServerContext server) {
        double[] p = position(server);
        return String.format("%.1f %.1f %.1f", p[0], p[1], p[2]);
    }
}
