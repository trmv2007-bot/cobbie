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
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * Crafting, from what he is carrying, through the game's own recipes.
 *
 * <p>Three things are asserted, in the order a player would discover them: it is refused while the
 * permission is off; one request makes planks from logs; and one request for a wooden pickaxe walks
 * the whole chain (log → planks → sticks → pickaxe) without being told each step. The last one is
 * the reason this exists — a companion that can only make one thing at a time from a table of
 * hardcoded recipes is not crafting, it is a party trick.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Craft}.
 */
public final class CobbieCraftTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            int logs = 4;
            server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .addItem(new ItemStack(Items.OAK_LOG, logs)));

            // 1. Refused while the switch is off, and nothing is made by the refusal. The switch is
            // set explicitly: the run directory keeps config/cobbie.json between runs, so a previous
            // run's "on" would otherwise be the state this test starts from.
            CobbieTestSupport.permit(server, "crafting", false);
            if (count(server, "minecraft:oak_planks") != 0) {
                throw new AssertionError("Setup failed: he started with planks");
            }
            String refusal = offer(server, "craft some planks", "oak_planks", "4");
            if (refusal != null) throw new AssertionError("craft plan refused outright: " + refusal);
            context.waitTicks(40);
            if (count(server, "minecraft:oak_planks") != 0) {
                throw new AssertionError("He crafted with the permission off");
            }
            boolean explained = reportContains(server, "switched off") || reportContains(server, "not allowed");
            if (!explained) {
                throw new AssertionError("A refused craft said nothing about permission: " + lastReport(server));
            }

            // 2. One recipe from what is in the bag.
            CobbieTestSupport.permit(server, "crafting", true);
            String planRefusal = offer(server, "craft some planks", "oak_planks", "4");
            if (planRefusal != null) throw new AssertionError("craft plan refused: " + planRefusal);
            try {
                server.waitFor(s -> countIn(s, "minecraft:oak_planks") >= 4, 300);
            } catch (Throwable t) {
                throw new AssertionError("No planks were made from his logs. He is carrying: "
                        + inventory(server), t);
            }

            // 3. The chain: one request, three recipes, no coaching.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.OAK_LOG, 2));
                return null;
            });
            int logsBefore = count(server, "minecraft:oak_log");
            String chainRefusal = offer(server, "make yourself a pickaxe", "wooden_pickaxe", "1");
            if (chainRefusal != null) throw new AssertionError("pickaxe plan refused: " + chainRefusal);
            try {
                server.waitFor(s -> countIn(s, "minecraft:wooden_pickaxe") >= 1, 400);
            } catch (Throwable t) {
                throw new AssertionError("He never made the pickaxe. He is carrying: " + inventory(server), t);
            }

            int logsAfter = count(server, "minecraft:oak_log");
            if (logsAfter >= logsBefore) {
                throw new AssertionError("The pickaxe did not come from his logs: logs " + logsBefore
                        + " -> " + logsAfter);
            }
            // Each stage starts only once the previous job has settled: queueing behind a retry is the
            // race that made an earlier test assert against a plan that had not begun.
            CobbieTestSupport.awaitPlanSettled(server, 300);

            // 4. The stonecutter: one stone is one stair, where the crafting table would want six, so a
            // pass here can only come from the stonecutting recipe.
            BlockPos saw = server.computeOnServer(s ->
                    CobbieTestSupport.owner(s).blockPosition().offset(2, -1, 2));
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + saw.getX() + " " + saw.getY() + " " + saw.getZ()
                        + " minecraft:stonecutter");
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.STONE, 1));
                return null;
            });
            String stairRefusal = offer(server, "cut me a stair", "stone_stairs", "1");
            if (stairRefusal != null) throw new AssertionError("stonecutter plan refused: " + stairRefusal);
            try {
                server.waitFor(s -> countIn(s, "minecraft:stone_stairs") >= 1, 400);
            } catch (Throwable t) {
                throw new AssertionError("No stair was cut from a single stone. He carries "
                        + inventory(server) + "; " + lastReport(server), t);
            }

            CobbieTestSupport.awaitPlanSettled(server, 300);

            // 5. The grid-reading specials. Repair: two damaged pickaxes become one, mended.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (int i = 0; i < 2; i++) {
                    ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
                    pick.setDamageValue(100);
                    cobbie.addItem(pick);
                }
                return null;
            });
            String repairRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("mend my pickaxes",
                            List.of(new ActionIntent(ActionVocabulary.REPAIR, "iron_pickaxe", 0, 0, 0, ""))));
            if (repairRefusal != null) throw new AssertionError("repair refused: " + repairRefusal);
            try {
                server.waitFor(s -> bestPickaxeDamage(s) >= 0 && bestPickaxeDamage(s) < 100, 300);
            } catch (Throwable t) {
                throw new AssertionError("The pickaxes were not mended; best damage is "
                        + server.computeOnServer(CobbieCraftTest::bestPickaxeDamage) + ". " + lastReport(server), t);
            }

            CobbieTestSupport.awaitPlanSettled(server, 300);

            // 6. Dye: red dye plus leather boots, decided by the game's own DyeRecipe.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var redDye = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getOptional(net.minecraft.resources.Identifier.parse("minecraft:red_dye"))
                        .orElseThrow(() -> new AssertionError("no red_dye item in this version"));
                cobbie.addItem(new ItemStack(redDye, 1));
                cobbie.addItem(new ItemStack(Items.LEATHER_BOOTS, 1));
                return null;
            });
            String dyeRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("dye my boots red",
                            List.of(new ActionIntent(ActionVocabulary.DYE, "red", 0, 0, 0, "leather_boots"))));
            if (dyeRefusal != null) throw new AssertionError("dye refused: " + dyeRefusal);
            try {
                server.waitFor(s -> bootsDyed(s), 300);
            } catch (Throwable t) {
                throw new AssertionError("The boots were not dyed. " + lastReport(server), t);
            }

            String made = lastReport(server);

            System.out.println("[gametest] craft ok: planks from logs, a pickaxe through the chain, "
                    + "a stair from one stone at the stonecutter, two pickaxes mended into one, boots dyed"
                    + " (logs " + logsBefore + " -> " + logsAfter + "); " + made);
        }
    }

    private static String offer(TestServerContext server, String request, String item, String data) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(request,
                List.of(new ActionIntent(ActionVocabulary.CRAFT, item, 0, 0, 0, data))));
    }

    private static int count(TestServerContext server, String id) {
        return server.computeOnServer(s -> countIn(s, id));
    }

    private static int countIn(MinecraftServer server, String id) {
        return CobbieTestSupport.cobbie(server).map(c -> c.countItem(id)).orElse(-1);
    }

    /** Damage of the least-damaged iron pickaxe in his bag, or -1 when he has none. */
    private static int bestPickaxeDamage(net.minecraft.server.MinecraftServer server) {
        var cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return -1;
        int best = -1;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.IRON_PICKAXE)) continue;
            if (best < 0 || stack.getDamageValue() < best) best = stack.getDamageValue();
        }
        return best;
    }

    private static boolean bootsDyed(net.minecraft.server.MinecraftServer server) {
        var cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return false;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.LEATHER_BOOTS)) continue;
            if (stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR) != null) return true;
        }
        return false;
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan)
                .map(plan -> plan.report())
                .orElse("no plan"));
    }

    private static boolean reportContains(TestServerContext server, String text) {
        return server.computeOnServer(s -> {
            var plan = CobbieTestSupport.cobbie(s).map(CobbieEntity::lastPlan).orElse(null);
            return plan != null && plan.report().toLowerCase(java.util.Locale.ROOT)
                    .contains(text.toLowerCase(java.util.Locale.ROOT));
        });
    }

    private static String inventory(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::describeInventory).orElse("no companion"));
    }
}
