package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.opportunity.Opportunity;
import com.rishi.cobbie.opportunity.OpportunityAssessor;
import com.rishi.cobbie.opportunity.OpportunityCycle;
import com.rishi.cobbie.opportunity.OpportunitySelector;
import com.rishi.cobbie.opportunity.Shortlist;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A project of his own starts, runs and finishes — when he is allowed one and something can be started.
 *
 * <p>Since 0.88.0 the choice is the model's, so <b>this class no longer waits for a project to appear by
 * itself</b>: with no model at 11434 nothing appears, which is the correct fail-closed answer and is
 * asserted in {@code CobbieOpportunityTest}. What is asserted here is the half that does not need a
 * model: the shortlist comes from the world, an answer through the shipped parser and dispatcher starts
 * the work, and the work reaches a terminal state with the world actually changed.
 *
 * <p>Three phases. The negative first: in ASSISTIVE nothing is started even with a pile in sight, because
 * the cycle that offers his own work is gated on creative autonomy. Then the pile — a shortlist, a
 * decision, a gather that empties the logs. Then the cairn — material in his bag, a decision, blocks on the
 * ground and fewer in the bag.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Initiative}.
 */
public final class CobbieInitiativeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);

            // --- 1. ASSISTIVE starts nothing. ---
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + 1 + i) + " " + base.getY() + " "
                            + (base.getZ() + 2) + " minecraft:oak_log");
                }
                return null;
            });
            int plansBefore = server.computeOnServer(CobbieInitiativeTest::planCount);
            int assistiveTicks = CobbieTestSupport.elapsed(server, 400);
            int plansAfter = server.computeOnServer(CobbieInitiativeTest::planCount);
            if (plansAfter != plansBefore) {
                throw new AssertionError("He started " + (plansAfter - plansBefore) + " project(s) in"
                        + " ASSISTIVE over " + assistiveTicks + " ticks, which is exactly what assistive"
                        + " means it must not do: " + lastReport(server));
            }
            System.out.println("[initiative] assistive: nothing started over " + assistiveTicks
                    + " ticks with a pile in sight");

            // --- 2. The pile, chosen through the shipped decision path. ---
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            Shortlist list = server.computeOnServer(s -> OpportunityAssessor.assess(
                    CobbieTestSupport.cobbie(s).orElseThrow()));
            String gatherId = firstOfKind(list, Opportunity.Kind.MINE);
            if (gatherId == null) {
                throw new AssertionError("A pile of three logs beside him was not offered at all: "
                        + list.describe());
            }
            String request = server.computeOnServer(s -> list.byId(gatherId).request());
            String refusal = server.computeOnServer(s -> OpportunityCycle.apply(
                    CobbieTestSupport.cobbie(s).orElseThrow(), list,
                    OpportunitySelector.parse(list, "{\"choice\":\"" + gatherId + "\"}")));
            if (refusal != null) throw new AssertionError("The chosen gather did not start: " + refusal);
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String status = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                throw new AssertionError("The project never reached a terminal state (" + status + "): "
                        + lastReport(server));
            }
            int logsLeft = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int left = 0;
                for (int i = 0; i < 3; i++) {
                    if (s.overworld().getBlockState(new BlockPos(base.getX() + 1 + i, base.getY(),
                            base.getZ() + 2)).is(Blocks.OAK_LOG)) left++;
                }
                return left;
            });
            if (logsLeft != 0) {
                throw new AssertionError("He reported a finished gather but " + logsLeft
                        + " log(s) are still standing: " + lastReport(server));
            }
            System.out.println("[initiative] gather: chose " + gatherId + ", ran \"" + request + "\" and"
                    + " reached " + status + " — " + lastReport(server));

            // --- 3. A project whose shape he decides, from material he carries. ---
            // Building is default-deny like every other switch, so the owner allows it first.
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.COBBLESTONE, 8));
                return null;
            });
            CobbieTestSupport.permit(server, "building", true);
            Shortlist buildList = server.computeOnServer(s -> OpportunityAssessor.assess(
                    CobbieTestSupport.cobbie(s).orElseThrow()));
            String buildId = firstOfKind(buildList, Opportunity.Kind.BUILD);
            if (buildId == null) {
                throw new AssertionError("With a stack of cobblestone in his bag and flat ground beside him"
                        + " no cairn was offered: " + buildList.describe());
            }
            String buildRequest = server.computeOnServer(s -> buildList.byId(buildId).request());
            String buildRefusal = server.computeOnServer(s -> OpportunityCycle.apply(
                    CobbieTestSupport.cobbie(s).orElseThrow(), buildList,
                    OpportunitySelector.parse(buildList, "{\"choice\":\"" + buildId + "\"}")));
            if (buildRefusal != null) throw new AssertionError("The chosen cairn did not start: " + buildRefusal);
            CobbieTestSupport.awaitPlanSettled(server, 900);
            String buildStatus = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(buildStatus)) {
                throw new AssertionError("His own build never finished (" + buildStatus + "): "
                        + lastReport(server));
            }
            int placed = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int found = 0;
                for (BlockPos at : BlockPos.betweenClosed(base.offset(-4, -1, -4), base.offset(4, 3, 4))) {
                    if (s.overworld().getBlockState(at).is(Blocks.COBBLESTONE)) found++;
                }
                return found;
            });
            if (placed < 3) {
                throw new AssertionError("He reported a finished cairn but only " + placed
                        + " cobblestone block(s) stand: " + lastReport(server));
            }
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countOf("minecraft:cobblestone"));
            if (carried > 5) {
                throw new AssertionError("He says he built a cairn but still carries " + carried
                        + " cobblestone — nothing was spent: " + lastReport(server));
            }
            System.out.println("[initiative] build: chose " + buildId + ", ran \"" + buildRequest + "\" — "
                    + placed + " cobblestone standing, " + carried + " left in the bag");
        }
    }

    private static String firstOfKind(Shortlist list, Opportunity.Kind kind) {
        for (Opportunity o : list.items()) {
            if (o.kind() == kind) return o.id();
        }
        return null;
    }

    /** How many plans have been created: only ever grows when something is handed to the runner. */
    private static int planCount(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(c -> {
            TaskPlan plan = c.lastPlan();
            return plan == null ? 0 : (int) plan.id();
        }).orElse(-1);
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.request() + " " + plan.status() + ": " + plan.report())
                .orElse("no plan"));
    }
}
