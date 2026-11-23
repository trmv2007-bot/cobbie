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
import net.minecraft.world.level.block.DoorBlock;

import java.util.List;

/**
 * A door is a way through, not a wall.
 *
 * <p>A player opens doors without thinking about it. Cobbie did not: the navigator was never told
 * doors are passable, so it priced the house as solid and walked around it. The test builds a wide
 * stone wall with one closed door in it and asks him to walk to the far side — the only route that
 * beats walking around a 33-block wall is through that door.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Door}.
 */
public final class CobbieDoorTest implements FabricClientGameTest {

    private static final int WALL_HALF_WIDTH = 16;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            int ground = start.getY() - 1;
            int wallZ = start.getZ() + 6;
            BlockPos target = new BlockPos(start.getX(), start.getY(), start.getZ() + 16);

            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "fill " + (start.getX() - WALL_HALF_WIDTH) + " " + start.getY()
                        + " " + wallZ + " " + (start.getX() + WALL_HALF_WIDTH) + " " + (start.getY() + 2)
                        + " " + wallZ + " minecraft:stone");
                // A door in the middle, closed, facing him.
                CobbieTestSupport.command(s, "setblock " + start.getX() + " " + start.getY() + " " + wallZ
                        + " minecraft:oak_door[half=lower,facing=north,open=false]");
                CobbieTestSupport.command(s, "setblock " + start.getX() + " " + (start.getY() + 1) + " " + wallZ
                        + " minecraft:oak_door[half=upper,facing=north,open=false]");
                return null;
            });

            boolean closed = server.computeOnServer(s -> {
                var state = s.overworld().getBlockState(new BlockPos(start.getX(), start.getY(), wallZ));
                return state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN);
            });
            if (!closed) throw new AssertionError("Setup failed: no closed door in the wall");

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk through the door", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            target.getX(), target.getY(), target.getZ(), ""))));
            if (refusal != null) throw new AssertionError("walk-to refused: " + refusal);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.blockPosition().distSqr(target) <= 9)
                        .orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("He never got through the door; he is at " + where(server)
                        + ", wall at z=" + wallZ + ", target " + target.toShortString(), t);
            }

            System.out.println("[gametest] door ok: walked through the closed door to "
                    + target.toShortString() + "; ground was " + ground);
        }
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.blockPosition().toShortString())
                .orElse("nowhere near the owner"));
    }
}
