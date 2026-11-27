package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.config.CobbieConfig;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The inversion, in a live world: a shortlist nobody wrote by hand, a choice that is the only thing that
 * decides, and a WAIT that is honoured.
 *
 * <p>Four things the brief asks for and this class proves in one world:
 *
 * <ol>
 *   <li><b>a live world produces a shortlist</b> — a scene with a pile of logs, cobblestone in his bag, an
 *       iron ingot on the ground, ripe wheat, a zombie already coming for him, a second hostile merely in
 *       sight, the owner twenty blocks off and ground he has not walked produces five or more candidates,
 *       every one of them re-validated here by the same gate its own first step will run;</li>
 *   <li><b>nothing is offered that cannot start</b> — three stone blocks with no pickaxe in the bag
 *       produce no {@code mine} candidate at all, because {@code canStartMining} says no and the assessor
 *       never mentions what it cannot begin;</li>
 *   <li><b>WAIT is honoured</b> — a rejection through the real parser starts no plan and moves no block;</li>
 *   <li><b>choosing B runs B, not A</b> — the second candidate on the list is chosen, and the work that
 *       runs is the cairn rather than the logs; then, from a freshly assessed list, the logs are chosen
 *       and they are what comes down. Both halves from the same scene, so a preference for the first
 *       candidate would fail one of them.</li>
 * </ol>
 *
 * <p><b>What the harness cannot drive honestly, and what stands in for it.</b> The model itself: an 8B
 * model at 11434 is not deterministic enough to pick B because a test wants B, and the suite must run with
 * no model at all. So the model's answer is injected as the JSON text it would send, at exactly the
 * boundary it would cross: {@link OpportunitySelector#parse} then {@link OpportunityCycle#apply}. Those
 * are the shipped parser and dispatcher; only the socket is missing. What that leaves unproven is stated
 * plainly: that lfm2.5, handed this shortlist, picks sensibly. That needs a live session and is measured
 * by nothing in this repository.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Opportunity}.
 */
public final class CobbieOpportunityTest implements FabricClientGameTest {

    /** The logged stone squares: the suppression phase checks the shortlist never mentions them. */
    private static BlockPos[] stones = new BlockPos[3];

    /**
     * The four log squares, recorded once.
     *
     * <p>Read by absolute coordinate rather than by an offset from his feet: he walks. A fight in the
     * first phase moved him four blocks, and "the logs are gone" was really "he is standing somewhere
     * else" — a test defect that looked exactly like a run of the code under test.
     */
    private static BlockPos[] logs = new BlockPos[4];

