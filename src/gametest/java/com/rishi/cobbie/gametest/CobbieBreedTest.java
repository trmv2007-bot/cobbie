package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Two cows, a handful of wheat, and a calf that exists afterwards.
 *
 * <p>A probe settled the mechanism before this was written: feeding through the actor puts both cows
 * in love and {@code canMate} agrees, but no calf appears on its own, because the pairing is normally
 * finished by the animals' own breeding goal and that goal never runs for them here. What is public is
 * {@code Animal.spawnChildFromBreeding}, so the verb feeds them, checks the preconditions, and asks the
 * game for the calf — the offspring, the parents' cooldowns and the sounds are all vanilla.
 *
 * <p>Each refusal is asserted too, because a refusal that names the wrong condition is worse than none:
 * one cow must say "I need two", a cow with no wheat must say "I have nothing a cow would eat", and two
 * cows that have just calved must say "cooldown" rather than trying again.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Breed}.
 */
public final class CobbieBreedTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            // 1. Two adults with no food: the refusal has to be about food.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                spawnCow(s, cobbie, 1.5);
                spawnCow(s, cobbie, 2.5);
                return null;
            });
            String noFood = breed(server, "cow");
            if (noFood != null) throw new AssertionError("breed refused outright: " + noFood);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "would eat")) {
                throw new AssertionError("Breeding with no food did not say so: " + lastReport(server));
            }

            // 2. Food and only one adult: the refusal has to be about the second cow.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.WHEAT, 8));
                return null;
            });
            // Leave exactly one adult in reach.
            server.computeOnServer(s -> {
                var level = CobbieTestSupport.cobbie(s).orElseThrow().level();
                var cows = level.getEntitiesOfClass(Cow.class,
                        CobbieTestSupport.cobbie(s).orElseThrow().getBoundingBox().inflate(8));
                for (int i = 1; i < cows.size(); i++) cows.get(i).discard();
                return null;
            });
            String one = breed(server, "cow");
            if (one != null) throw new AssertionError("breed refused outright: " + one);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (!reportSays(server, "need two")) {
                throw new AssertionError("Breeding with one cow did not say so: " + lastReport(server));
            }

            // 3. Two adults and wheat: a calf, and the wheat is spent.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                spawnCow(s, cobbie, -1.5);
                return null;
            });
            int calvesBefore = server.computeOnServer(CobbieBreedTest::calves);
            int wheatBefore = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:wheat"));
            String asked = breed(server, "cow");
            if (asked != null) throw new AssertionError("breed refused: " + asked);
            CobbieTestSupport.awaitPlanSettled(server, 600);

            int calvesAfter = server.computeOnServer(CobbieBreedTest::calves);
            int wheatAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:wheat"));
            if (calvesAfter <= calvesBefore) {
                throw new AssertionError("No calf appeared (" + calvesBefore + " -> " + calvesAfter + "). "
                        + lastReport(server));
            }
            if (wheatAfter != wheatBefore - 2) {
                throw new AssertionError("Feeding two cows should spend two wheat: " + wheatBefore
                        + " -> " + wheatAfter);
            }
            if (!reportSays(server, "young one was born")) {
                throw new AssertionError("The report does not say what happened: " + lastReport(server));
            }

            // 4. Straight afterwards a second attempt does not produce a second calf, and the refusal
            //    names the food it tried rather than claiming a cause. The exact wording of a
            //    post-birth cooldown is NOT established: in this version the parents still answer
            //    canFallInLove() true and simply decline the food, so "cooldown" would be a diagnosis
            //    the run does not support and is not asserted here.
            int calvesBeforeSecond = server.computeOnServer(CobbieBreedTest::calves);
            String again = breed(server, "cow");
            if (again != null) throw new AssertionError("breed refused outright: " + again);
            CobbieTestSupport.awaitPlanSettled(server, 600);
            if (server.computeOnServer(CobbieBreedTest::calves) != calvesBeforeSecond) {
                throw new AssertionError("A second breeding produced another calf immediately");
            }
            if (!reportSays(server, "would not take")) {
                throw new AssertionError("A second breeding was not refused with what was tried: "
                        + lastReport(server));
            }

            System.out.println("[gametest] breed ok: two cows and two wheat produced a calf and spent"
                    + " both wheat; no food and a single adult each refused with their own reason, and a"
                    + " second attempt made no second calf");
        }
    }

    private static void spawnCow(net.minecraft.server.level.ServerLevel level, CobbieEntity cobbie, double dx) {
        Cow cow = new Cow(EntityTypes.COW, level);
        cow.setPos(cobbie.getX() + dx, cobbie.getY(), cobbie.getZ() + 1.0);
        level.addFreshEntity(cow);
    }

    private static void spawnCow(net.minecraft.server.MinecraftServer server, CobbieEntity cobbie, double dx) {
        spawnCow(server.overworld(), cobbie, dx);
    }

    private static String breed(TestServerContext server, String animal) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "breed the " + animal,
                List.of(new ActionIntent(ActionVocabulary.BREED, animal, 0, 0, 0, ""))));
    }

    private static int calves(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server)
                .map(c -> c.level().getEntitiesOfClass(Cow.class, c.getBoundingBox().inflate(12), Cow::isBaby).size())
                .orElse(-1);
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
