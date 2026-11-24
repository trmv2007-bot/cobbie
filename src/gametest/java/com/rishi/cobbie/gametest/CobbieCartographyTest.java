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
import net.minecraft.world.item.MapItem;

import java.util.List;

/**
 * A map zoomed out at a real cartography table.
 *
 * <p>The claim is the scale: the map that comes back has to be one step further out than the one that
 * went in — read from the map's own saved data before and after, not from "the table returned
 * something". The paper is spent, and he still has exactly one map.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Cartography}.
 */
public final class CobbieCartographyTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "crafting", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos table = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + table.getX() + " " + table.getY() + " "
                        + table.getZ() + " minecraft:cartography_table");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(MapItem.create(s.overworld(), start.getX(), start.getZ(), (byte) 0, true, false));
                cobbie.addItem(new ItemStack(Items.PAPER, 8));
                return null;
            });

            int scaleBefore = server.computeOnServer(CobbieCartographyTest::mapScale);
            if (scaleBefore < 0) throw new AssertionError("Setup failed: no filled map in his bag");

            // 1. An addition the table will not use is refused, and costs nothing.
            String refused = map(server, "glass_pane");
            if (refused != null) throw new AssertionError("map refused outright: " + refused);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "i have no")) {
                throw new AssertionError("An addition he does not carry was not refused with the reason: "
                        + lastReport(server));
            }

            // 2. Paper: the map comes back one scale further out.
            String asked = map(server, "paper");
            if (asked != null) throw new AssertionError("map refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            int scaleAfter = server.computeOnServer(CobbieCartographyTest::mapScale);
            int maps = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:filled_map"));
            int paper = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:paper"));

            if (scaleAfter != scaleBefore + 1) {
                throw new AssertionError("The map did not zoom out: scale " + scaleBefore + " -> "
                        + scaleAfter + ". " + lastReport(server));
            }
            if (maps != 1) throw new AssertionError("He has " + maps + " maps instead of 1");
            if (paper != 7) throw new AssertionError("Exactly one paper should have been spent: " + paper);

            System.out.println("[gametest] cartography ok: the map zoomed from scale " + scaleBefore
                    + " to " + scaleAfter + " with one paper spent, and he still has exactly one map; an"
                    + " addition he does not carry was refused and cost nothing");
        }
    }

    private static String map(TestServerContext server, String addition) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "zoom my map out with " + addition,
                List.of(new ActionIntent(ActionVocabulary.MAP, "filled_map", 0, 0, 0, addition))));
    }

    /** The scale of the filled map in his bag, or -1 when there is none. */
    private static int mapScale(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return -1;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.FILLED_MAP)) continue;
            var data = MapItem.getSavedData(stack, server.overworld());
            if (data != null) return data.scale;
        }
        return -1;
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
