package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * The audit's one real find: a verb must spend the stack it chose, not the first stack that shares its id.
 *
 * <p>{@code drop} and {@code give} picked the <em>last</em> matching stack and then spent by registry id,
 * which {@code consumeItem} resolves from the <em>first</em> matching slot. With a plain sword and an
 * enchanted one in the bag, he could hand over the enchanted one and quietly spend the plain one — one
 * enchanted sword becoming two. The check is conservation: the world holds exactly one enchanted sword
 * before and after each verb, wherever it ends up.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Spend}.
 */
public final class CobbieSpendTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieEntity spawn = CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");

            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                ResourceKey<Enchantment> sharpness = Enchantments.SHARPNESS;
                ItemStack plain = new ItemStack(Items.DIAMOND_SWORD, 1);
                ItemStack enchanted = new ItemStack(Items.DIAMOND_SWORD, 1);
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                mutable.set(s.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(sharpness), 3);
                enchanted.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
                cobbie.addItem(plain);
                cobbie.addItem(enchanted);
                return null;
            });
            if (!swordCounts(server).equals("1enchanted/2total")) {
                throw new AssertionError("Test setup failed: " + swordCounts(server));
            }

            // --- 1. Dropping one sword must not sink the enchantment into the ground for free. ---
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("drop a sword", List.of(
                        new ActionIntent(ActionVocabulary.DROP, "minecraft:diamond_sword", 0, 0, 0, "1")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String afterDrop = swordCounts(server);
            if (!"1enchanted/2total".equals(afterDrop)) {
                throw new AssertionError("After dropping one sword the world holds " + afterDrop
                        + " (expected 1 enchanted of 2 total) — the chosen stack and the spent stack"
                        + " disagreed: " + lastReport(server));
            }

            // --- 2. Giving one away must hand over the same sword it spent. ---
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("give me a sword", List.of(
                        new ActionIntent(ActionVocabulary.GIVE, "minecraft:diamond_sword", 0, 0, 0, "")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String afterGive = swordCounts(server);
            if (!"1enchanted/2total".equals(afterGive)) {
                throw new AssertionError("After giving one sword away the world holds " + afterGive
                        + " (expected 1 enchanted of 2 total): " + lastReport(server));
            }

            System.out.println("[gametest] spend ok: enchantments conserved across drop and give — "
                    + afterDrop + ", then " + afterGive + " (bag + ground + owner)");
            if (spawn == null) throw new AssertionError("no companion");
        }
    }

    /** "Ne enchanted/Ntotal" across his bag, the ground and the owner's inventory. */
    private static String swordCounts(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            int enchanted = 0;
            int total = 0;
            for (ItemStack stack : cobbie.bagContents()) {
                if (stack.isEmpty() || !stack.is(Items.DIAMOND_SWORD)) continue;
                total += stack.getCount();
                if (stack.get(DataComponents.ENCHANTMENTS) != null
                        && !stack.get(DataComponents.ENCHANTMENTS).isEmpty()) enchanted += stack.getCount();
            }
            var owner = CobbieTestSupport.owner(s);
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
                ItemStack stack = owner.getInventory().getItem(i);
                if (stack.isEmpty() || !stack.is(Items.DIAMOND_SWORD)) continue;
                total += stack.getCount();
                if (stack.get(DataComponents.ENCHANTMENTS) != null
                        && !stack.get(DataComponents.ENCHANTMENTS).isEmpty()) enchanted += stack.getCount();
            }
            for (var pile : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(cobbie.blockPosition()).inflate(8))) {
                ItemStack stack = pile.getItem();
                if (!stack.is(Items.DIAMOND_SWORD)) continue;
                total += stack.getCount();
                if (stack.get(DataComponents.ENCHANTMENTS) != null
                        && !stack.get(DataComponents.ENCHANTMENTS).isEmpty()) enchanted += stack.getCount();
            }
            return enchanted + "enchanted/" + total + "total";
        });
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
