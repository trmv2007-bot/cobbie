package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared setup for the in-game tests: a normal-preset world, the owner, and the one companion that
 * belongs to them. Every test drives Cobbie the way a player does — commands are dispatched from
 * the owner's command source, because a console source cannot spawn or own a companion.
 */
final class CobbieTestSupport {

    private CobbieTestSupport() {
    }

    static TestSingleplayerContext createWorld(ClientGameTestContext context, Difficulty difficulty,
                                               WorldCreationUiState.SelectedGameMode mode) {
        return createWorld(context, difficulty, mode, WorldPresets.NORMAL);
    }

    /**
     * Action tests use a flat world: level ground with no hillside between him and the block means a
     * red test is about his pathfinding and not about the terrain he happened to spawn in.
     */
    static TestSingleplayerContext createFlatWorld(ClientGameTestContext context, Difficulty difficulty,
                                                   WorldCreationUiState.SelectedGameMode mode) {
        return createWorld(context, difficulty, mode, WorldPresets.FLAT);
    }

    private static TestSingleplayerContext createWorld(ClientGameTestContext context, Difficulty difficulty,
                                                       WorldCreationUiState.SelectedGameMode mode,
                                                       net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> preset) {
        return context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.getNormalPresetList().stream()
                            .filter(e -> e.preset().unwrapKey().map(preset::equals).orElse(false))
                            .findFirst()
                            .ifPresent(state::setWorldType);
                    state.setGameMode(mode);
                    state.setDifficulty(difficulty);
                    state.setAllowCommands(true);
                })
                .create();
    }

    static void awaitWorld(ClientGameTestContext context) {
        context.waitFor(mc -> mc.player != null && mc.level != null, 400);
    }

    static ServerPlayer owner(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(owner(server).createCommandSourceStack(), command);
    }

    static void command(TestServerContext server, String command) {
        server.computeOnServer(s -> {
            CobbieTestSupport.command(s, command);
            return null;
        });
    }

    static Optional<CobbieEntity> cobbie(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return Optional.empty();
        }
        ServerPlayer owner = owner(server);
        ServerLevel level = server.overworld();
        AABB box = new AABB(owner.blockPosition()).inflate(48, 32, 48);
        List<Entity> found = level.getEntities((Entity) null, box, e -> e.getType() == CobbieEntities.COBBIE);
        return found.isEmpty() ? Optional.empty() : Optional.of((CobbieEntity) found.get(0));
    }

    static CobbieEntity spawn(ClientGameTestContext context, TestServerContext server) {
        command(server, "spawncobbie");
        try {
            server.waitFor(s -> cobbie(s).isPresent(), 400);
        } catch (Throwable t) {
            throw new AssertionError("/spawncobbie never produced a companion near the owner", t);
        }
        return server.computeOnServer(s -> cobbie(s).orElseThrow());
    }

    /** Wait for the connection probe to settle into "connected" (nameplate "§eCobbie"). */
    static void awaitBrain(TestServerContext server) {
        try {
            server.waitFor(s -> cobbie(s).map(c -> {
                String name = c.getCustomName() == null ? "" : c.getCustomName().getString();
                return name.endsWith("Cobbie");
            }).orElse(false), 600);
        } catch (Throwable t) {
            String name = server.computeOnServer(s -> cobbie(s)
                    .map(c -> c.getCustomName() == null ? "" : c.getCustomName().getString())
                    .orElse("no cobbie"));
            throw new AssertionError("Cobbie never reached a connected brain; nameplate was \"" + name + "\"", t);
        }
    }

    static void permit(TestServerContext server, String which, boolean on) {
        command(server, "cobbie permit " + which + " " + (on ? "on" : "off"));
    }

    /** Teleport the owner properly: a server-side snap is undone by the client's next position packet. */
    static void teleportOwner(TestServerContext server, double dx, double dy, double dz) {
        server.computeOnServer(s -> {
            ServerPlayer owner = owner(s);
            owner.teleportTo(s.overworld(), owner.getX() + dx, owner.getY() + dy, owner.getZ() + dz,
                    Set.of(), owner.getYRot(), owner.getXRot(), true);
            return null;
        });
    }
}
