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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import java.util.List;

/**
 * Harvesting ripe wheat and sowing the same cells again.
 *
 * <p>Tilling, sowing and bone meal already work through {@code use} — a probe confirmed hoe, seeds and
 * bone meal all through the actor before this verb existed — so this test is about the cycle that was
 * missing: ripe crops come off, their drops are his, and the cells are sown again from his bag.
 *
 * <p>Two refusals matter as much as the harvest: a field that is still growing must say so rather
 * than silently doing nothing, and a harvest with no seed to replant must say that too instead of
 * claiming a replant.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Farm}.
 */
public final class CobbieFarmTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "collect", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                // Three ripe wheat on farmland beside him, and one that is still growing.
                for (int i = 0; i < 3; i++) {
                    BlockPos farm = new BlockPos(start.getX() + 1 + i, start.getY() - 1, start.getZ() + 1);
                    CobbieTestSupport.command(s, "setblock " + farm.getX() + " " + farm.getY() + " " + farm.getZ()
                            + " minecraft:farmland[moisture=7]");
                    CobbieTestSupport.command(s, "setblock " + farm.getX() + " " + (farm.getY() + 1) + " "
                            + farm.getZ() + " minecraft:wheat[age=7]");
                }
                BlockPos young = new BlockPos(start.getX() + 4, start.getY() - 1, start.getZ() + 1);
                CobbieTestSupport.command(s, "setblock " + young.getX() + " " + young.getY() + " " + young.getZ()
                        + " minecraft:farmland[moisture=7]");
                CobbieTestSupport.command(s, "setblock " + young.getX() + " " + (young.getY() + 1) + " "
                        + young.getZ() + " minecraft:wheat[age=2]");
                cobbie.addItem(new ItemStack(Items.WHEAT_SEEDS, 5));
                return null;
            });
            BlockPos ripeCentre = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ() + 1);
            BlockPos young = new BlockPos(start.getX() + 4, start.getY() - 1, start.getZ() + 1);

            // 1. The harvest: three ripe cells come off, all three are sown again.
            String asked = farm(server, "");
            if (asked != null) throw new AssertionError("farm refused outright: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            int sown = server.computeOnServer(s -> cropsAtAge(s, ripeCentre, false));
            int stillRipe = server.computeOnServer(s -> cropsAtAge(s, ripeCentre, true));
            int youngAge = server.computeOnServer(s -> s.overworld().getBlockState(young.above())
                    .getBlock() instanceof CropBlock crop
                            ? crop.getAge(s.overworld().getBlockState(young.above())) : -1);
            if (stillRipe != 0) {
                throw new AssertionError(stillRipe + " ripe crop(s) were left standing. " + lastReport(server));
            }
            if (sown != 3) {
                throw new AssertionError("Replanted " + sown + " of 3 cells. " + lastReport(server));
            }
            if (youngAge != 2) {
                throw new AssertionError("The unripe crop was disturbed: age " + youngAge + " instead of 2");
            }
            if (!reportSays(server, "harvested 3")) {
                throw new AssertionError("The report does not say what came off: " + lastReport(server));
            }

            // 2. Nothing ripe now: the refusal has to say that, not shrug.
            String again = farm(server, "");
            if (again != null) throw new AssertionError("farm refused outright: " + again);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            // The refusal's own words: "nothing is ripe yet — N crop(s) are still growing".
            if (!reportSays(server, "ripe yet")) {
                throw new AssertionError("A field of seedlings was not refused with the reason: "
                        + lastReport(server));
            }

            // 3. Ripe again with no seeds left: harvest, and say plainly that nothing was sown.
            server.computeOnServer(s -> {
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (start.getX() + 1 + i) + " " + start.getY() + " "
                            + (start.getZ() + 1) + " minecraft:wheat[age=7]");
                }
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.consumeItem("minecraft:wheat_seeds", cobbie.countItem("minecraft:wheat_seeds"));
                return null;
            });
            String bare = farm(server, "");
            if (bare != null) throw new AssertionError("farm refused outright: " + bare);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "no seed")) {
                throw new AssertionError("A harvest with no seeds did not say so: " + lastReport(server));
            }

            System.out.println("[gametest] farm ok: 3 ripe wheat harvested and all 3 cells resown, the"
                    + " growing crop untouched; a field of seedlings refused as \"not ripe\"; and a harvest"
                    + " with no seeds reported that instead of claiming a replant");
        }
    }

    private static String farm(TestServerContext server, String crop) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "tend the farm",
                List.of(new ActionIntent(ActionVocabulary.FARM, crop, 0, 0, 0, ""))));
    }

    /** How many crops in the three cells are at max age (ripe) or at age 0 (just sown). */
    private static int cropsAtAge(MinecraftServer server, BlockPos centre, boolean ripe) {
        int found = 0;
        for (int i = -1; i <= 1; i++) {
            BlockPos pos = centre.offset(i, 1, 0);
            var state = server.overworld().getBlockState(pos);
            if (!(state.getBlock() instanceof CropBlock crop)) continue;
            int age = crop.getAge(state);
            if (ripe ? crop.isMaxAge(state) : age == 0) found++;
        }
        return found;
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
