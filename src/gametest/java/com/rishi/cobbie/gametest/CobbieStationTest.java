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
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * Stations and containers from any side, and after moving.
 *
 * <p>A chest, a furnace, an anvil, a stonecutter and a crafting table, each used while he stands on a
 * different side of it, and after walking to a second position. What is asserted is the <b>contents of
 * the container</b> and <b>what came out of the station</b>, read off the world — never the sentence he
 * said about it. The interaction face is the thing that silently differs between sides, and the point of
 * this class is that nothing he does depends on it.
 *
 * <p>Two honest limits, named rather than papered over:
 *
 * <ul>
 *   <li>The <b>chest and furnace</b> are driven through their {@code Container} rather than through a
 *       menu, which is how this mod works everywhere: a menu is a screen for a player, and the slots are
 *       the same slots. So the side he stands on cannot affect the result — and this proves it.</li>
 *   <li>The <b>stonecutter and the crafting table</b> are gates and scenery rather than menus: a
 *       stonecutter step requires one within reach and then produces the recipe's output, and ordinary
 *       crafting is done by hand from the recipe table. The assertion is therefore the output in his bag
 *       from every side, not a menu that never opened. That is a design choice of the mod and is written
 *       down in {@code docs/AUDIT.md}.</li>
 * </ul>
 *
 * <p>And the one claim a menu block must not make: {@code use} on a station whose only answer to a
 * right-click is a menu reports a refusal with the reason, not "I used it" over an unchanged world.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Station}.
 */
public final class CobbieStationTest implements FabricClientGameTest {

