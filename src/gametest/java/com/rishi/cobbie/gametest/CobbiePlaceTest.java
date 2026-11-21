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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Building: give him a stack of dirt, ask for one placed block, and require both the block to exist
 * and the stack to shrink by exactly one. A failed placement must not consume anything.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbiePlaceTest implements FabricClientGameTest {

    private static final String DIRT = "minecraft:dirt";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            int given = 8;
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.DIRT, given));
                return null;
            });

            int stocked = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().countItem(DIRT));
            if (stocked != given) {
                throw new AssertionError("Test setup failed: gave him " + given + " dirt but he holds " + stocked);
            }

            // A supported, empty spot six blocks away, with a cleared corridor to walk there.
            BlockPos target = server.computeOnServer(s -> {
                BlockPos from = CobbieTestSupport.owner(s).blockPosition();
                BlockPos spot = from.offset(0, 0, 6);
                for (int z = 0; z <= 7; z++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            CobbieTestSupport.command(s, "setblock "
                                    + (from.getX() + dx) + " " + (from.getY() + dy) + " " + (from.getZ() + z)
                                    + " minecraft:air");
                        }
                    }
                }
                CobbieTestSupport.command(s, "setblock "
                        + spot.getX() + " " + spot.getY() + " " + spot.getZ() + " minecraft:air");
                CobbieTestSupport.command(s, "setblock "
                        + spot.getX() + " " + (spot.getY() - 1) + " " + spot.getZ() + " minecraft:stone");
                return spot;
            });

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("place a block of dirt here",
                            List.of(new ActionIntent(ActionVocabulary.PLACE, DIRT,
                                    target.getX(), target.getY(), target.getZ(), ""))));
            if (refusal != null) {
                throw new AssertionError("Plan refused: " + refusal);
            }

            try {
                server.waitFor(s -> s.overworld().getBlockState(target).is(Blocks.DIRT), 900);
            } catch (Throwable t) {
                var state = server.computeOnServer(s -> s.overworld().getBlockState(target).toString());
                int left = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.countItem(DIRT)).orElse(-1));
                String plan = server.computeOnServer(s -> {
                    var p = CobbieTestSupport.cobbie(s).orElseThrow().lastPlan();
                    return p == null ? "no plan" : p.status() + " :: " + p.report();
                });
                throw new AssertionError("No dirt placed at " + target + "; state=" + state
                        + " remaining dirt=" + left + " plan=" + plan, t);
            }

            int left = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().countItem(DIRT));
            if (left != given - 1) {
                throw new AssertionError("Placed a block but the stack went from " + given + " to " + left);
            }

            System.out.println("[gametest] place ok: dirt at " + target.toShortString()
                    + ", stack " + given + " -> " + left);
        }
    }
}
