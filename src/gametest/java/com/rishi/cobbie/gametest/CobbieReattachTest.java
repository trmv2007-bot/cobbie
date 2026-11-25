package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * A reply in flight survives the crossing that replaces him, and still dies with him.
 *
 * <p>A crossing gives back a <em>new entity instance</em> with the same UUID. A model reply takes
 * seconds, so if a portal is taken inside that window the reply was composed for an object that no
 * longer exists — and the old guard, "is this body still there", answered that with silence because it
 * could not tell <b>replaced</b> from <b>died</b>. {@link CobbieBrain#reattach} asks for the companion
 * with that UUID instead, and this test drives that function through both endings rather than
 * asserting anything about the guard.
 *
 * <p>What it does <b>not</b> cover: a real model reply. That needs a configured provider and this
 * harness has none, so the routing is exercised directly and the model path is inferred — from the two
 * facts this test asserts, that the instance is replaced and the UUID survives.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Reattach}.
 */
public final class CobbieReattachTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            CobbieEntity before = server.computeOnServer(s -> anyCobbie(s).orElseThrow());
            UUID id = server.computeOnServer(s -> anyCobbie(s).orElseThrow().getUUID());

            // The owner goes first, which is what happens in play and what loads the destination
            // chunks: a mob teleported into an unloaded level is added to nothing at all.
            server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                owner.teleportTo(s.getLevel(Level.NETHER), 8.5, 64.0, 8.5, java.util.Set.of(), 0.0F, 0.0F, true);
                return null;
            });
            context.waitTicks(5);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = anyCobbie(s).orElseThrow();
                var transition = new net.minecraft.world.level.portal.TeleportTransition(
                        s.getLevel(Level.NETHER), new net.minecraft.world.phys.Vec3(8.5, 64.0, 8.5),
                        net.minecraft.world.phys.Vec3.ZERO, 0.0F, 0.0F,
                        net.minecraft.world.level.portal.TeleportTransition.PLACE_PORTAL_TICKET);
                cobbie.teleport(transition);
                return null;
            });
            context.waitTicks(10);

            String crossed = server.computeOnServer(s -> {
                CobbieEntity live = anyCobbie(s).orElse(null);
                CobbieEntity reattached = CobbieBrain.reattach(before, CobbieTestSupport.owner(s));
                if (live == null) return "no companion after the crossing";
                if (reattached == null) return "reattach returned nothing for a live companion";
                return "live=" + live.level().dimension().toString()
                        + " sameInstance=" + (reattached == before)
                        + " sameUuid=" + reattached.getUUID().equals(id)
                        + " alive=" + reattached.isAlive();
            });
            if (!crossed.contains("sameInstance=false")) {
                throw new AssertionError("Setup failed: the crossing did not replace the instance (" + crossed + ")");
            }
            if (!crossed.contains("sameUuid=true") || !crossed.contains("alive=true")) {
                throw new AssertionError("A reply in flight did not find him after the crossing (" + crossed + ")");
            }
            System.out.println("[gametest] reattach ok across a crossing: " + crossed);

            // The death case must behave exactly as before: nothing to answer, nobody speaking.
            server.computeOnServer(s -> {
                CobbieEntity live = anyCobbie(s).orElseThrow();
                live.kill((net.minecraft.server.level.ServerLevel) live.level());
                return null;
            });
            context.waitTicks(5);
            String dead = server.computeOnServer(s -> {
                CobbieEntity reattached = CobbieBrain.reattach(before, CobbieTestSupport.owner(s));
                return reattached == null ? "nothing" : "found " + reattached;
            });
            if (!dead.equals("nothing")) {
                throw new AssertionError("A reply was routed to him after he died: " + dead);
            }
            System.out.println("[gametest] reattach ok after death: nothing to deliver");
        }
    }

    /** The companion in any loaded level: after a crossing he and the owner are in different ones. */
    private static Optional<CobbieEntity> anyCobbie(net.minecraft.server.MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CobbieEntity cobbie && cobbie.isAlive()) return Optional.of(cobbie);
            }
        }
        return Optional.empty();
    }
}
