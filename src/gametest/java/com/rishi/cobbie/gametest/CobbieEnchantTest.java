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
 * Enchanting at a real table, on his own levels.
 *
 * <p>The first capability that drives a menu rather than a recipe, so the assertions are about the
 * menu's own accounting: an enchantment appears on the item, his level drops by what the table charged,
 * the lapis is spent — and with too few levels he is refused with the numbers rather than quietly
 * producing something.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Enchant}.
 */
public final class CobbieEnchantTest implements FabricClientGameTest {

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
                        + table.getZ() + " minecraft:enchanting_table");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIAMOND_SWORD, 1));
                cobbie.addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
                cobbie.addExperience(40);
                return null;
            });
            int levelBefore = server.computeOnServer(CobbieEnchantTest::level);
            if (levelBefore <= 0) throw new AssertionError("Setup failed: he has no levels to spend");

            // 1. Level-short: refused with the numbers, and the plan must be *finished* before the next
            // stage, or its queued retry races the assertions.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().setExperience(0, 0.0f);
                return null;
            });
            String refusal = enchant(server);
            if (refusal != null) throw new AssertionError("enchant refused outright: " + refusal);
            waitForPlanSettled(server, 200);
            if (!reportSays(server, "levels")) {
                throw new AssertionError("A refused enchant did not name the level cost: " + lastReport(server));
            }

            // 2. Funded: the sword gains an enchantment and the table takes its charge.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().setExperience(levelBefore, 0.0f);
                return null;
            });
            String again = enchant(server);
            if (again != null) throw new AssertionError("enchant refused: " + again);
            waitForPlanSettled(server, 300);

            int levelAfter = server.computeOnServer(CobbieEnchantTest::level);
            int lapisAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:lapis_lazuli"));
            boolean enchanted = server.computeOnServer(CobbieEnchantTest::anySwordEnchanted);
            if (!enchanted) {
                throw new AssertionError("Nothing was enchanted. " + lastReport(server));
            }
            if (levelAfter >= levelBefore) {
                throw new AssertionError("He was not charged any levels: before=" + levelBefore
                        + " after=" + levelAfter + " report=" + lastReport(server));
            }
            if (lapisAfter >= 3) {
                throw new AssertionError("No lapis was spent: still carrying " + lapisAfter);
            }

            System.out.println("[gametest] enchant ok: sword enchanted for " + (levelBefore - levelAfter)
                    + " level(s), level " + levelBefore + " -> " + levelAfter + ", lapis 3 -> " + lapisAfter
                    + "; a level-short request was refused with the numbers");
        }
    }

    /** Wait until the plan in flight has finished, so the next stage cannot interleave with it. */
    private static void waitForPlanSettled(TestServerContext server, int ticks) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.lastPlan() == null || c.lastPlan().isFinished())
                    .orElse(false), ticks);
        } catch (Throwable t) {
            throw new AssertionError("The plan never settled: " + server.computeOnServer(s ->
                    CobbieTestSupport.cobbie(s).map(c -> c.lastPlan() == null ? "none"
                            : c.lastPlan().status().toString()).orElse("no cobbie")), t);
        }
    }

    private static String enchant(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "enchant my sword",
                List.of(new ActionIntent(ActionVocabulary.ENCHANT, "diamond_sword", 0, 0, 0, "low"))));
    }

    private static int level(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::experienceLevel).orElse(-1);
    }

    private static boolean anySwordEnchanted(MinecraftServer server) {
        var cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return false;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.DIAMOND_SWORD)) continue;
            if (stack.get(DataComponents.ENCHANTMENTS) != null) return true;
        }
        return false;
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
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
