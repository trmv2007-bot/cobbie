package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Agency, in two stages so a failure says which half broke: first walk-to must move him (pure
 * navigation), then mine must break a block he had to walk to (navigation plus a verified world
 * change). The block is placed by the test on cleared ground, so terrain and seed do not matter.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieMineTest implements FabricClientGameTest {

    private static final int TARGET_DISTANCE = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);

            // A cleared, level corridor so the walk is about pathing rather than about a hillside.
            BlockPos from = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos target = from.offset(0, 0, TARGET_DISTANCE);
            server.computeOnServer(s -> {
                for (int z = 0; z <= TARGET_DISTANCE + 1; z++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            CobbieTestSupport.command(s, "setblock "
                                    + (from.getX() + dx) + " " + (from.getY() + dy) + " " + (from.getZ() + z)
                                    + " minecraft:air");
                        }
                    }
                }
                return null;
            });

            // Stage 1: plain walking.
            BlockPos waypoint = from.offset(0, 0, 4);
            String walkRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk up the path",
                            List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                                    waypoint.getX(), waypoint.getY(), waypoint.getZ(), ""))));
            if (walkRefusal != null) {
                throw new AssertionError("walk-to refused: " + walkRefusal);
            }
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.blockPosition().distSqr(waypoint) <= 4)
                        .orElse(false), 600);
            } catch (Throwable t) {
                StringBuilder trace = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    context.waitTicks(5);
                    trace.append("\n  +").append((i + 1) * 5).append("t: ").append(diagnostics(server));
                }
                throw new AssertionError("walk-to did not move him. Traced:" + trace, t);
            }

            // Stage 2: mining, which he must first walk to.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock "
                        + target.getX() + " " + target.getY() + " " + target.getZ() + " minecraft:stone");
                return null;
            });
            boolean solid = server.computeOnServer(s -> s.overworld().getBlockState(target).is(Blocks.STONE));
            if (!solid) {
                throw new AssertionError("Test setup failed: no stone at " + target + " to mine");
            }

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("mine the stone in front of me",
                            List.of(new ActionIntent(ActionVocabulary.MINE, Items.STONE.toString(),
                                    target.getX(), target.getY(), target.getZ(), ""))));
            if (refusal != null) {
                throw new AssertionError("Mine plan refused: " + refusal);
            }

            try {
                server.waitFor(s -> s.overworld().getBlockState(target).isAir(), 900);
            } catch (Throwable t) {
                throw new AssertionError("Block at " + target + " was not mined. " + diagnostics(server), t);
            }

            double distance = server.computeOnServer(s -> Math.sqrt(
                    CobbieTestSupport.cobbie(s).orElseThrow()
                            .distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5)));
            if (distance > 5.0) {
                throw new AssertionError("Block was mined from " + distance + " blocks away");
            }

            System.out.println("[gametest] mine ok: block at " + target.toShortString()
                    + " broke, final distance=" + String.format("%.2f", distance));
        }
    }

    private static String diagnostics(TestServerContext server) {
        return server.computeOnServer(s -> {
            var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            var nav = ((Mob) cobbie).getNavigation();
            var path = nav.getPath();
            var plan = cobbie.lastPlan();
            var probe = nav.createPath(cobbie.blockPosition().getX(),
                    cobbie.blockPosition().getY(), cobbie.blockPosition().getZ() + 2, 1);
            return "pos=" + cobbie.blockPosition()
                    + " onGround=" + cobbie.onGround()
                    + " noAi=" + cobbie.isNoAi()
                    + " effectiveAi=" + cobbie.isEffectiveAi()
                    + " navDone=" + nav.isDone()
                    + " pathNodes=" + (path == null ? -1 : path.getNodeCount())
                    + " selfPath=" + (probe == null ? "null" : probe.getNodeCount())
                    + " plan=" + (plan == null ? "none" : plan.status() + " " + plan.report());
        });
    }
}
