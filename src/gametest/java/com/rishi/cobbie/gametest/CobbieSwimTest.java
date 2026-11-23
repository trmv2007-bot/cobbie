package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;

/**
 * Crossing water, in a corridor he cannot walk around.
 *
 * <p>The pool is walled, so "follow the owner across" can only mean swim across. The test asserts
 * he gets in, gets out, and arrives — and reports how many ticks the crossing took, so "much faster
 * in water" is a number rather than a feeling.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Swim}.
 */
public final class CobbieSwimTest implements FabricClientGameTest {

    private static final int WATER_LENGTH = 12;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());

            // A pool wide enough that walking around it costs more than swimming across, with its
            // surface flush with the bank so the crossing is a swim and not a climb. Going round was
            // exactly what a narrow channel got him the first time this test was written.
            int bank = start.getY() - 1;        // the block he stands on
            int zFrom = start.getZ() + 4;
            int zTo = zFrom + WATER_LENGTH;
            int halfWidth = 40;
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "fill " + (start.getX() - halfWidth) + " " + (bank - 1) + " " + zFrom
                        + " " + (start.getX() + halfWidth) + " " + bank + " " + zTo + " minecraft:water");
                return null;
            });

            boolean wet = server.computeOnServer(s -> s.overworld().getBlockState(
                    new BlockPos(start.getX(), bank, zFrom + 2)).getFluidState().isSource());
            if (!wet) throw new AssertionError("Setup failed: no water where the channel should be");

            // Put him on the near bank and the owner on the far one.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.teleportTo(s.overworld(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5,
                        java.util.Set.of(), 0.0F, 0.0F, true);
                return null;
            });
            CobbieTestSupport.teleportOwner(server, 0, 0, WATER_LENGTH + 8);
            try {
                server.waitFor(s -> s.overworld().isLoaded(CobbieTestSupport.owner(s).blockPosition()), 200);
            } catch (Throwable t) {
                throw new AssertionError("Owner's chunk never loaded across the channel", t);
            }

            try {
                server.waitFor(s -> inWater(s), 400);
            } catch (Throwable t) {
                throw new AssertionError("He never entered the water; he is at " + where(server), t);
            }
            long enteredAt = server.computeOnServer(MinecraftServer::getTickCount);

            // The tag above his head has to say what he is doing. "swimming" is a word 0.18.1 added
            // to the status shape, and the pool is the only place it can be true.
            java.util.Set<String> words = new java.util.HashSet<>();
            for (int i = 0; i < 40; i++) {
                context.waitTicks(1);
                words.add(server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.statusWord()).orElse("none")));
            }
            if (!words.contains("swimming")) {
                throw new AssertionError("He was in the water but the tag never said swimming; it said "
                        + words);
            }

            server.waitFor(s -> !inWater(s) && distanceToOwner(s) > 0, 900);

            long leftAt = server.computeOnServer(MinecraftServer::getTickCount);
            try {
                server.waitFor(s -> distanceToOwner(s) < 4.0, 900);
            } catch (Throwable t) {
                throw new AssertionError("He got wet but never reached the owner; he is at " + where(server)
                        + " (" + distanceToOwner(server) + " blocks away)", t);
            }

            long ticks = leftAt - enteredAt;
            if (ticks <= 0) ticks = 1;
            double rate = WATER_LENGTH / (ticks / 20.0);
            if (ticks > 400) {
                throw new AssertionError("The crossing took " + ticks + " ticks for " + WATER_LENGTH
                        + " blocks — that is wading, not swimming");
            }

            System.out.println("[gametest] swim ok: " + WATER_LENGTH + " blocks of water in " + ticks
                    + " ticks (" + String.format("%.2f", rate) + " blocks/s), reached the owner at "
                    + String.format("%.1f", distanceToOwner(server)) + " blocks");
        }
    }

    private static boolean inWater(TestServerContext server) {
        return server.computeOnServer(CobbieSwimTest::inWater);
    }

    private static boolean inWater(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::isInWater).orElse(false);
    }

    private static double distanceToOwner(TestServerContext server) {
        return server.computeOnServer(CobbieSwimTest::distanceToOwner);
    }

    private static double distanceToOwner(MinecraftServer server) {
        ServerPlayer owner = CobbieTestSupport.owner(server);
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server, 96).orElse(null);
        return cobbie == null ? -1.0 : (double) owner.distanceTo(cobbie);
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s, 96)
                .map(c -> c.blockPosition().toShortString() + " inWater=" + c.isInWater())
                .orElse("nowhere near the owner"));
    }
}
