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
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * Asking for something by describing it, rather than naming a registry id.
 *
 * <p>He could always see that one pickaxe was worn and one was fresh; the action layer could only take an id, so
 * "my worst pickaxe" had no route. Three things are asserted here, and the second is the one that matters: a
 * description resolves to the right stack, an ambiguous description is <b>refused with the candidates listed</b>
 * rather than silently picked, and something he does not carry never resolves at all.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Describe}.
 */
public final class CobbieDescribeTest implements FabricClientGameTest {

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
                ItemStack worn = new ItemStack(Items.IRON_PICKAXE, 1);
                worn.setDamageValue(worn.getMaxDamage() - 3);
                ItemStack fresh = new ItemStack(Items.IRON_PICKAXE, 1);
                cobbie.addItem(fresh);
                cobbie.addItem(worn);
                return null;
            });

            // --- 1. "my worst pickaxe" is the worn one, not the first one in the bag. ---
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("get my worst pickaxe out", List.of(
                            new ActionIntent(ActionVocabulary.EQUIP, "worst pickaxe", 0, 0, 0, ""))));
            if (refusal != null) {
                throw new AssertionError("Equipping a described pickaxe was refused outright: " + refusal);
            }
            CobbieTestSupport.awaitPlanSettled(server, 600);
            int remaining = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return cobbie.getMainHandItem().is(Items.IRON_PICKAXE)
                        ? cobbie.getMainHandItem().getMaxDamage() - cobbie.getMainHandItem().getDamageValue()
                        : -1;
            });
            if (remaining != 3) {
                throw new AssertionError("He took the pickaxe with " + remaining + " uses left; \"worst\" means"
                        + " the one with the least, which has 3: " + lastReport(server));
            }

            // --- 2. Two swords and no way to tell them apart is a question, not a pick. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIAMOND_SWORD, 1));
                ItemStack sharp = new ItemStack(Items.DIAMOND_SWORD, 1);
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                mutable.set(s.registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 3);
                sharp.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
                cobbie.addItem(sharp);
                return null;
            });
            // Both swords match "diamond sword", so there is nothing to choose between them on those words.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("get my diamond sword",
                        List.of(new ActionIntent(ActionVocabulary.EQUIP, "diamond sword", 0, 0, 0, "")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String ambiguous = lastFailure(server);
            if (ambiguous == null || !ambiguous.toLowerCase().contains("which one")) {
                throw new AssertionError("Two sharp swords did not produce a question: " + lastReport(server));
            }
            if (!ambiguous.contains("diamond sword")) {
                throw new AssertionError("The refusal did not list the candidates: " + ambiguous);
            }

            // --- 3. Something he is not carrying never resolves. ---
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("get my netherite axe",
                        List.of(new ActionIntent(ActionVocabulary.EQUIP, "netherite_axe", 0, 0, 0, "")));
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String absent = lastFailure(server);
            if (absent == null || !absent.contains("netherite_axe")) {
                throw new AssertionError("An item he does not carry resolved to something: " + lastReport(server));
            }

            System.out.println("[gametest] describe ok: \"worst pickaxe\" took the worn one (" + remaining
                    + " uses), \"diamond sword\" (two of them) asked which one, and"
                    + " \"netherite_axe\" was refused as not carried");
            if (spawn == null) throw new AssertionError("no companion");
        }
    }

    /** The last plan's first failed step's reason. */
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
