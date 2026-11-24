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

/**
 * An enchanted book onto a sword, at a real anvil.
 *
 * <p>The first anvil capability, and the one an anvil is mostly for. Three claims: the enchantment
 * lands on the item, the anvil's own level charge is paid, and the book is spent. A level-short
 * attempt is refused without inventing a number it cannot read.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Anvil}.
 */
public final class CobbieAnvilTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "crafting", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos anvil = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + anvil.getX() + " " + anvil.getY() + " "
                        + anvil.getZ() + " minecraft:anvil");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
                cobbie.addItem(sharpnessBook(s, 1));
                cobbie.setExperience(6, 0.0f);
                return null;
            });
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:enchanted_book") != 1)) {
                throw new AssertionError("Setup failed: no enchanted book in his bag");
            }

            // 1. Level-short: refused, and the refusal says what it can without inventing a price.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().setExperience(0, 0.0f);
                return null;
            });
            String refusal = anvil(server);
            if (refusal != null) throw new AssertionError("anvil refused outright: " + refusal);
            CobbieTestSupport.awaitPlanSettled(server, 200);
            if (!reportSays(server, "level")) {
                throw new AssertionError("A refused anvil use did not mention levels: " + lastReport(server));
            }

            // 2. Funded: the book goes on the sword.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().setExperience(6, 0.0f);
                return null;
            });
            int levelBefore = server.computeOnServer(CobbieAnvilTest::level);
            String again = anvil(server);
            if (again != null) throw new AssertionError("anvil refused: " + again);
            CobbieTestSupport.awaitPlanSettled(server, 300);

            boolean enchanted = server.computeOnServer(CobbieAnvilTest::swordEnchanted);
            int levelAfter = server.computeOnServer(CobbieAnvilTest::level);
            int books = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:enchanted_book"));
            if (!enchanted) throw new AssertionError("The sword gained no enchantment. " + lastReport(server));
            if (books != 0) throw new AssertionError("The book was not spent: " + books + " left");
            if (levelAfter >= levelBefore) {
                throw new AssertionError("The anvil charged nothing: " + levelBefore + " -> " + levelAfter
                        + ". " + lastReport(server));
            }

            System.out.println("[gametest] anvil ok: book applied, level " + levelBefore + " -> " + levelAfter
                    + ", book spent; a level-short attempt was refused");
        }
    }

    /**
     * A sharpness book, built through the enchantment API.
     *
     * <p>Not with {@code /enchant}: on a held book that command leaves an *empty* stored-enchantment map,
     * which the anvil then refuses — and a presence-only assertion on the component passes anyway. The
     * oracle that proved this was a real player at the same anvil getting no result from that book and
     * one from this one.
     */
    private static ItemStack sharpnessBook(MinecraftServer server, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var lookup = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var sharpness = lookup.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        var mutable = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        mutable.set(sharpness, level);
        book.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        var stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) {
            throw new AssertionError("Setup failed: the book carries no enchantment");
        }
        return book;
    }

    private static String anvil(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "put the book on my sword",
                List.of(new ActionIntent(ActionVocabulary.ANVIL, "iron_sword", 0, 0, 0, ""))));
    }

    private static int level(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::experienceLevel).orElse(-1);
    }

    private static boolean swordEnchanted(MinecraftServer server) {
        var cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return false;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.IRON_SWORD)) continue;
            if (stack.get(DataComponents.ENCHANTMENTS) != null) return true;
        }
        return false;
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT)
                .contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
