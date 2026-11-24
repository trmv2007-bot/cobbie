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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * A diamond pickaxe into a netherite one, at a real smithing table.
 *
 * <p>The claim that matters is not "he made a netherite pickaxe" — it is that the pickaxe that comes
 * out is <em>the one he was carrying</em>: its name, its enchantment and its wear on it. Smiting
 * that quietly hands back a fresh pickaxe would pass a "did a netherite pickaxe appear" assertion and
 * would have destroyed the owner's work, so the assertions are on the components.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Smith}.
 */
public final class CobbieSmithTest implements FabricClientGameTest {

    private static final String NAME = "Rishi's Pick";
    private static final int DAMAGE = 240;

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
                        + table.getZ() + " minecraft:smithing_table");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(wornPickaxe(s));
                cobbie.addItem(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1));
                cobbie.addItem(new ItemStack(Items.NETHERITE_INGOT, 1));
                cobbie.addItem(new ItemStack(Items.COBBLESTONE, 1));
                return null;
            });

            // 1. Something the table has no recipe for is refused, and costs nothing.
            String refused = smith(server, "cobblestone");
            if (refused != null) throw new AssertionError("smith refused outright: " + refused);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "would upgrade")) {
                throw new AssertionError("An item with no smithing recipe was not refused with the reason: "
                        + lastReport(server));
            }
            if (server.computeOnServer(CobbieSmithTest::templates) != 1) {
                throw new AssertionError("The refused smith spent his template anyway");
            }

            // 2. Template, diamond pickaxe and netherite ingot: the same pickaxe, in netherite.
            String asked = smith(server, "diamond_pickaxe");
            if (asked != null) throw new AssertionError("smith refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            String result = server.computeOnServer(CobbieSmithTest::describeResult);
            if (!result.equals("ok")) throw new AssertionError(result + ". " + lastReport(server));
            if (server.computeOnServer(CobbieSmithTest::templates) != 0) {
                throw new AssertionError("The upgrade template was not spent");
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:netherite_ingot")) != 0) {
                throw new AssertionError("The netherite ingot was not spent");
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:diamond_pickaxe")) != 0) {
                throw new AssertionError("He kept the diamond pickaxe as well as the netherite one");
            }

            System.out.println("[gametest] smith ok: diamond_pickaxe -> netherite_pickaxe keeping the name"
                    + " \"" + NAME + "\", efficiency 3 and " + DAMAGE + " damage; template and ingot spent;"
                    + " an item with no smithing recipe was refused and cost nothing");
        }
    }

    /**
     * A diamond pickaxe that is recognisably the owner's: named, enchanted, and already worn. Every
     * one of those has to survive the upgrade or the result is a different pickaxe.
     */
    private static ItemStack wornPickaxe(MinecraftServer server) {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal(NAME));
        var efficiency = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.EFFICIENCY);
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(efficiency, 3);
        pick.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        pick.setDamageValue(DAMAGE);
        return pick;
    }

    private static String smith(TestServerContext server, String target) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "upgrade my " + target,
                List.of(new ActionIntent(ActionVocabulary.SMITH, target, 0, 0, 0, ""))));
    }

    private static int templates(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server)
                .map(c -> c.countItem("minecraft:netherite_upgrade_smithing_template")).orElse(-1);
    }

    /** "ok", or a sentence naming what was wrong with the pickaxe that came out. */
    private static String describeResult(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return "no companion";
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.NETHERITE_PICKAXE)) continue;
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null || !NAME.equals(name.getString())) {
                return "the netherite pickaxe lost its name (was " + (name == null ? "none" : name.getString()) + ")";
            }
            ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
            int level = enchants == null ? 0 : enchants.getLevel(
                    server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.EFFICIENCY));
            if (level != 3) return "efficiency came out at " + level + " instead of 3";
            if (stack.getDamageValue() != DAMAGE) {
                return "the wear was reset: " + stack.getDamageValue() + " instead of " + DAMAGE;
            }
            return "ok";
        }
        return "no netherite pickaxe in his bag";
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
