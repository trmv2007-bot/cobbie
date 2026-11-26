package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.task.TaskPlan;
import com.rishi.cobbie.task.TaskStep;
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
import java.util.Locale;
import java.util.Set;

/**
 * The world changes while a plan is running, and every step has to re-check its own preconditions when it
 * starts — not at plan adoption, and not when the plan was offered.
 *
 * <p>Everything else in this suite proves a verb works in a world built to agree with it. A player's world
 * does not agree: another player closes the door he was heading for, breaks the log he was sent for, takes
 * the item out of the chest he is walking to, or builds on the square he was going to build on. Four
 * mutations in one world, one per case, each verified by <b>the world afterwards and the line he said</b>:
 *
 * <ol>
 *   <li><b>the destination stops being a place</b> — the square is filled with stone between adoption and
 *       the step's turn. He must not walk into it, must not retry it, and must say what changed: the step
 *       is <i>never attempted</i> (attempts 0), which is what tells the new path from the old one, where
 *       {@code startWalk} refused it after an attempt.</li>
 *   <li><b>the chest is emptied while he walks</b> — the step walks, and what it was sent for is gone by
 *       the time he gets there. He stops and names the coal, and does not claim the job.</li>
 *   <li><b>the square is built on while he walks</b> — the one case with an honest substitute: the same
 *       block, one square over. The block has to be there afterwards, at the new square, with the material
 *       spent exactly once.</li>
 *   <li><b>somebody else does the work</b> — the log is broken while he walks to it. That is not a
 *       completion he can claim and not a swing at air; he stops and says there is nothing left.</li>
 * </ol>
 *
 * <p>The three failure modes are asserted separately, because they are three different defects:
 * <b>looping</b> (the step's attempt count and the plan's retry count stay inside the budget, and the whole
 * thing settles inside a tick bound), <b>claiming completion</b> (the plan status is FAILED and he never
 * said "all done" in that window), and <b>silent stop</b> (the reason is in the recent-lines window, not
 * only in a field). His health is printed at every stage, as the terrain course does: a companion who died
 * on the way has passed nothing.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=StalePlan}.
 */