    /** Where he was spawned, so a fight in the shortlist phase can be undone before the plan phases. */
    private static BlockPos home;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "collection", true);
            CobbieTestSupport.permit(server, "exploration", true);
            CobbieTestSupport.permit(server, "combat", true);
            // His own cycle stands down for the whole run: every decision here is injected, so nothing else
            // can start a plan underneath an assertion. The cycle's own gates are asserted in phase 5 and in
            // OpportunityCadenceTest.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.command(server, "gamerule advanceTime false");
            CobbieTestSupport.command(server, "time set midnight");

            // ---- The scene: one of everything he could start. ----
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos feet = cobbie.blockPosition();
                home = feet.immutable();
                for (int i = 0; i < 4; i++) {
                    logs[i] = feet.offset(3 + i, 0, 2);
                    CobbieTestSupport.command(s, "setblock " + logs[i].getX() + " " + logs[i].getY() + " "
                            + logs[i].getZ() + " minecraft:oak_log");
                }
                cobbie.addItem(new ItemStack(Items.COBBLESTONE, 8));
                ItemEntity ingot = new ItemEntity(s.overworld(), feet.getX() + 0.5, feet.getY(),
                        feet.getZ() - 3.5, new ItemStack(Items.IRON_INGOT, 1));
                s.overworld().addFreshEntity(ingot);
                CobbieTestSupport.command(s, "setblock " + (feet.getX() + 2) + " " + (feet.getY() - 1) + " "
                        + feet.getZ() + " minecraft:farmland[moisture=7]");
                CobbieTestSupport.command(s, "setblock " + (feet.getX() + 2) + " " + feet.getY() + " "
                        + feet.getZ() + " minecraft:wheat[age=7]");
                // One hostile already coming for him, one merely in sight: protect and fight are different
                // facts, and a count of hostiles would call them the same.
                CobbieTestSupport.command(s, "summon minecraft:zombie " + (feet.getX() + 5 + 0.5) + " "
                        + feet.getY() + " " + (feet.getZ() + 1 + 0.5) + " {NoAI:1b}");
                CobbieTestSupport.command(s, "summon minecraft:zombie " + (feet.getX() - 6 + 0.5) + " "
                        + feet.getY() + " " + (feet.getZ() - 1 + 0.5) + " {NoAI:1b}");
                return null;
            });
            CobbieTestSupport.teleportOwner(server, 20, 0, 0);
            CobbieTestSupport.elapsed(server, 20);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                // The nearer zombie is set on him: threatTier 1, which is what "already coming for me" means.
                s.overworld().getEntitiesOfClass(Zombie.class, new AABB(cobbie.blockPosition()).inflate(8))
                        .stream()
                        .min((a, b) -> Double.compare(a.distanceToSqr(cobbie), b.distanceToSqr(cobbie)))
                        .ifPresent(z -> z.setTarget(cobbie));
                return null;
            });
            CobbieTestSupport.elapsed(server, 10);

            // ---- 1. A live world produces a shortlist, and every candidate can start. ----
            Shortlist list = assess(server);
            if (list.size() < 5) {
                throw new AssertionError("A scene with logs, cobblestone, a dropped item, ripe wheat, two hostiles,"
                        + " a distant owner and unvisited ground produced only " + list.size() + " opportunit"
                        + (list.size() == 1 ? "y" : "ies") + ": " + list.describe());
            }
            if (list.size() > Shortlist.MAX) {
                throw new AssertionError("The shortlist is longer than the cap of " + Shortlist.MAX);
            }
            StringBuilder kinds = new StringBuilder();
            for (Opportunity o : list.items()) {
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(o.kind().label());
            }
            System.out.println("[opportunity] shortlist (" + list.size() + "): " + kinds);
            assertEveryCandidateStarts(server, list);

            // The same shortlist, as the owner can see it. Instrumentation is only instrumentation if it
            // reads back: the command has to name the ids the model is choosing between and the choice
            // that was made last.
            String shown = CobbieTestSupport.captureCommand(server, "cobbie opportunities");
            if (!shown.contains("op-1") || !shown.contains("op-" + list.size())) {
                throw new AssertionError("/cobbie opportunities did not list the shortlist: " + shown);
            }
            if (!shown.contains("Offered now (" + list.size() + ")")) {
                throw new AssertionError("/cobbie opportunities miscounted the shortlist: " + shown);
            }
            System.out.println("[opportunity] /cobbie opportunities read back " + list.size() + " candidates and"
                    + " the last decision");

            // ---- 2. Nothing is offered that cannot start. ----
            // Three stone blocks with no pickaxe in the bag: the drop gate refuses every one of them, so the
            // only honest shortlist neither mentions them nor pads the gap with something unstartable.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos feet = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    stones[i] = feet.offset(-2 - i, 0, 3);
                    CobbieTestSupport.command(s, "setblock " + stones[i].getX() + " " + stones[i].getY() + " "
                            + stones[i].getZ() + " minecraft:stone");
                }
                return null;
            });
            Shortlist withStone = assess(server);
            for (Opportunity o : withStone.items()) {
                for (ActionIntent step : o.steps()) {
                    for (BlockPos stone : stones) {
                        if (step.x() == stone.getX() && step.y() == stone.getY() && step.z() == stone.getZ()) {
                            throw new AssertionError("He offered to mine stone he cannot break without a"
                                    + " pickaxe: " + o.brief());
                        }
                    }
                }
            }
            System.out.println("[opportunity] stone without a pickaxe was not offered, and the shortlist was not"
                    + " padded to reach a number: " + withStone.describe());
            server.computeOnServer(s -> {
                for (BlockPos stone : stones) {
                    CobbieTestSupport.command(s, "setblock " + stone.getX() + " " + stone.getY() + " "
                            + stone.getZ() + " minecraft:air");
                }
                return null;
            });

            // The hostiles have done their job. A fight in the next phase would be a second reason for him
            // to move, and the plan phases below are about the plans, not about the combat goals.
            CobbieTestSupport.command(server, "kill @e[type=minecraft:zombie]");
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setTarget(null);
                return null;
            });
            CobbieTestSupport.stand(server, home);
            CobbieTestSupport.elapsed(server, 20);

            // ---- 3. WAIT is honoured: no plan, no block moved. ----
            Shortlist quiet = assess(server);
            int plansBeforeWait = planCount(server);
            int logsBeforeWait = logsStanding(server);
            String waited = server.computeOnServer(s -> OpportunityCycle.apply(CobbieTestSupport.cobbie(s).orElseThrow(),
                    quiet, OpportunitySelector.parse(quiet, "{\"choice\":\"wait\",\"why\":\"just keeping you company\"}")));
            if (waited == null || !waited.startsWith("waiting")) {
                throw new AssertionError("A WAIT was not treated as one: " + waited);
            }
            CobbieTestSupport.elapsed(server, 60);
            if (planCount(server) != plansBeforeWait) {
                throw new AssertionError("A WAIT started a plan anyway: " + lastReport(server));
            }
            if (logsStanding(server) != logsBeforeWait) {
                throw new AssertionError("A WAIT moved blocks: " + (logsBeforeWait - logsStanding(server))
                        + " log(s) are gone");
            }
            if (!OpportunityCycle.lastDecision().contains("wait")) {
                throw new AssertionError("The decision log did not record the wait: "
                        + OpportunityCycle.lastDecision());
            }
            System.out.println("[opportunity] wait honoured: \"" + OpportunityCycle.lastDecision() + "\"");

            // ---- 4. Choosing B runs B, and then choosing A runs A. ----
            Shortlist first = assess(server);
            String buildId = firstOfKind(first, Opportunity.Kind.BUILD);
            String mineId = firstOfKind(first, Opportunity.Kind.MINE);
            if (buildId == null || mineId == null) {
                throw new AssertionError("The scene has both a pile to take and material to build with, but the"
                        + " shortlist offered " + first.describe());
            }
            int mineAt = first.ids().indexOf(mineId);
            int buildAt = first.ids().indexOf(buildId);
            if (buildAt <= mineAt) {
                throw new AssertionError("The build candidate is not behind the mine candidate on the list"
                        + " (" + first.describe() + "), so choosing it would not prove position was ignored");
            }
            String buildRequest = server.computeOnServer(s -> first.byId(buildId).request());
            int logsBeforeBuild = logsStanding(server);
            chooseThat(server, first, buildId);
            awaitSettled(server, 1200, "The chosen build never settled");
            String builtRequest = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().request());
            if (!buildRequest.equals(builtRequest)) {
                throw new AssertionError("Choosing " + buildId + " ran \"" + builtRequest + "\" instead of \""
                        + buildRequest + "\"");
            }
            if (logsStanding(server) != logsBeforeBuild) {
                throw new AssertionError("The cairn was chosen but the logs came down: the first candidate ran");
            }
            int placed = cobblePlaced(server);
            if (placed < 3) {
                throw new AssertionError("The chosen build reported finished with only " + placed
                        + " cobblestone standing: " + lastReport(server));
            }
            System.out.println("[opportunity] choosing the second candidate ran it: \"" + builtRequest + "\" — "
                    + placed + " cobblestone standing, " + logsStanding(server) + " log(s) untouched");

            // The other half of the same scene: a fresh shortlist, and the pile is chosen this time.
            Shortlist second = assess(server);
            String mineAgain = firstOfKind(second, Opportunity.Kind.MINE);
            if (mineAgain == null) {
                throw new AssertionError("The pile is still standing but is no longer offered: "
                        + second.describe());
            }
            String mineRequest = server.computeOnServer(s -> second.byId(mineAgain).request());
            chooseThat(server, second, mineAgain);
            awaitSettled(server, 1600, "The chosen gather never settled");
            String minedRequest = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().request());
            if (!mineRequest.equals(minedRequest)) {
                throw new AssertionError("Choosing " + mineAgain + " ran \"" + minedRequest + "\" instead of \""
                        + mineRequest + "\"");
            }
            int logsAfter = logsStanding(server);
            if (logsAfter >= logsBeforeBuild) {
                throw new AssertionError("The gather reported finished but " + logsAfter + " of "
                        + logsBeforeBuild + " log(s) are still standing: " + lastReport(server));
            }
            System.out.println("[opportunity] choosing the pile afterwards ran it: \"" + minedRequest + "\" — "
                    + logsAfter + " of " + logsBeforeBuild + " log(s) left");

            // ---- 5. A brain that cannot answer means WAIT, never a candidate of the assessor's. ----
            noBrainMeansWait(server, second);
        }
    }

    /**
     * The same shortlist, a brain that cannot be reached, and no plan at the end of it.
     *
     * <p>This is the regression that keeps the inversion honest: a fallback to the first candidate would be
     * indistinguishable from a model that chose, and it would put the assessor back in charge. The live
     * brain is pointed at a closed port for the length of the check and restored from the config file after,
     * so what runs is the real request path, failing for the real reason.
     */
    private static void noBrainMeansWait(TestServerContext server, Shortlist list) {
        int before = planCount(server);
        String live = CobbieBrain.client() == null ? "none" : CobbieBrain.client().endpoint();
        CobbieBrain.configure("http://127.0.0.1:9/v1", "no-model", "", "openai", 0.4, 256, 1, 1, 0);
        CompletableFuture<OpportunitySelector.Decision> pending = server.computeOnServer(s ->
                OpportunitySelector.ask(CobbieTestSupport.cobbie(s).orElseThrow(), list));
        OpportunitySelector.Decision decision;
        try {
            decision = pending.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("The request neither answered nor failed inside 20s: " + e, e);
        } finally {
            CobbieConfig.reload();
        }
        if (!decision.isWait()) {
            throw new AssertionError("With no brain reachable he picked " + decision.opportunityId()
                    + " — that is the assessor deciding");
        }
        if (decision.origin() != OpportunitySelector.Origin.FALLBACK_REJECTED) {
            throw new AssertionError("A failed request was recorded as " + decision.origin()
                    + ", which would hide it in a session log");
        }
        if (planCount(server) != before) {
            throw new AssertionError("A failed choice started a plan: " + lastReport(server));
        }
        System.out.println("[opportunity] no brain (" + live + "): WAIT \"" + decision.why()
                + "\" and no plan; " + list.size() + " candidate(s) were offered and none was taken");
    }

    // ---- helpers ----

    /** Offer a raw model answer through the shipped parser and dispatcher, as the live path does. */
    private static void chooseThat(TestServerContext server, Shortlist list, String id) {
        String refusal = server.computeOnServer(s -> OpportunityCycle.apply(CobbieTestSupport.cobbie(s).orElseThrow(),
                list, OpportunitySelector.parse(list, "{\"choice\":\"" + id + "\"}")));
        if (refusal != null) throw new AssertionError("Choosing " + id + " did not start: " + refusal);
    }

    private static Shortlist assess(TestServerContext server) {
        return server.computeOnServer(s -> OpportunityAssessor.assess(CobbieTestSupport.cobbie(s).orElseThrow()));
    }

    private static String firstOfKind(Shortlist list, Opportunity.Kind kind) {
        for (Opportunity o : list.items()) {
            if (o.kind() == kind) return o.id();
        }
        return null;
    }

    /**
     * Re-run the executor's own gate over every step of every candidate, from outside the assessor.
     *
     * <p>Deliberately a second reading of the same facts rather than a copy of the assessor's logic: this
     * asks the entity, block by block, whether the work could start. If the assessor ever offers something
     * the verb would refuse, one of these lines fails.
     */
    private static void assertEveryCandidateStarts(TestServerContext server, Shortlist list) {
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            for (Opportunity o : list.items()) {
                int index = 0;
                for (ActionIntent step : o.steps()) {
                    if (!CobbieConfig.permits(step.action())) {
                        throw new AssertionError(o.id() + " offers " + step.action() + ", which the config forbids");
                    }
                    BlockPos at = new BlockPos(step.x(), step.y(), step.z());
                    switch (o.kind()) {
                        case MINE -> {
                            if (!cobbie.canStartMining(at)) {
                                throw new AssertionError(o.id() + " offers to mine " + at.toShortString()
                                        + ", which he cannot start");
                            }
                        }
                        case BUILD -> {
                            boolean ok = index == 0
                                    ? cobbie.canPlace(step.target(), at)
                                    : cobbie.canPlaceOnOwn(step.target(), at);
                            if (!ok) {
                                throw new AssertionError(o.id() + " offers to build at " + at.toShortString()
                                        + " with " + step.target() + ", which he cannot do");
                            }
                        }
                        case RETURN -> {
                            if (index == 0 && !cobbie.canWalkTo(at)) {
                                throw new AssertionError(o.id() + " offers a walk to " + at.toShortString()
                                        + " he cannot reach");
                            }
                        }
                        case PROTECT, FIGHT -> {
                            if (cobbie.threatsNear(16) == 0) {
                                throw new AssertionError(o.id() + " offers a fight with nothing hostile in sight");
                            }
                        }
                        case REPLENISH -> {
                            if (cobbie.nearestLooseItem() == null) {
                                throw new AssertionError(o.id() + " offers to pick up something that is not there");
                            }
                        }
                        case FARM -> {
                            if (cobbie.ripeCropsNear(4) <= 0) {
                                throw new AssertionError(o.id() + " offers a harvest with nothing ripe");
                            }
                        }
                        case FIND -> {
                            if (!CobbieConfig.permits("explore")) {
                                throw new AssertionError(o.id() + " offers exploring, which is switched off");
                            }
                        }
                        default -> { }
                    }
                    index++;
                }
            }
            return null;
        });
    }

    private static void awaitSettled(TestServerContext server, int ticks, String what) {
        try {
            CobbieTestSupport.awaitPlanSettled(server, ticks);
        } catch (Throwable t) {
            throw new AssertionError(what + ": " + lastReport(server), t);
        }
    }

    private static int planCount(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(c -> {
            TaskPlan plan = c.lastPlan();
            return plan == null ? 0 : (int) plan.id();
        }).orElse(-1));
    }

    private static int logsStanding(TestServerContext server) {
        return server.computeOnServer(s -> {
            int standing = 0;
            for (BlockPos log : logs) {
                if (s.overworld().getBlockState(log).is(Blocks.OAK_LOG)) standing++;
            }
            return standing;
        });
    }

    private static int cobblePlaced(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos feet = cobbie.blockPosition();
            int found = 0;
            for (BlockPos at : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 3, 4))) {
                if (s.overworld().getBlockState(at).is(Blocks.COBBLESTONE)) found++;
            }
            return found;
        });
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.request() + " " + plan.status() + ": " + plan.report())
                .orElse("no plan"));
    }
}
