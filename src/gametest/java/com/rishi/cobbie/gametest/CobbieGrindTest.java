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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * The enchantments coming off something at a real grindstone.
 *
 * <p>The claim is the absence of content, not the presence of a component: the sword that comes back
 * must have <em>no</em> enchantments on it, and must still be the same worn sword — the trap the
 * anvil slice fell into was asserting that a component existed when what mattered was what was in it,
 * so here the assertion is that the enchantment map is gone and the damage is untouched.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Grind}.
 */
public final class CobbieGrindTest implements FabricClientGameTest {

    private static final int DAMAGE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "crafting", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos stone = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + stone.getX() + " " + stone.getY() + " "
                        + stone.getZ() + " minecraft:grindstone");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(enchantedSword(s));
                cobbie.addItem(new ItemStack(Items.STONE_PICKAXE, 1));
                return null;
            });

            // 1. Something with no enchantment on it has nothing to take off, and is said so.
            String refused = grind(server, "stone_pickaxe");
            if (refused != null) throw new AssertionError("grind refused outright: " + refused);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "no enchanted")) {
                throw new AssertionError("An unenchanted item was not refused with the reason: "
                        + lastReport(server));
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:stone_pickaxe")) != 1) {
                throw new AssertionError("The refused grind ate the pickaxe");
            }

            // 2. The sword comes back bare, and still the same sword.
            String asked = grind(server, "iron_sword");
            if (asked != null) throw new AssertionError("grind refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            String result = server.computeOnServer(CobbieGrindTest::describeSword);
            if (!result.equals("ok")) throw new AssertionError(result + ". " + lastReport(server));
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:iron_sword")) != 1) {
                throw new AssertionError("He ended up with something other than one iron sword");
            }

            System.out.println("[gametest] grind ok: an enchanted iron sword came back with no enchantments"
                    + " and its " + DAMAGE + " damage untouched; an unenchanted pickaxe was refused with"
                    + " the reason and kept");
        }
    }

    /** A worn iron sword with one enchantment on it, built through the enchantment API. */
    private static ItemStack enchantedSword(MinecraftServer server) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD, 1);
        var sharpness = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(sharpness, 2);
        sword.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        sword.setDamageValue(DAMAGE);
        if (!net.minecraft.world.item.enchantment.EnchantmentHelper.hasAnyEnchantments(sword)) {
            throw new AssertionError("Setup failed: the sword carries no enchantment");
        }
        return sword;
    }

    private static String grind(TestServerContext server, String target) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "take the enchantments off my " + target,
                List.of(new ActionIntent(ActionVocabulary.GRIND, target, 0, 0, 0, ""))));
    }

    /** "ok", or a sentence naming what is still wrong with the sword in his bag. */
    private static String describeSword(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return "no companion";
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.IRON_SWORD)) continue;
            ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
            if (enchants != null && !enchants.isEmpty()) {
                return "the sword still carries " + enchants + " instead of a bare one";
            }
            if (stack.getDamageValue() != DAMAGE) {
                return "the wear changed: " + stack.getDamageValue() + " instead of " + DAMAGE;
            }
            return "ok";
        }
        return "no iron sword in his bag";
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
