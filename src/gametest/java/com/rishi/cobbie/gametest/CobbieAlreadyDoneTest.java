package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

import java.util.List;

/**
 * What "the world has already done it" is allowed to mean.
 *
 * <p>{@code checkStep} — {@code alreadySatisfied} until 0.78 — was added in 0.75.0 so a project that went stale
 * while it queued would close as done
 * instead of swinging at a target the world had removed. It was answering <b>yes</b> for a walk aimed at solid
 * stone — *"nothing left to do — the world had already done that"* about a destination he could never occupy. That is
 * a false completion claim, which is the one failure this project exists to prevent.
 *
 * <p>The rule, asserted here directly against the predicate and then through a plan:
 * <b>the check may answer yes only for an objective whose satisfaction is observable and specific</b> — a block that
 * is now the target state, a thing that is now gone. Anything without such a predicate answers <b>unknown</b>, which
 * means the step runs and fails with its own reason.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=AlreadyDone}.
 */
public final class CobbieAlreadyDoneTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            // Nothing of his own may run while the predicate is being judged.
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.command(server, "cobbie autonomy assistive");

            // --- 1. A walk into solid stone is not "already done". ---
            BlockPos wall = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                // His own feet, one block east: inside the 1.5 blocks the old predicate read as "he is there",
                // and a block he cannot ever stand in.
                BlockPos at = cobbie.blockPosition().east();
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:stone");
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + (at.getY() + 1) + " " + at.getZ()
                        + " minecraft:stone");
                return at;
            });
            boolean withinReachOfTheStone = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .blockPosition().closerThan(wall, 1.5));
            if (!withinReachOfTheStone) {
                throw new AssertionError("Fixture: he is not within 1.5 blocks of the stone, so the bug's trigger"
                        + " is not present");
            }
            boolean satisfiedAboutStone = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .checkStep(new ActionIntent(ActionVocabulary.WALK_TO, "", wall.getX(), wall.getY(),
                            wall.getZ(), "")).isSatisfied());
            if (satisfiedAboutStone) {
                throw new AssertionError("A walk into solid stone at " + wall.toShortString()
                        + " was reported as already satisfied; the honest answer is that it cannot be stood in");
            }

            long before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk into that stone", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            wall.getX(), wall.getY(), wall.getZ(), ""))));
            if (refusal != null) {
                throw new AssertionError("The walk was refused outright instead of being attempted: " + refusal);
            }
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.lastPlan() != null
                        && c.lastPlan().id() > before && c.lastPlan().isFinished()).orElse(false), 400);
            } catch (Throwable t) {
                throw new AssertionError("The walk never settled; the last plan is "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.lastPlan() == null ? "none" : c.planRecord(c.lastPlan().id()))
                        .orElse("no companion")), t);
            }
            TaskPlan walked = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().lastPlan());
            if (walked == null || walked.id() <= before) {
                throw new AssertionError("The walk produced no plan, so nothing was tested");
            }
            if (walked.status() == TaskPlan.Status.COMPLETED) {
                throw new AssertionError("A walk into solid stone completed: " + walked.report());
            }
            if (!walked.report().contains("cannot stand there")) {
                throw new AssertionError("The walk failed for some other reason than the destination: "
                        + walked.report());
            }
            System.out.println("[already-done] walk into solid stone: " + walked.status() + " — \""
                    + walked.report() + "\"");

            // --- 2. A verb with no satisfaction predicate is never reported satisfied. ---
            // Judged on the predicate directly, without a plan, so the rule holds even for kinds no current verb
            // happens to exercise: a step whose goal nothing in the world can express must run and fail its own way.
            List<String> noPredicate = List.of(ActionVocabulary.ATTACK, ActionVocabulary.COLLECT,
                    ActionVocabulary.CRAFT, ActionVocabulary.USE, ActionVocabulary.EQUIP, ActionVocabulary.DROP,
                    ActionVocabulary.CHAT, ActionVocabulary.WAIT);
            List<String> claimed = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return noPredicate.stream().filter(verb -> cobbie.checkStep(new ActionIntent(verb,
                        "minecraft:oak_log", wall.getX(), wall.getY(), wall.getZ(), "")).isSatisfied()).toList();
            });
            if (!claimed.isEmpty()) {
                throw new AssertionError("These verbs were reported as already satisfied with no predicate to"
                        + " justify it: " + claimed);
            }
            // The control: an objective whose satisfaction IS observable still answers yes, so the test cannot
            // pass by answering no to everything.
            boolean mineAirIsSatisfied = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .checkStep(new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log",
                            wall.getX(), wall.getY() + 5, wall.getZ(), "")).isSatisfied());
            if (!mineAirIsSatisfied) {
                throw new AssertionError("The positive control failed: mining open air is a kind whose goal the"
                        + " world can be asked about, and it answered no");
            }
            System.out.println("[already-done] verbs judged: " + noPredicate.size()
                    + " with no predicate, all unknown; the observable control answered yes");
        }
    }
}
