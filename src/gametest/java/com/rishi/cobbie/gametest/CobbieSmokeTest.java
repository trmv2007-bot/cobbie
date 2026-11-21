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
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.List;

/**
 * Smallest in-game proof: boot a real client into a normal (non-flat) survival world, run
 * {@code /spawncobbie}, wait for the live model to connect, and screenshot the rendered frame.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieSmokeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.getNormalPresetList().stream()
                            .filter(e -> e.preset().unwrapKey()
                                    .map(WorldPresets.NORMAL::equals)
                                    .orElse(false))
                            .findFirst()
                            .ifPresent(state::setWorldType);
                    state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    state.setDifficulty(Difficulty.PEACEFUL);
                    state.setAllowCommands(true);
                })
                .create()) {
            context.waitFor(mc -> mc.player != null && mc.level != null);

            TestServerContext server = world.getServer();
            server.computeOnServer(s -> {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "time set day");
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "weather clear");
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "gamerule advanceTime false");
                return null;
            });

            // Pin the camera first so the "before" frame and the "after" frame are the same view.
            // /spawncobbie drops him at owner + (2, 0, 2), so yaw -45 faces that corner and pitch 8
            // looks slightly down at him.
            server.computeOnServer(s -> {
                ServerPlayer owner = s.getPlayerList().getPlayers().get(0);
                owner.absSnapTo(owner.getX(), owner.getY(), owner.getZ(), -45.0F, 8.0F);
                return null;
            });
            context.waitTicks(60);
            context.takeScreenshot("cobbie_before");

            server.computeOnServer(s -> {
                ServerPlayer owner = s.getPlayerList().getPlayers().get(0);
                s.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/spawncobbie");
                return null;
            });

            server.waitFor(s -> !cobbieNearOwner(s).isEmpty(), 400);
            try {
                server.waitFor(s -> nameOf(cobbieNearOwner(s)).endsWith("Cobbie"), 400);
            } catch (Throwable t) {
                String actual = server.computeOnServer(s -> nameOf(cobbieNearOwner(s)));
                throw new AssertionError("Cobbie never reached a connected brain; nameplate stayed \""
                        + actual + "\" (is Ollama running with the configured model?)", t);
            }
            context.waitFor(mc -> {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.getType() == CobbieEntities.COBBIE) {
                        return true;
                    }
                }
                return false;
            }, 100);
            context.waitTicks(20);

            List<Entity> found = server.computeOnServer(CobbieSmokeTest::cobbieNearOwner);
            Entity cobbie = found.get(0);
            if (!cobbie.isAlive()) {
                throw new AssertionError("Cobbie spawned dead at " + cobbie.blockPosition());
            }
            if (found.size() > 1) {
                throw new AssertionError("/spawncobbie produced " + found.size() + " companions for one player");
            }

            BlockPos at = cobbie.blockPosition();
            Path screenshot = context.takeScreenshot("cobbie_smoke_spawned");

            context.runOnClient(mc -> {
                Entity clientCobbie = null;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.getType() == CobbieEntities.COBBIE) {
                        clientCobbie = e;
                        break;
                    }
                }
                if (clientCobbie == null) {
                    throw new AssertionError("Cobbie never reached the client: he cannot be drawn");
                }
                if (mc.getEntityRenderDispatcher().getRenderer(clientCobbie) == null) {
                    throw new AssertionError("No entity renderer registered for Cobbie");
                }
                if (clientCobbie.isInvisible()) {
                    throw new AssertionError("Client entity is flagged invisible");
                }
            });

            System.out.println("[gametest] cobbie alive at " + at
                    + " name=" + cobbie.getCustomName()
                    + " screenshot=" + screenshot);
        }
    }

    private static String nameOf(List<Entity> cobbie) {
        if (cobbie.isEmpty() || cobbie.get(0).getCustomName() == null) {
            return "";
        }
        return cobbie.get(0).getCustomName().getString();
    }

    private static List<Entity> cobbieNearOwner(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return List.of();
        }
        ServerPlayer owner = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = server.overworld();
        AABB box = new AABB(owner.blockPosition()).inflate(16, 16, 16);
        return level.getEntities((Entity) null, box, e -> e.getType() == CobbieEntities.COBBIE);
    }
}