    /** Feet positions around a block: two blocks away on each side, at the block's own height. */
    private static final Map<String, int[]> SIDES = Map.of(
            "north", new int[]{0, -2},
            "south", new int[]{0, 2},
            "east", new int[]{2, 0},
            "west", new int[]{-2, 0});

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "crafting", true);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.command(server, "gamerule doMobSpawning false");

            BlockPos origin = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos chest = origin.offset(4, 0, 0);
            BlockPos furnace = origin.offset(-4, 0, 0);
            BlockPos anvil = origin.offset(0, 0, 4);
            BlockPos stonecutter = origin.offset(0, 0, -4);
            BlockPos table = origin.offset(8, 0, 0);
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + coords(chest) + " minecraft:chest");
                CobbieTestSupport.command(s, "setblock " + coords(furnace) + " minecraft:furnace");
                CobbieTestSupport.command(s, "setblock " + coords(anvil) + " minecraft:anvil");
                CobbieTestSupport.command(s, "setblock " + coords(stonecutter) + " minecraft:stonecutter");
                CobbieTestSupport.command(s, "setblock " + coords(table) + " minecraft:crafting_table");
                if (s.overworld().getBlockEntity(chest) instanceof net.minecraft.world.Container container) {
                    container.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
                }
                return null;
            });

            // ---- 1. A chest, from each of the four sides, then from a second position after walking.
            for (Map.Entry<String, int[]> side : SIDES.entrySet()) {
                int before = chestCount(server, chest, Items.COBBLESTONE);
                standBeside(server, chest, side.getValue());
                String refusal = offer(server, ActionVocabulary.TAKE, "cobblestone", chest, "5");
                if (refusal != null) throw new AssertionError("take from the " + side.getKey()
                        + " side was refused: " + refusal);
                try {
                    server.waitFor(s -> chestCount(s, chest, Items.COBBLESTONE) == before - 5, 300);
                } catch (Throwable t) {
                    throw new AssertionError("Taking from the chest's " + side.getKey() + " side moved nothing:"
                            + " it holds " + chestCount(server, chest, Items.COBBLESTONE) + " of " + before
                            + "; " + CobbieTestSupport.lastReport(server), t);
                }
                int carried = carried(server, Items.COBBLESTONE);
                if (carried != 20 - (before - 5)) {
                    throw new AssertionError("Taking from the chest's " + side.getKey() + " side emptied it"
                            + " but his bag holds " + carried + " instead of " + (20 - (before - 5)));
                }
            }
            if (chestCount(server, chest, Items.COBBLESTONE) != 0) {
                throw new AssertionError("Four takes of five from four sides left "
                        + chestCount(server, chest, Items.COBBLESTONE) + " in a chest that held 20");
            }

            // The second position: seven blocks out, so he has to walk there before the put lands.
            CobbieTestSupport.stand(server, chest.offset(0, 1, -7));
            String putRefusal = offer(server, ActionVocabulary.PUT, "cobblestone", chest, "20");
            if (putRefusal != null) throw new AssertionError("put was refused: " + putRefusal);
            try {
                server.waitFor(s -> chestCount(s, chest, Items.COBBLESTONE) == 20
                        && CobbieTestSupport.cobbie(s).map(c -> c.countItem("minecraft:cobblestone"))
                        .orElse(-1) == 0, 600);
            } catch (Throwable t) {
                throw new AssertionError("After walking to a second position he put nothing back: the chest"
                        + " holds " + chestCount(server, chest, Items.COBBLESTONE) + " and he carries "
                        + carried(server, Items.COBBLESTONE) + ". " + CobbieTestSupport.lastReport(server), t);
            }

            // ---- 2. A furnace, from two sides: one raw iron cooked on each.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.RAW_IRON, 4));
                cobbie.addItem(new ItemStack(Items.COAL, 4));
                return null;
            });
            int wanted = 0;
            for (String side : List.of("north", "south")) {
                // "smelt two" is a target, not an increment: the second ask is for one more than he holds,
                // because asking for exactly what he already has plans nothing at all.
                wanted++;
                standBeside(server, furnace, SIDES.get(side));
                String refusal = offer(server, ActionVocabulary.SMELT, "raw_iron", furnace,
                        String.valueOf(wanted));
                if (refusal != null) throw new AssertionError("smelt from the " + side
                        + " side was refused: " + refusal);
                int target = wanted;
                try {
                    server.waitFor(s -> carried(s, Items.IRON_INGOT) == target, 900);
                } catch (Throwable t) {
                    throw new AssertionError("Nothing came out of the furnace's " + side + " side: input="
                            + furnaceSlot(server, furnace, 0) + " fuel=" + furnaceSlot(server, furnace, 1)
                            + " result=" + furnaceSlot(server, furnace, 2) + "; he carries "
                            + carried(server, Items.IRON_INGOT) + " ingots. " + CobbieTestSupport.lastReport(server), t);
                }
            }

            // ---- 3. An anvil, from two sides: the same book twice, so the level has to climb.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIAMOND_SWORD, 1));
                // The anvil charges levels and he earns his own — no levels, no work, which is the
                // refusal the failure above this line would otherwise be.
                cobbie.setExperience(10, 0.0f);
                return null;
            });
            int level = 0;
            for (String side : List.of("north", "east")) {
                // One book per ask: two books that would both go on the sword are a question he is right
                // to refuse to answer, not a job.
                server.computeOnServer(s -> {
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(sharpnessBook(s, 1));
                    return null;
                });
                standBeside(server, anvil, SIDES.get(side));
                String refusal = offer(server, ActionVocabulary.ANVIL, "diamond_sword", BlockPos.ZERO, "");
                if (refusal != null) throw new AssertionError("anvil from the " + side
                        + " side was refused: " + refusal);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                int now = swordLevel(server);
                if (now <= level) {
                    throw new AssertionError("The anvil at his " + side + " side put nothing on the sword:"
                            + " it reads Sharpness " + now + " and read " + level + ". "
                            + CobbieTestSupport.lastReport(server));
                }
                level = now;
            }

            // ---- 4. A stonecutter, from two sides: stairs out of stone, once from each.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.STONE, 8));
                return null;
            });
            for (String side : List.of("north", "south")) {
                // A count is a target, not an increment: asking for one when he already holds four plans
                // nothing at all, so each side asks for one more than he has.
                int before = carried(server, Items.STONE_STAIRS);
                standBeside(server, stonecutter, SIDES.get(side));
                String refusal = offer(server, ActionVocabulary.CRAFT, "stone_stairs", BlockPos.ZERO,
                        String.valueOf(before + 1));
                if (refusal != null) throw new AssertionError("stonecutting from the " + side
                        + " side was refused: " + refusal);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (carried(server, Items.STONE_STAIRS) <= before) {
                    throw new AssertionError("No stairs came out of the stonecutter's " + side + " side. "
                            + CobbieTestSupport.lastReport(server));
                }
            }

            // ---- 5. A crafting table, from two sides.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.OAK_PLANKS, 8));
                cobbie.addItem(new ItemStack(Items.STICK, 6));
                return null;
            });
            for (String side : List.of("north", "east")) {
                int before = carried(server, Items.WOODEN_PICKAXE);
                standBeside(server, table, SIDES.get(side));
                String refusal = offer(server, ActionVocabulary.CRAFT, "wooden_pickaxe", BlockPos.ZERO,
                        String.valueOf(before + 1));
                if (refusal != null) throw new AssertionError("crafting at the table's " + side
                        + " side was refused: " + refusal);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (carried(server, Items.WOODEN_PICKAXE) <= before) {
                    throw new AssertionError("No pickaxe came out of the table's " + side + " side. "
                            + CobbieTestSupport.lastReport(server));
                }
            }

            // ---- 6. And the claim a menu block must not make.
            standBeside(server, table, SIDES.get("west"));
            String useRefusal = offer(server, ActionVocabulary.USE, "", table, "");
            if (useRefusal != null) throw new AssertionError("use on the table was refused outright: "
                    + useRefusal);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            String report = CobbieTestSupport.lastReport(server);
            if (!report.toLowerCase(java.util.Locale.ROOT).contains("menu")) {
                throw new AssertionError("Using a crafting table, whose only answer to a right-click is a"
                        + " menu, was not refused with that reason: " + report);
            }

            System.out.println("[gametest] station ok: chest round-trip of 20 from four sides then put back"
                    + " after walking to a second position; two smelts from two sides; a book applied to one"
                    + " sword from two sides reaching Sharpness " + level + "; stairs cut from two sides;"
                    + " pickaxes made at the table from two sides; use on a menu refused with the reason");
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void standBeside(TestServerContext server, BlockPos station, int[] offset) {
        CobbieTestSupport.stand(server, station.above().offset(offset[0], 0, offset[1]));
    }

    private static String offer(TestServerContext server, String verb, String target, BlockPos at, String data) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan(verb + " " + target,
                        List.of(new ActionIntent(verb, target, at.getX(), at.getY(), at.getZ(), data))));
    }

    private static int carried(TestServerContext server, net.minecraft.world.item.Item item) {
        return server.computeOnServer(s -> carried(s, item));
    }

    private static int carried(MinecraftServer server, net.minecraft.world.item.Item item) {
        return CobbieTestSupport.cobbie(server)
                .map(c -> c.countItem(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(item).toString()))
                .orElse(-1);
    }

    private static int chestCount(TestServerContext server, BlockPos pos, net.minecraft.world.item.Item item) {
        return server.computeOnServer(s -> chestCount(s, pos, item));
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

    /** The Sharpness level on the sword he is carrying, or 0 when there is no enchanted sword. */
    private static int swordLevel(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
                ItemStack stack = cobbie.getInventoryContainer().getItem(i);
                if (stack.isEmpty() || !stack.is(Items.DIAMOND_SWORD)) continue;
                var enchantments = stack.get(DataComponents.ENCHANTMENTS);
                if (enchantments == null || enchantments.isEmpty()) continue;
                var sharpness = s.registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
                return enchantments.getLevel(sharpness);
            }
            return 0;
        });
    }

    private static ItemStack sharpnessBook(MinecraftServer server, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var sharpness = server.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        var mutable = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        mutable.set(sharpness, level);
        book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return book;
    }
}
