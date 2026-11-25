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
            // Placing is default-deny; without this the torch case tests the permission gate instead of the desync.
            CobbieTestSupport.permit(server, "building", true);
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

            // --- 3. A skill learned cleanly, then replayed after the world moved on. ---
            //
            // This case was "not measurable" in the first version because the gather did not complete, so no skill
            // existed to replay. It is now a hard requirement: a clean gather, a learned skill, and both failures
            // reported as failures of the *test* rather than skipped.
            CobbieTestSupport.awaitPlanSettled(server, 600);
            BlockPos pile = server.computeOnServer(s -> {
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
                return base;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);
            String gatherReport = lastReport(server);
            if (!gatherReport.contains("completed")) {
                throw new AssertionError("The learning gather did not complete, so the replay case cannot be"
                        + " measured at all: " + gatherReport);
            }
            String skillName = "gather-the-oak-logs";
            boolean learned = server.computeOnServer(s -> SkillRegistry.get(skillName).isPresent());
            if (!learned) {
                throw new AssertionError("A clean three-step gather on three logs learned no skill called \""
                        + skillName + "\", so a replay cannot be tested: " + gatherReport);
            }

            // The world moves on: every log in sight goes, which is what a player does between two runs of a habit.
            int removed = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                int gone = 0;
                for (int dx = -12; dx <= 12; dx++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        for (int dy = -2; dy <= 3; dy++) {
                            BlockPos p = cobbie.blockPosition().offset(dx, dy, dz);
                            if (s.overworld().getBlockState(p).is(Blocks.OAK_LOG)) {
                                CobbieTestSupport.command(s, "setblock " + p.getX() + " " + p.getY() + " "
                                        + p.getZ() + " minecraft:air");
                                gone++;
                            }
                        }
                    }
                }
                return gone;
            });
            // What the replay is aimed at, and what the world looks like when it is aimed: the failure worth
            // catching is a replay that succeeds against the wrong world, which no "completed" assertion sees.
            String aimed = server.computeOnServer(s -> {
                var skill = SkillRegistry.get(skillName).orElseThrow();
                StringBuilder steps = new StringBuilder();
                for (var step : skill.steps()) {
                    if (steps.length() > 0) steps.append(", ");
                    steps.append(step.summary());
                }
                int logs = 0;
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (int dx = -12; dx <= 12; dx++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        for (int dy = -2; dy <= 3; dy++) {
                            if (s.overworld().getBlockState(cobbie.blockPosition().offset(dx, dy, dz))
                                    .is(Blocks.OAK_LOG)) logs++;
                        }
                    }
                }
                return steps + " | oak logs in a 12-block cube: " + logs;
            });
            System.out.println("[adversity] replay aimed at: " + aimed);
            String replay = server.computeOnServer(s -> SkillExecutor
                    .run(CobbieTestSupport.cobbie(s).orElseThrow(), skillName).message());
            String replayLower = replay.toLowerCase(java.util.Locale.ROOT);
            if (!replayLower.contains("see none") && !replayLower.contains("nothing") && !replayLower.contains("cannot see")) {
                throw new AssertionError("A skill replayed with " + removed + " logs removed said: " + replay);
            }
            System.out.println("[adversity] stale skill (" + removed + " logs removed): " + replay);

            // --- 4. A slot is emptied between the plan reading it and the step taking it. ---
            BlockPos torchAt = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.TORCH, 1));
                BlockPos p = cobbie.blockPosition().offset(-2, 0, -2);
                cobbie.offerPlan("put a torch down there", List.of(
                        new ActionIntent(ActionVocabulary.PLACE, "minecraft:torch", p.getX(), p.getY(),
                                p.getZ(), "")));
                // The desync: the plan has accepted the step, and the torch is taken before it runs.
                cobbie.consumeItem("minecraft:torch", 1);
                return p;
            });
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String torchState = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            String torchReport = lastReport(server);
            boolean torchPlaced = server.computeOnServer(s -> s.overworld().getBlockState(torchAt)
                    .is(Blocks.TORCH));
            if ("COMPLETED".equals(torchState) && !torchPlaced) {
                throw new AssertionError("He reported placing a torch that is not there: " + torchReport);
            }
            if (!"COMPLETED".equals(torchState) && torchPlaced) {
                throw new AssertionError("He reported failing and the torch is there: " + torchReport);
            }
            if (!"COMPLETED".equals(torchState)
                    && !torchReport.toLowerCase(java.util.Locale.ROOT).contains("torch")) {
                throw new AssertionError("The torch went missing and he did not name it: " + torchReport);
            }
            System.out.println("[adversity] emptied slot: " + torchState + " placed=" + torchPlaced + " — "
                    + torchReport);

            // --- 5. Real terrain: a fence line whose only way through is a closed door. ---
            BlockPos beyond = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(6, 0, 0);
                // A line of fence either side of a closed door, straight across his path.
                for (int dz = -2; dz <= 2; dz++) {
                    String block = dz == 0 ? "minecraft:oak_door[facing=east,half=lower,open=false,hinge=left]"
                            : "minecraft:oak_fence";
                    CobbieTestSupport.command(s, "setblock " + (base.getX() - 1) + " " + base.getY() + " "
                            + (base.getZ() + dz) + " " + block);
                    if (dz == 0) {
                        CobbieTestSupport.command(s, "setblock " + (base.getX() - 1) + " " + (base.getY() + 1) + " "
                                + (base.getZ() + dz) + " minecraft:oak_door[facing=east,half=upper,open=false,hinge=left]");
                    }
                }
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:stone");
                cobbie.offerPlan("mine the stone past the fence", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", base.getX(), base.getY(),
                                base.getZ(), "")));
                return base;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1800);
            String fenceState = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            String fenceReport = lastReport(server);
            boolean stoneBeyond = server.computeOnServer(s -> s.overworld().getBlockState(beyond)
                    .is(Blocks.STONE));
            if ("COMPLETED".equals(fenceState) && stoneBeyond) {
                throw new AssertionError("He reported mining through a fence and the stone is still there: "
                        + fenceReport);
            }
            if (!"COMPLETED".equals(fenceState) && stoneBeyond
                    && !fenceReport.toLowerCase(java.util.Locale.ROOT).contains("reach")
                    && !fenceReport.toLowerCase(java.util.Locale.ROOT).contains("stand")
                    && !fenceReport.toLowerCase(java.util.Locale.ROOT).contains("stuck")
                    && !fenceReport.toLowerCase(java.util.Locale.ROOT).contains("far")) {
                throw new AssertionError("He stopped at a fence without saying what stopped him: " + fenceReport);
            }
            System.out.println("[adversity] fence and door: " + fenceState + " stone left=" + stoneBeyond + " — "
                    + fenceReport);

            System.out.println("[adversity] done");
        }
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(TaskPlan::report).orElse("no plan"));
    }
}
