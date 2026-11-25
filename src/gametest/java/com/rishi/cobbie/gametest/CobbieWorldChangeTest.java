package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * A plan does not survive a world change.
 *
 * <p>A plan is coordinates in one world, so the moment the world under it changes the plan is stale:
 * it fails step by step with "that area is not loaded", which a player reads as him being thick
 * rather than as the plan being out of date. Two facts are asserted here, in order.
 *
 * <p><b>The guard works:</b> with a walk in progress, telling him the world changed ends the plan and
 * says so plainly. This is driven through {@link CobbieEntity#onWorldChanged()} rather than through a
 * real crossing, and that is deliberate — the second fact is why.
 *
 * <p><b>The engine already removes the plan on a crossing:</b> a real portal crossing reaches him
 * through {@code TeleportTransition}, which <em>replaces the entity with a new instance</em>. The task
 * queue does not come with it, so on this path there is no stale plan left to cancel — the guard above
 * is for the paths where the instance survives, and for anything that carries a plan across.
 *
 * <p>Both halves are printed, so the run says which world this engine build is in rather than leaving
 * it to be remembered.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=WorldChange}.
 */
public final class CobbieWorldChangeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // A walk long enough to still be running when we interrupt it, and inside
            // navigation_range: he refuses a target beyond it outright, which is a different test.
            BlockPos start = server.computeOnServer(s -> anyCobbie(s).orElseThrow().blockPosition());
            BlockPos near = start.offset(18, 0, 0);
            server.computeOnServer(s -> {
                anyCobbie(s).orElseThrow().moveTo(near);
                return null;
            });
            context.waitTicks(10);

            String setup = server.computeOnServer(s -> anyCobbie(s)
                    .map(CobbieWorldChangeTest::describe).orElse("no cobbie"));
            if (!server.computeOnServer(s -> anyCobbie(s).map(CobbieEntity::isBusy).orElse(false))) {
                throw new AssertionError("Setup failed: he had no walk in progress to interrupt (" + setup + ")");
            }

            // The guard: the world changed under the plan.
            server.computeOnServer(s -> {
                anyCobbie(s).orElseThrow().onWorldChanged();
                return null;
            });
            // Poll rather than sleep a fixed number of ticks: the line is said on a later tick, and
            // reading one field at one instant would score whatever happened to be last.
            boolean idle = false;
            boolean explained = false;
            for (int tick = 0; tick < 20 && !explained; tick++) {
                context.waitTicks(2);
                idle = server.computeOnServer(s -> anyCobbie(s).map(c -> !c.isBusy()).orElse(false));
                // The window, not the newest line: the plan's own settled report can land a tick behind the line
                // that explains the event, and reading one field then scores whatever happened to be last.
                explained = server.computeOnServer(s -> anyCobbie(s)
                        .map(c -> c.saidRecently("changed worlds")).orElse(false));
            }
            if (!idle) {
                throw new AssertionError("The plan survived the world change; he is "
                        + server.computeOnServer(s -> anyCobbie(s)
                                .map(CobbieWorldChangeTest::describe).orElse("gone")));
            }
            if (!explained) {
                throw new AssertionError("He stopped, but did not explain why; he said "
                        + server.computeOnServer(s -> anyCobbie(s)
                                .map(c -> c.recentLines().toString()).orElse("nothing")));
            }
            System.out.println("[gametest] world change guard ok: he said "
                    + server.computeOnServer(s -> anyCobbie(s)
                            .map(c -> c.recentLines().toString()).orElse("nothing")));

            // The engine: a real crossing, and what actually becomes of a plan on it.
            BlockPos before = server.computeOnServer(s -> anyCobbie(s).orElseThrow().blockPosition());
            java.util.UUID uuidBefore = server.computeOnServer(s -> anyCobbie(s).orElseThrow().getUUID());
            server.computeOnServer(s -> {
                // The owner goes first: in play he is the reason the companion crosses, and a mob
                // teleported into an unloaded level is added to nothing at all.
                var owner = CobbieTestSupport.owner(s);
                owner.teleportTo(s.getLevel(Level.NETHER), 8.5, 64.0, 8.5, java.util.Set.of(), 0.0F, 0.0F, true);
                return null;
            });
            context.waitTicks(5);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = anyCobbie(s).orElseThrow();
                cobbie.moveTo(before.offset(12, 0, 0));
                var transition = new net.minecraft.world.level.portal.TeleportTransition(
                        s.getLevel(Level.NETHER), new net.minecraft.world.phys.Vec3(8.5, 64.0, 8.5),
                        net.minecraft.world.phys.Vec3.ZERO, 0.0F, 0.0F,
                        net.minecraft.world.level.portal.TeleportTransition.PLACE_PORTAL_TICKET);
                cobbie.teleport(transition);
                return null;
            });
            context.waitTicks(10);

            // The UUID is printed because the whole neighbour question hangs on it: the crossing
            // replaces the instance, so every registry keyed by UUID survives only if this one value
            // does, and that is worth seeing in a log rather than assuming.
            String where = server.computeOnServer(s -> anyCobbie(s)
                    .map(c -> c.level().dimension().toString() + " " + c.blockPosition().toShortString()
                            + " busy=" + c.isBusy() + " uuid=" + c.getUUID()
                            + (c.getUUID().equals(uuidBefore) ? " (same uuid)" : " (new uuid)"))
                    .orElse("not in any level"));
            if (!where.contains("the_nether")) {
                throw new AssertionError("Setup failed: he was not carried to the nether (" + where + ")");
            }
            if (where.contains("busy=true")) {
                throw new AssertionError("A plan survived a real crossing (" + where
                        + ") — the guard above is no longer the whole story and this test needs rewriting");
            }
            System.out.println("[gametest] world change ok: the guard ended a plan, and a real crossing left him at "
                    + where + " with no plan to cancel");
        }
    }

    private static String describe(CobbieEntity cobbie) {
        return "busy=" + cobbie.isBusy() + " job=\"" + cobbie.currentTaskDescription() + "\" pos="
                + cobbie.blockPosition().toShortString() + " said=\"" + cobbie.lastSpokenLine() + "\"";
    }

    /**
     * The companion in any loaded level. The ordinary helper searches near the owner, which stops
     * finding him the moment he is in one world and the owner is in another — exactly the state this
     * test is about.
     */
    private static Optional<CobbieEntity> anyCobbie(net.minecraft.server.MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CobbieEntity cobbie && cobbie.isAlive()) return Optional.of(cobbie);
            }
        }
        return Optional.empty();
    }
}
