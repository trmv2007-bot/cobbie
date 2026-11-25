package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.skill.SkillExecutor;
import com.rishi.cobbie.skill.SkillRegistry;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * An adversarial run: a task in flight, and the world changed underneath it.
 *
 * <p>Every other class proves a verb. None of them proves what happens when navigation, inventory and the world move
 * while a plan is running — which is the thing a player hits in their first hour. The rule this asserts is the one
 * that matters most: **he finishes, or he stops and says precisely what stopped him. He never reports completion he
 * did not achieve.**
 *
 * <p>Both halves are asserted. The success of a failure is not the sentence alone — a run that dies silently is the
 * other half of the same defect, so a stopped plan must fail <em>and</em> name the block that disappeared.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Adversity}.
 */
public final class CobbieAdversityTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);
            CobbieTestSupport.command(server, "cobbie stay");

            // --- 1. The block he was going to mine is taken away mid-run. ---
            BlockPos target = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(3, 0, 3);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan("mine that oak log", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
                return base;
            });
            // The world changes while he walks: the log is gone before the swing lands.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + target.getX() + " " + target.getY() + " "
                        + target.getZ() + " minecraft:air");
                return null;
            });
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String vanishedState = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            String vanishedReport = lastReport(server);
            if ("COMPLETED".equals(vanishedState)) {
                throw new AssertionError("He reported completion for a block that was removed before he reached"
                        + " it: " + vanishedReport);
            }
            if (!vanishedReport.contains("oak_log") && !vanishedReport.contains("nothing at")
                    && !vanishedReport.toLowerCase(java.util.Locale.ROOT).contains("air")) {
                throw new AssertionError("He stopped, but did not say what stopped him: " + vanishedReport);
            }
            System.out.println("[adversity] vanished block: " + vanishedState + " — " + vanishedReport);

            // --- 2. A wall goes up between him and the block while he is on his way. ---
            //
            // The first version of this obstacle took his pickaxe away, and he mined the stone anyway: in creative a
            // bare hand breaks stone, and the drop still comes off the loot table. Two facts worth recording — a
            // running step's tool lives in his HAND, not his bag, and creative makes "no tool" a non-obstacle. So the
            // obstacle is a real one: a wall he has to path around.
            BlockPos walled = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(-4, 0, 4);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:stone");
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        if (dx == 0 && base.getX() + dx == base.getX()) continue;   // leave the block itself
                        CobbieTestSupport.command(s, "setblock " + (base.getX() + 2 + dx) + " "
                                + (base.getY() + dy) + " " + base.getZ() + " minecraft:cobblestone");
                    }
                }
                cobbie.offerPlan("mine the stone behind that wall", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", base.getX(), base.getY(),
                                base.getZ(), "")));
                return base;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);
            String wallState = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            String wallReport = lastReport(server);
            boolean stoneLeft = server.computeOnServer(s -> s.overworld().getBlockState(walled)
                    .is(Blocks.STONE));
            if ("COMPLETED".equals(wallState) && stoneLeft) {
                throw new AssertionError("He reported completing a mine and the stone is still there: "
                        + wallReport);
            }
            if (!"COMPLETED".equals(wallState) && stoneLeft
                    && !wallReport.toLowerCase(java.util.Locale.ROOT).contains("reach")
                    && !wallReport.toLowerCase(java.util.Locale.ROOT).contains("stand")
                    && !wallReport.toLowerCase(java.util.Locale.ROOT).contains("far")
                    && !wallReport.toLowerCase(java.util.Locale.ROOT).contains("stuck")) {
                throw new AssertionError("He stopped at the wall without saying what stopped him: " + wallReport);
            }
            System.out.println("[adversity] wall in the path: " + wallState + " stone left=" + stoneLeft + " — "
                    + wallReport);

            // --- 3. A skill replayed against surroundings that no longer offer what it needs. ---
            String skillName = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(2, 0, -3);
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + i) + " " + base.getY() + " "
                            + base.getZ() + " minecraft:oak_log");
                }
                List<ActionIntent> steps = new java.util.ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    steps.add(new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX() + i,
                            base.getY(), base.getZ(), ""));
                }
                cobbie.offerPlan("gather the oak logs", steps);
                return "gather-the-oak-logs";
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);
            String learned = server.computeOnServer(s -> SkillRegistry.get(skillName).isPresent()
                    ? "learned" : "not learned");
            if (!"learned".equals(learned)) {
                System.out.println("[adversity] skill: not learned, so the replay case is not measurable here");
            } else {
                // Every log in sight is gone: the replay must refuse and say what it is missing, not swing at air.
                server.computeOnServer(s -> {
                    for (int dx = -8; dx <= 8; dx++) {
                        for (int dz = -8; dz <= 8; dz++) {
                            for (int dy = -2; dy <= 3; dy++) {
                                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                                BlockPos p = cobbie.blockPosition().offset(dx, dy, dz);
                                if (s.overworld().getBlockState(p).is(Blocks.OAK_LOG)) {
                                    CobbieTestSupport.command(s, "setblock " + p.getX() + " " + p.getY() + " "
                                            + p.getZ() + " minecraft:air");
                                }
                            }
                        }
                    }
                    return null;
                });
                String outcome = server.computeOnServer(s -> SkillExecutor
                        .run(CobbieTestSupport.cobbie(s).orElseThrow(), skillName).message());
                if (!outcome.toLowerCase(java.util.Locale.ROOT).contains("see none")
                        && !outcome.toLowerCase(java.util.Locale.ROOT).contains("nothing")) {
                    throw new AssertionError("A skill replayed with nothing to work on said: " + outcome);
                }
                System.out.println("[adversity] stale skill: " + outcome);
            }

            System.out.println("[adversity] done");
        }
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(TaskPlan::report).orElse("no plan"));
    }
}
