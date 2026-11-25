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
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * "Put this book on my sword" — the description, the book, the levels, and the result.
 *
 * <p>This request used to be reported as a failure on a run that had worked: the anvil's output is taken from the
 * menu after it is cleared, so the actor's "remaining" was air while the sword in his bag carried the enchantment.
 * The verb now reads its success off what he carries, which is the same rule the item frame taught.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=AnvilEnd}.
 */
public final class CobbieAnvilEndTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "crafting", true);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setExperience(12, 0.0f);
                BlockPos feet = cobbie.blockPosition();
                CobbieTestSupport.command(s, "setblock " + feet.getX() + " " + (feet.getY() - 1) + " "
                        + feet.getZ() + " minecraft:anvil");
                cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
                ItemStack book = new ItemStack(Items.ENCHANTED_BOOK, 1);
                ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                stored.set(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.SHARPNESS), 3);
                book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
                cobbie.addItem(book);
                return null;
            });

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("put this book on my sword", List.of(
                            new ActionIntent(ActionVocabulary.ANVIL, "my sword", 0, 0, 0, ""))));
            if (refusal != null) {
                throw new AssertionError("The anvil plan was refused outright: " + refusal);
            }
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String report = lastReport(server);
            if (!report.contains("completed")) {
                throw new AssertionError("The work was reported as a failure: " + report);
            }

            String result = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (ItemStack stack : cobbie.bagContents()) {
                    if (!stack.is(Items.IRON_SWORD)) continue;
                    var ench = stack.get(DataComponents.ENCHANTMENTS);
                    return "enchanted=" + (ench != null) + " levels=" + cobbie.experienceLevel()
                            + " books=" + cobbie.countItem("minecraft:enchanted_book");
                }
                return "no sword left";
            });
            if (!result.startsWith("enchanted=true")) {
                throw new AssertionError("He said it worked but the sword is not enchanted: " + result);
            }
            if (!result.contains("books=0")) {
                throw new AssertionError("The book was not spent: " + result);
            }

            System.out.println("[gametest] anvil end ok: \"put this book on my sword\" completed — "
                    + result + " — " + report);

            // --- A book that cannot go on the item is refused with both names, not chosen and then rejected. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.BOW, 1));
                cobbie.addItem(book(s, net.minecraft.world.item.enchantment.Enchantments.FIRE_PROTECTION, 2));
                cobbie.offerPlan("put the fire protection book on the bow",
                        List.of(new ActionIntent(ActionVocabulary.ANVIL, "bow", 0, 0, 0, "")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String mismatch = lastFailure(server);
            if (mismatch == null || !mismatch.contains("will not go on") && !mismatch.contains("none of my books")) {
                throw new AssertionError("A book the item cannot take was not refused by name: "
                        + lastReport(server));
            }

            // --- Two books that both fit are a question, never a silent pick. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos feet = cobbie.blockPosition();
                cobbie.addItem(new ItemStack(Items.IRON_SWORD, 1));
                cobbie.addItem(book(s, net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 1));
                cobbie.addItem(book(s, net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 1));
                cobbie.offerPlan("put a sharpness book on that sword",
                        List.of(new ActionIntent(ActionVocabulary.ANVIL, "iron_sword", 0, 0, 0, "")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String question = lastFailure(server);
            if (question == null || !question.contains("which one")) {
                throw new AssertionError("Two equal books were not put to the owner as a question: "
                        + lastReport(server));
            }
            System.out.println("[gametest] anvil choice ok: a book the item cannot take refused by name, and"
                    + " two equal books asked as a question — " + question);
        }
    }

    private static ItemStack book(net.minecraft.server.MinecraftServer server,
            net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> which, int level) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK, 1);
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(which), level);
        stack.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        return stack;
    }

    private static String lastFailure(TestServerContext server) {
        return server.computeOnServer(s -> {
            var plan = CobbieTestSupport.cobbie(s).orElseThrow().lastPlan();
            if (plan == null) return null;
            for (var step : plan.steps()) {
                if (step.status() == com.rishi.cobbie.task.TaskStep.Status.FAILED) return step.result();
            }
            return null;
        });
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
