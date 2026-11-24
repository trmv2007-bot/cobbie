package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

import java.util.List;

/**
 * Ladders and vines: going somewhere vertical and being followed.
 *
 * <p>A wall with a ladder on its face, and a walk to the top of the wall. The pathfinder has no climb
 * node, so without help he stands at the foot of the ladder forever — which is the same failure as the
 * water one, one dimension over.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Climb}.
 */
public final class CobbieClimbTest implements FabricClientGameTest {

    private static final int HEIGHT = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            int ground = start.getY();
            int wallZ = start.getZ() + 4;
            int x = start.getX();
            server.computeOnServer(s -> {
                // A wall three wide and five tall, with a ladder on the face nearest him.
                CobbieTestSupport.command(s, "fill " + (x - 1) + " " + ground + " " + wallZ + " "
                        + (x + 1) + " " + (ground + HEIGHT) + " " + wallZ + " minecraft:stone");
                CobbieTestSupport.command(s, "fill " + x + " " + ground + " " + (wallZ - 1) + " "
                        + x + " " + (ground + HEIGHT) + " " + (wallZ - 1) + " minecraft:ladder[facing=south]");
                return null;
            });
            BlockPos target = new BlockPos(x, ground + HEIGHT + 1, wallZ);
            boolean ready = server.computeOnServer(s -> s.overworld().getBlockState(target.below())
                    .is(net.minecraft.world.level.block.Blocks.STONE));
            if (!ready) throw new AssertionError("Setup failed: no wall top at " + target.toShortString());

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("climb up there", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            target.getX(), target.getY(), target.getZ(), ""))));
            if (refusal != null) throw new AssertionError("walk-to refused: " + refusal);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.getY() >= ground + HEIGHT - 0.5 && c.blockPosition().distSqr(target) <= 9)
                        .orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("He never got up the ladder; he is at " + where(server)
                        + " and the top is at " + target.toShortString(), t);
            }

            double top = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> (double) c.getY()).orElse(-1.0));
            System.out.println("[gametest] climb ok: went from y=" + ground + " to "
                    + String.format("%.1f", top) + " up a ladder");
        }
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.blockPosition().toShortString() + " onClimbable=" + c.onClimbable())
                .orElse("nowhere near the owner"));
    }
}
