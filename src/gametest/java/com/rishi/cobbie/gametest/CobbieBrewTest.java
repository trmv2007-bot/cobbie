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
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.List;

/**
 * A brewing stand, and the bottles that come out of it changed.
 *
 * <p>Four claims, and each of them is read back off the world: a thing the stand will not take is
 * refused and costs nothing, the stand is loaded and left to work, the bottles in his bag are
 * awkward potions afterwards, and the reagent and the bottles are gone from the bag because they
 * went into the stand rather than being duplicated.
 *
 * <p>The first refusal is deliberately {@code dirt} and not "a brewing ingredient that does not
 * match": the first run used a glistering melon slice against a water bottle, expecting a refusal,
 * and the game quietly brewed a <em>mundane</em> potion — a real recipe in 26.3. The probe was
 * wrong, not the stand.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Brew}.
 */
public final class CobbieBrewTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "containers", true);
            CobbieTestSupport.permit(server, "crafting", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos stand = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + stand.getX() + " " + stand.getY() + " "
                        + stand.getZ() + " minecraft:brewing_stand");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (int i = 0; i < 3; i++) cobbie.addItem(PotionContents.createItemStack(Items.POTION, Potions.WATER));
                cobbie.addItem(new ItemStack(Items.NETHER_WART, 2));
                cobbie.addItem(new ItemStack(Items.GLISTERING_MELON_SLICE, 1));
                cobbie.addItem(new ItemStack(Items.BLAZE_POWDER, 1));
                // Something a stand will not take, so the refusal has a real item to name.
                cobbie.addItem(new ItemStack(Items.DIRT, 1));
                return null;
            });
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .getInventoryContainer().getContainerSize() < 1)) {
                throw new AssertionError("Setup failed: his bag is not there");
            }
            if (server.computeOnServer(CobbieBrewTest::waterBottles) != 3) {
                throw new AssertionError("Setup failed: he is not carrying three water bottles");
            }

            // 1. Something the stand will not take is refused, and costs nothing.
            String refused = brew(server, "dirt");
            if (refused != null) throw new AssertionError("brew refused outright: " + refused);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "will not take")) {
                throw new AssertionError("A reagent the stand cannot use was not refused with the reason: "
                        + lastReport(server));
            }
            if (server.computeOnServer(CobbieBrewTest::waterBottles) != 3) {
                throw new AssertionError("The refused brew spent his bottles anyway");
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:nether_wart")) != 2) {
                throw new AssertionError("The refused brew spent something out of his bag");
            }

            // 2. Nether wart into water bottles: awkward potions, out of a real stand.
            String asked = brew(server, "nether_wart");
            if (asked != null) throw new AssertionError("brew refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 1600);

            int awkward = server.computeOnServer(CobbieBrewTest::awkwardPotions);
            int water = server.computeOnServer(CobbieBrewTest::waterBottles);
            int wart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:nether_wart"));
            int leftInStand = server.computeOnServer(s -> bottlesLeftInStand(s, stand));

            if (awkward != 3) {
                throw new AssertionError("Expected three awkward potions, found " + awkward + ". "
                        + lastReport(server));
            }
            if (water != 0) throw new AssertionError("The water bottles are still there: " + water);
            if (wart != 1) throw new AssertionError("The stand did not spend exactly one nether wart: " + wart);
            if (leftInStand != 0) {
                throw new AssertionError("He left " + leftInStand + " bottle(s) in the stand");
            }

            // 3. A finished brew has to leave him free. A job that reports success without clearing
            // the step in flight poisons the next one — a walk-then-anvil plan failed both steps with
            // "lost track of the brewing" and "still busy with walk-to" until this was fixed.
            String next = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "step aside",
                    List.of(new ActionIntent(ActionVocabulary.WALK_TO, "", start.getX() + 2, start.getY(),
                            start.getZ(), ""))));
            if (next != null) throw new AssertionError("He was still busy after a finished brew: " + next);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            String afterBrew = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(afterBrew)) {
                throw new AssertionError("The first step after a brew did not run (" + afterBrew + "): "
                        + lastReport(server));
            }

            System.out.println("[gametest] brew ok: 3 water bottles -> 3 awkward potions in a real stand,"
                    + " one nether wart spent, the stand emptied; a reagent the stand will not take was"
                    + " refused and cost nothing; and a plan right after a brew ran instead of hitting a"
                    + " job that had already finished");
        }
    }

    private static String brew(TestServerContext server, String reagent) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "brew with " + reagent,
                List.of(new ActionIntent(ActionVocabulary.BREW, reagent, 0, 0, 0, ""))));
    }

    /** Potions in his bag whose contents are water, whatever the item count says. */
    private static int waterBottles(MinecraftServer server) {
        return countPotion(server, Potions.WATER);
    }

    private static int awkwardPotions(MinecraftServer server) {
        return countPotion(server, Potions.AWKWARD);
    }

    private static int countPotion(MinecraftServer server, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return -1;
        int found = 0;
        for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
            ItemStack stack = cobbie.getInventoryContainer().getItem(i);
            if (stack.isEmpty()) continue;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(potion)) found += stack.getCount();
        }
        return found;
    }

    /** Bottles the stand is still holding, which is the difference between "took them out" and "they vanished". */
    private static int bottlesLeftInStand(MinecraftServer server, BlockPos pos) {
        var level = server.overworld();
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand)) {
            return -1;
        }
        int found = 0;
        for (int slot = 0; slot < 3; slot++) {
            if (!stand.getItem(slot).isEmpty()) found+= stand.getItem(slot).getCount();
        }
        return found;
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