public final class CobbieStalePlanTest implements FabricClientGameTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED");
    /** How far from him every fixture sits: past his interaction reach, so the step has to walk. */
    private static final int AWAY = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // A project of his own would be a second plan in the window, and this class is about one plan at
            // a time; the switches below are the ones the four verbs need, no more.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "containers", true);
            awaitIdle(server, 400);

            // ---- 1. The destination stops being a place before the step runs. ----
            // The old rule asked once, at adoption; the square was free then and is stone when his turn
            // comes. The proof that the new path ran is attempts == 0: nothing was walked, because there
            // was no longer anywhere to walk to and the reason was read off the world.
            long beforeOne = latestId(server);
            BlockPos filledIn = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos spot = cobbie.blockPosition().offset(0, 0, AWAY);
                cobbie.offerPlan("walk over to the rise", List.of(new ActionIntent(
                        ActionVocabulary.WALK_TO, "", spot.getX(), spot.getY(), spot.getZ(), "")));
                CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + " minecraft:stone");
                CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + (spot.getY() + 1) + " "
                        + spot.getZ() + " minecraft:stone");
                return spot;
            });
            TaskPlan walked = settledPlan(server, beforeOne, "walk over to the rise", 600,
                    "The walk into a filled square never settled");
            assertStopped(server, walked, "cannot stand there",
                    "a walk whose destination was filled in", "all done");
            TaskStep walkStep = walked.steps().get(0);
            if (walkStep.attempts() != 0) {
                throw new AssertionError("The step was attempted " + walkStep.attempts() + " time(s) against a"
                        + " square that was stone before it started; the check is happening after the fact,"
                        + " not at the moment the step runs (" + walked.report() + ")");
            }
            alive(server, "1 the filled-in destination", filledIn);

            // ---- 2. The chest is emptied while he walks to it. ----
            awaitIdle(server, 400);
            long beforeTwo = latestId(server);
            BlockPos chest = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos at = cobbie.blockPosition().offset(0, 0, -AWAY);
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:chest");
                if (!(s.overworld().getBlockEntity(at) instanceof net.minecraft.world.Container container)) {
                    throw new AssertionError("Setup failed: no chest at " + at.toShortString());
                }
                container.setItem(0, new ItemStack(Items.COAL, 8));
                cobbie.offerPlan("get the coal out of that chest", List.of(new ActionIntent(
                        ActionVocabulary.TAKE, "minecraft:coal", at.getX(), at.getY(), at.getZ(), "8")));
                return at;
            });
            // He has to be on his way. `isBusy` is true from the moment a plan is adopted, so waiting on it
            // would put the change before the step's turn — case 1's event with a different object. His own
            // position is the only proof the walk actually started.
            walkedAway(server, beforeTwo);
            server.computeOnServer(s -> {
                if (s.overworld().getBlockEntity(chest) instanceof net.minecraft.world.Container container) {
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        container.setItem(slot, ItemStack.EMPTY);
                    }
                }
                return null;
            });
            TaskPlan took = settledPlan(server, beforeTwo, "get the coal out of that chest", 600,
                    "The take from an emptied chest never settled");
            assertStopped(server, took, "no coal", "a chest emptied on the way to it", "all done");
            if (took.status() == TaskPlan.Status.COMPLETED) {
                throw new AssertionError("An emptied chest was reported as a finished job: " + took.report());
            }
            alive(server, "2 the emptied chest", chest);

            // ---- 3. The square is built on while he walks, and there is somewhere else to put it. ----
            awaitIdle(server, 400);
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.DIRT, 4));
                return null;
            });
            int dirtBefore = carried(server, "minecraft:dirt");
            long beforeThree = latestId(server);
            BlockPos squatted = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos spot = cobbie.blockPosition().offset(AWAY, 0, 0);
                cobbie.offerPlan("put a block of dirt over there", List.of(new ActionIntent(
                        ActionVocabulary.PLACE, "minecraft:dirt", spot.getX(), spot.getY(), spot.getZ(), "")));
                return spot;
            });
            walkedAway(server, beforeThree);
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + squatted.getX() + " " + squatted.getY() + " "
                        + squatted.getZ() + " minecraft:stone");
                return null;
            });
            TaskPlan placed = settledPlan(server, beforeThree, "put a block of dirt over there", 900,
                    "The place onto a squatted square never settled");
            if (placed.status() != TaskPlan.Status.COMPLETED) {
                throw new AssertionError("A block he could still put down one square over was not put down: "
                        + placed.report() + "; he said " + said(server));
            }
            BlockPos endedAt = server.computeOnServer(s -> {
                for (BlockPos candidate : List.of(squatted.north(), squatted.south(), squatted.east(),
                        squatted.west(), squatted.above())) {
                    if (s.overworld().getBlockState(candidate).is(Blocks.DIRT)) return candidate;
                }
                return null;
            });
            if (endedAt == null) {
                throw new AssertionError("The plan completed without a dirt block at or beside "
                        + squatted.toShortString() + ": " + placed.report());
            }
            if (server.computeOnServer(s -> s.overworld().getBlockState(squatted).is(Blocks.DIRT))) {
                throw new AssertionError("The dirt went to the square that was already stone, so no replan"
                        + " happened: " + placed.report());
            }
            int spent = dirtBefore - carried(server, "minecraft:dirt");
            if (spent != 1) {
                throw new AssertionError("The replan spent " + spent + " dirt instead of exactly 1 (from "
                        + dirtBefore + " to " + carried(server, "minecraft:dirt") + ")");
            }
            require(placed.log().contains("replanned"), "The plan log does not record the replan: "
                    + placed.log());
            System.out.println("[stale] 3 squatted square: " + placed.status() + " — dirt now at "
                    + endedAt.toShortString() + " instead of " + squatted.toShortString() + ", spent " + spent
                    + "; log: " + placed.log());
            alive(server, "3 the squatted square", endedAt);

            // ---- 4. Somebody else does the work. ----
            awaitIdle(server, 400);
            long beforeFour = latestId(server);
            BlockPos wasLog = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos at = cobbie.blockPosition().offset(-AWAY, 0, 0);
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan("break that oak log for me", List.of(new ActionIntent(
                        ActionVocabulary.MINE, "minecraft:oak_log", at.getX(), at.getY(), at.getZ(), "")));
                return at;
            });
            walkedAway(server, beforeFour);
            // Broken by somebody else while he walks. There is no second oak log within reach of a
            // replan in a flat world, so the honest ending is to stop and say the log went.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + wasLog.getX() + " " + wasLog.getY() + " "
                        + wasLog.getZ() + " minecraft:air");
                return null;
            });
            TaskPlan mined = settledPlan(server, beforeFour, "break that oak log for me", 600,
                    "The mine of a log somebody else broke never settled");
            assertStopped(server, mined, "nothing left", "a log somebody else broke", "all done");
            if (server.computeOnServer(s -> !s.overworld().getBlockState(wasLog).isAir())) {
                throw new AssertionError("The log is still there at " + wasLog.toShortString()
                        + ", so the fixture did not run: " + mined.report());
            }
            alive(server, "4 the log somebody else broke", wasLog);

            System.out.println("[stale] done: one destination filled, one chest emptied, one square squatted"
                    + " and replanned, one log broken by somebody else — all four settled inside their bounds");
        }
    }

    // ==================================================================
    // The rule being asserted, in one place
    // ==================================================================

    /**
     * He stopped, he said why, and he did not claim the job.
     *
     * <p>Three separate defects, asserted separately: a plan that is COMPLETED over work he did not do (the
     * completion claim), a plan that is not finished at all (the loop), and a plan that stopped with the
     * reason nowhere in what he actually said (the silent stop) — the reason in a field is not a reason he
     * told anyone.
     */
    private static void assertStopped(TestServerContext server, TaskPlan plan, String cause, String what,
                                      String completionLine) {
        if (!plan.isFinished()) {
            throw new AssertionError("The plan never settled after " + what + " — that is the loop: "
                    + plan.report());
        }
        if (plan.status() == TaskPlan.Status.COMPLETED) {
            throw new AssertionError("A plan completed over work he did not do, after " + what + ": "
                    + plan.report());
        }
        String abort = plan.abortReason();
        if (!abort.toLowerCase(Locale.ROOT).contains(cause)) {
            throw new AssertionError("The plan stopped after " + what + " without naming it: abortReason=\""
                    + abort + "\", report=" + plan.report());
        }
        boolean said = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.saidRecently(cause)).orElse(false));
        if (!said) {
            throw new AssertionError("He stopped after " + what + " but never said why; he said "
                    + said(server));
        }
        // Since the causal line, not anywhere in the window: an earlier case in this class legitimately
        // finished its job and said so, and a window that still holds that line would score this case with
        // the previous one's words.
        String after = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> linesSince(c.recentLines(), cause)).orElse(""));
        if (after.contains(completionLine)) {
            throw new AssertionError("He claimed the job was done after " + what + "; he said " + said(server));
        }
        int attempts = plan.steps().stream().mapToInt(TaskStep::attempts).sum();
        if (attempts > 2 * plan.steps().size()) {
            throw new AssertionError("The step was retried " + attempts + " times over "
                    + plan.steps().size() + " step(s) after " + what + " — that is the loop: " + plan.report());
        }
        System.out.println("[stale] stopped after " + what + ": " + plan.status() + " — \"" + abort
                + "\" (attempts " + attempts + ", retries " + plan.totalRetries() + ")");
    }

    /** He is still here, still alive, and not stuck in a block. Printed every stage, as the terrain course does. */
    private static void alive(TestServerContext server, String stage, BlockPos where) {
        String reading = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElse(null);
            if (cobbie == null) return "gone";
            return cobbie.getHealth() + "/" + cobbie.getMaxHealth() + " at " + cobbie.blockPosition().toShortString()
                    + " busy=" + cobbie.isBusy() + " inWall=" + cobbie.isInWall();
        });
        if (reading.equals("gone")) {
            throw new AssertionError("He is not in any level after " + stage + " (the fixture was at "
                    + where.toShortString() + ")");
        }
        if (reading.startsWith("0.0") || reading.contains("inWall=true") || reading.contains("busy=true")) {
            throw new AssertionError("After " + stage + " he is " + reading
                    + "; the plan should have ended and he should be standing in the open");
        }
        System.out.println("[stale] " + stage + ": alive, health " + reading);
    }

    // ==================================================================
    // Finding the plan this case made, never whatever ran last
    // ==================================================================

    /**
     * Wait until the step has actually carried him somewhere.
     *
     * <p>{@code isBusy()} is true the tick a plan is adopted, before any step has begun, so a fixture that
     * waits on it changes the world before the walk rather than during it and quietly tests the wrong
     * moment. His own feet are the evidence that the walk started.
     */
    private static void walkedAway(TestServerContext server, long since) {
        BlockPos start = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> !c.blockPosition().equals(start)).orElse(false), 200);
        } catch (Throwable t) {
            throw new AssertionError("He never set off, so nothing was mid-walk to change; he is at "
                    + server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.blockPosition().toShortString() + " busy=" + c.isBusy()).orElse("gone"))
                    + "; plans: " + describe(server, since), t);
        }
    }

    private static long latestId(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
    }

    private static void awaitIdle(TestServerContext server, int ticks) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> !c.isBusy()).orElse(false), ticks);
        } catch (Throwable t) {
            throw new AssertionError("He never went idle between cases; he is busy with "
                    + server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::currentTaskDescription).orElse("nothing")), t);
        }
    }

    private static long findPlan(CobbieEntity cobbie, long since, String prefix) {
        for (long id = cobbie.latestPlanId(); id > since; id--) {
            String record = cobbie.planRecord(id);
            if (record != null && record.startsWith(prefix)) return id;
        }
        return -1;
    }

    /** The plan this case made, waited for to a terminal status by its own id. */
    private static TaskPlan settledPlan(TestServerContext server, long since, String prefix, int ticks,
                                        String why) {
        long id;
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> findPlan(c, since, prefix) >= 0).orElse(false), 200);
            id = server.computeOnServer(s -> findPlan(CobbieTestSupport.cobbie(s).orElseThrow(), since, prefix));
            server.waitFor(s -> {
                String record = CobbieTestSupport.cobbie(s).map(c -> c.planRecord(id)).orElse(null);
                return record != null && TERMINAL.contains(statusIn(record));
            }, ticks);
        } catch (Throwable t) {
            throw new AssertionError(why + "; plans after " + since + " read: " + describe(server, since), t);
        }
        TaskPlan plan = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().planAfter(id - 1));
        if (plan == null || plan.id() != id) {
            throw new AssertionError("Plan " + id + " is no longer remembered, so nothing can be said about it");
        }
        return plan;
    }

    private static String statusIn(String record) {
        int mark = record.indexOf(" -> ");
        if (mark < 0) return "";
        int end = record.indexOf(" — ", mark + 4);
        return record.substring(mark + 4, end < 0 ? record.length() : end);
    }

    private static String describe(TestServerContext server, long since) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElse(null);
            if (cobbie == null) return "no companion";
            StringBuilder sb = new StringBuilder();
            for (long id = since + 1; id <= cobbie.latestPlanId(); id++) {
                String record = cobbie.planRecord(id);
                if (record == null) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(record);
            }
            return sb.length() == 0 ? "(none)" : sb.toString();
        });
    }

    /** What he said from the line naming the change onwards — everything after the event, nothing before it. */
    private static String linesSince(List<String> recent, String cause) {
        int from = -1;
        for (int i = 0; i < recent.size(); i++) {
            if (recent.get(i).toLowerCase(Locale.ROOT).contains(cause)) from = i;
        }
        return String.join(" | ", recent.subList(from < 0 ? recent.size() : from, recent.size()));
    }

    private static String said(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.recentLines().toString()).orElse("nothing"));
    }

    private static int carried(TestServerContext server, String id) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(c -> c.countItem(id)).orElse(-1));
    }

    private static void require(boolean what, String why) {
        if (!what) throw new AssertionError(why);
    }
}
