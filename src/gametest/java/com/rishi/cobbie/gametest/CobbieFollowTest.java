package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/**
 * Follow behaviour: spawn Cobbie, move the owner ~12 blocks away, wait, assert Cobbie is
 * genuinely closing distance toward the owner (not just standing still).
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieFollowTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.getNormalPresetList().stream()
                            .filter(e -> e.preset().unwrapKey().map(WorldPresets.NORMAL::equals).orElse(false))
                            .findFirst()
                            .ifPresent(state::setWorldType);
                    state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    state.setDifficulty(Difficulty.PEACEFUL);
                    state.setAllowCommands(true);
                })
                .create()) {

            context.waitFor(mc -> mc.player != null && mc.level != null, 200);

            TestServerContext server = world.getServer();
            server.computeOnServer(s -> {
                ServerPlayer owner = s.getPlayerList().getPlayers().get(0);
                s.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/spawncobbie");
                return null;
            });
            server.waitFor(s -> !cobbieNearOwner(s).isEmpty(), 400);

            // Move the owner ~12 blocks away with a real teleport: absSnapTo on a ServerPlayer only
            // moves him server-side, and the client's next position packet snaps him straight back.
            server.computeOnServer(s -> {
                ServerPlayer owner = s.getPlayerList().getPlayers().get(0);
                owner.teleportTo(s.overworld(), owner.getX() + 12, owner.getY(), owner.getZ() + 12,
                        Set.of(), 0.0F, 8.0F, true);
                return null;
            });
            // Following only repaths where the owner's chunk is loaded, so wait for the world to
            // catch up before measuring anything. Without this the test fails on a slow chunk
            // stream and blames the dog.
            try {
                server.waitFor(s -> s.overworld().isLoaded(CobbieTestSupport.owner(s).blockPosition()), 200);
            } catch (Throwable t) {
                throw new AssertionError("Owner's chunk never loaded after the teleport", t);
            }
            context.waitTicks(10);
            double before = server.computeOnServer(CobbieFollowTest::distance);

            // Give him a generous window to close the gap; assert on evidence, not a fixed sleep.
            try {
                server.waitFor(s -> distance(s) < before - 2.0, 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie did not close distance: before=" + before
                        + " after=" + server.computeOnServer(CobbieFollowTest::distance), t);
            }
            double after = server.computeOnServer(CobbieFollowTest::distance);

            List<Entity> cobbie = server.computeOnServer(CobbieFollowTest::cobbieNearOwner);
            if (cobbie.isEmpty()) {
                throw new AssertionError("Cobbie gone after follow test");
            }
            if (!((PathfinderMob) cobbie.get(0)).isAlive()) {
                throw new AssertionError("Cobbie died during follow test");
            }

            // Distance should be closing (Cobbie walking toward owner), not static or growing
            if (after >= before - 1.0) {
                throw new AssertionError("Cobbie did not close distance: before=" + before
                        + " after=" + after);
            }

            System.out.println("[gametest] follow ok: distance before=" + before
                    + " after=" + after);
        }
    }

    private static double distance(MinecraftServer server) {
        ServerPlayer owner = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = server.overworld();
        List<Entity> found = level.getEntities((Entity) null,
                new AABB(owner.blockPosition()).inflate(48, 32, 48),
                e -> e.getType() == CobbieEntities.COBBIE);
        if (found.isEmpty()) {
            return -1;
        }
        Entity c = found.get(0);
        return owner.distanceTo(c);
    }

    private static List<Entity> cobbieNearOwner(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return List.of();
        }
        ServerPlayer owner = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = server.overworld();
        return level.getEntities((Entity) null,
                new AABB(owner.blockPosition()).inflate(48, 32, 48),
                e -> e.getType() == CobbieEntities.COBBIE);
    }
}