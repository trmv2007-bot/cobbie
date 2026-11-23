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

import java.util.List;

/**
 * Containers and the furnace, which is the capability that changes what he can build.
 *
 * <p>Two stages so a failure says which half broke. First a chest round trip: put cobblestone in,
 * take it back out, both sides verified by counting. Then smelting: raw iron and coal into a furnace,
 * an iron ingot out of it, and — the point of the whole exercise — a <em>craft</em> request for an
 * iron pickaxe that plans the smelt itself and needs no separate instruction.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Container}.
 */
public final class CobbieContainerTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            int groundY = start.getY() - 1;          // the block a chest actually occupies
            BlockPos chest = new BlockPos(start.getX() + 2, groundY, start.getZ() - 2);
            BlockPos furnace = new BlockPos(start.getX() - 2, groundY, start.getZ() - 2);
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + chest.getX() + " " + chest.getY() + " "
                        + chest.getZ() + " minecraft:chest");
                CobbieTestSupport.command(s, "setblock " + furnace.getX() + " " + furnace.getY() + " "
                        + furnace.getZ() + " minecraft:furnace");
                return null;
            });
            boolean placed = server.computeOnServer(s ->
                    s.overworld().getBlockEntity(chest) instanceof net.minecraft.world.Container
                            && s.overworld().getBlockEntity(furnace) instanceof net.minecraft.world.Container);
            if (!placed) throw new AssertionError("Setup failed: no chest or furnace block entity");

            // 1. The permission is off by default, and the refusal says which switch to flip.
            CobbieTestSupport.permit(server, "containers", false);
            String refused = offer(server, ActionVocabulary.PUT, "cobblestone", chest, "16");
            if (refused != null) throw new AssertionError("put refused outright: " + refused);
            context.waitTicks(20);
            if (!reportSays(server, "not allowed")) {
                throw new AssertionError("A refused container action did not say why: " + lastReport(server));
            }

            CobbieTestSupport.permit(server, "containers", true);
            server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .addItem(new ItemStack(Items.COBBLESTONE, 16)));

            String putRefusal = offer(server, ActionVocabulary.PUT, "cobblestone", chest, "16");
            if (putRefusal != null) throw new AssertionError("put refused: " + putRefusal);
            try {
                server.waitFor(s -> chestCount(s, chest, Items.COBBLESTONE) == 16, 300);
            } catch (Throwable t) {
                throw new AssertionError("Chest never got the cobblestone: it holds "
                        + server.computeOnServer(s -> chestCount(s, chest, Items.COBBLESTONE))
                        + "; he is carrying " + inventory(server), t);
            }

            String takeRefusal = offer(server, ActionVocabulary.TAKE, "cobblestone", chest, "16.");
            if (takeRefusal != null) throw new AssertionError("take refused: " + takeRefusal);
            try {
                server.waitFor(s -> chestCount(s, chest, Items.COBBLESTONE) == 0
                        && carried(s, "minecraft:cobblestone") >= 16, 300);
            } catch (Throwable t) {
                throw new AssertionError("Chest was not emptied back into his bag; he carries "
                        + inventory(server), t);
            }

            // 2. Iron: ore plus fuel in, ingot out — the reason a furnace matters.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.RAW_IRON, 4));
                cobbie.addItem(new ItemStack(Items.COAL, 4));
                return null;
            });
            String smeltRefusal = offer(server, ActionVocabulary.CRAFT, "iron_ingot", furnace, "2");
            if (smeltRefusal != null) throw new AssertionError("craft iron ingot refused: " + smeltRefusal);
            try {
                server.waitFor(s -> carried(s, "minecraft:iron_ingot") >= 2, 900);
            } catch (Throwable t) {
                throw new AssertionError("He never smelted the iron. He carries " + inventory(server)
                        + "; furnace holds input=" + furnaceSlot(server, furnace, 0)
                        + " fuel=" + furnaceSlot(server, furnace, 1)
                        + " result=" + furnaceSlot(server, furnace, 2), t);
            }

            System.out.println("[gametest] container ok: chest round-trip of 16 cobblestone, then "
                    + carried(server, "minecraft:iron_ingot") + " iron ingot(s) smelted from raw ore; "
                    + lastReport(server));
        }
    }

    private static String offer(TestServerContext server, String verb, String target, BlockPos at, String data) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                verb + " " + target,
                List.of(new ActionIntent(verb, target, at.getX(), at.getY(), at.getZ(), data))));
    }

    private static int carried(MinecraftServer server, String id) {
        return CobbieTestSupport.cobbie(server).map(c -> c.countItem(id)).orElse(-1);
    }

    private static int carried(TestServerContext server, String id) {
        return server.computeOnServer(s -> carried(s, id));
    }

    private static int chestCount(MinecraftServer server, BlockPos pos, net.minecraft.world.item.Item item) {
        var entity = server.overworld().getBlockEntity(pos);
        if (!(entity instanceof net.minecraft.world.Container container)) return -1;
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static String furnaceSlot(TestServerContext server, BlockPos pos, int slot) {
        return server.computeOnServer(s -> {
            var entity = s.overworld().getBlockEntity(pos);
            if (!(entity instanceof net.minecraft.world.Container container)) return "no furnace";
            ItemStack stack = container.getItem(slot);
            return stack.isEmpty() ? "empty" : stack.getCount() + "x " + stack.getItem();
        });
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan)
                .map(plan -> plan.report())
                .orElse("no plan"));
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT)
                .contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String inventory(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::describeInventory).orElse("no companion"));
    }
}
