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
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The autonomous project lifecycle: interrupted, impossible, stale, and not silently repeated.
 *
 * <p>The fifth failure a player meets, and the one that leaves no trace when it goes wrong — a project that vanishes
 * without a word, or one that starts itself again behind the owner's back. The rule is the same two endings as the
 * adversity run: **he finishes, or he stops and says why.** The unacceptable outcome is a project that silently
 * disappears, silently repeats, or claims completion it did not achieve.
 *
 * <p><b>Every assertion here is about the plan this scenario made, and it is found by its id.</b> An explore project
 * he starts for himself ("looking around a bit") can land in the window — creative autonomy lets him, one tick in
 * four hundred — and {@code lastPlan()} is not the answer to "what became of the plan this scenario made". It is the
 * same lesson as the harness attribution, one layer down: reading the newest thing is not reading the thing you
 * asked about, and a test that does it scores whatever happened instead.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Lifecycle}.
 */
public final class CobbieLifecycleTest implements FabricClientGameTest {

    /** The statuses a plan never leaves. */
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED");

    private static final String INITIATIVE = "gathering the oak log nearby";
    private static final String IMPOSSIBLE = "mine that far log";
    private static final String STALE = "get the log and the post";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.permit(server, "collect", true);
            // His own exploring is a competing self-start, and it is not what this class tests: an explore
            // project walks him away from the pile before initiative can choose it — twice in one run, 30
            // blocks off, after which "a pile in sight" was no longer true and no project ever started.
            CobbieTestSupport.permit(server, "explore", false);
            CobbieTestSupport.command(server, "cobbie stay");

            // --- 1. The owner interrupts HIS OWN project. ---
            //
            // The contract is that a self-started project is cancelled by an owner instruction; two owner
            // instructions queue behind each other, which is a different rule. So the project here comes from
            // initiative: creative autonomy, a pile in sight, and nothing asked for.
            long beforeInitiative = latestId(server);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(3, 0, 3);
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + i) + " " + base.getY() + " "
                            + base.getZ() + " minecraft:oak_log");
                }
                CobbieTestSupport.command(s, "cobbie autonomy creative");
                return null;
            });
            awaitPlan(server, beforeInitiative, INITIATIVE, 900,
                    "No initiative project started, so the interruption cannot be tested");
            long initiativePlan = idOf(server, beforeInitiative, INITIATIVE);
            String beforeInterrupt = statusIn(recordOf(server, initiativePlan));
            System.out.println("[lifecycle] the initiative project (" + initiativePlan + ") is " + beforeInterrupt);

            BlockPos ownerJob = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(-4, 0, -4);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan("actually, mine the log over here", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
                return base;
            });
            CobbieTestSupport.awaitPlanSettled(server, 1200);

            // The owner's instruction must have run...
            boolean ownerJobDone = server.computeOnServer(s -> s.overworld().getBlockState(ownerJob).isAir());
            if (!ownerJobDone) {
                throw new AssertionError("The owner's instruction did not run: " + describe(server, beforeInitiative));
            }
            // ...and the self-started project must be cancelled, in words, not merely dropped.
            String cancelled = recordOf(server, initiativePlan);
            if (!cancelled.toLowerCase(Locale.ROOT).contains("cancel")) {
                throw new AssertionError("The initiative project was not recorded as cancelled: " + cancelled);
            }
            // And it must not come back on its own inside the cooldown.
            long latest = latestId(server);
            CobbieTestSupport.elapsed(server, 200);
            long later = latestId(server);
            // Anything created in this window is fine unless it is the cancelled project again: a follow-up after
            // the owner's job is the brain commenting on a result, which is not the project coming back.
            for (long id = latest + 1; id <= later; id++) {
                final long probe = id;
                String record = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .planRecord(probe));
                if (record != null && record.startsWith(INITIATIVE)) {
                    throw new AssertionError("The cancelled project came back: " + record);
                }
            }
            System.out.println("[lifecycle] interrupted: owner instruction done, the project recorded \""
                    + cancelled + "\", and nothing restarted");

            // --- 2. A project whose target is taken away abandons once, with a reason, and does not repeat. ---
            long before = latestId(server);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition().offset(2, 0, 5);
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:oak_log");
                cobbie.offerPlan(IMPOSSIBLE, List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
                // The world removes it before he gets there.
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
                        + " minecraft:air");
                return null;
            });
            awaitPlan(server, before, IMPOSSIBLE, 1200,
                    "The impossible project never even started");
            // The plan this scenario made, and only it: the id is fixed the moment it exists, so an explore project
            // starting while he is idle cannot be scored in its place.
            long impossibleId = idOf(server, before, IMPOSSIBLE);
            awaitSettled(server, impossibleId, 1200, "The impossible project never settled");
            String impossibleRecord = recordOf(server, impossibleId);
            String impossibleState = statusIn(impossibleRecord);
            if ("COMPLETED".equals(impossibleState)) {
                throw new AssertionError("An impossible project reported completion: " + impossibleRecord);
            }
            CobbieTestSupport.elapsed(server, 120);
            // A follow-up plan after a failure is the brain asking the model what to do next — legitimate, and not
            // the same thing as the impossible project re-announcing itself. Only the second would be the defect.
            long after = latestId(server);
            // Only ids after the impossible project can be a re-announcement of it.
            for (long id = impossibleId + 1; id <= after; id++) {
                final long probe = id;
                String record = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .planRecord(probe));
                if (record != null && record.startsWith(IMPOSSIBLE)) {
                    throw new AssertionError("The impossible project re-announced itself: " + record);
                }
            }
            System.out.println("[lifecycle] impossible: " + impossibleRecord + " (and it did not re-announce)");

            // --- 3. A project that goes stale while it waits: the world does the work first. ---
            //
            // The sixth failure is quieter than the other five: the plan was right when it was made and
            // the world moved on before it started. Here a first project holds the queue while the second
            // is offered, and the world then finishes the second one's first objective — so by the time it
            // starts, there is nothing left for it to do. The honest ending is COMPLETED with a line that
            // says so; the defect is swinging at air, and the worse defect is reporting that as a job he
            // could not manage. The plan is not asserted to have steps: zero work is the correct amount.
            long beforeStale = latestId(server);
            // His own initiative would only add noise here, and it is not what this scenario is about.
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            BlockPos[] targets = new BlockPos[2];
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                // Something to keep him busy, so the project below is offered while he is working — the
                // case the rule is about, and the reason the world can change before it starts.
                BlockPos rise = cobbie.blockPosition().offset(-7, 0, -7);
                cobbie.offerPlan("walk over to the rise", List.of(new ActionIntent(
                        ActionVocabulary.WALK_TO, "", rise.getX(), rise.getY(), rise.getZ(), "")));
                // Two objectives in reach: a log to break, and a post that is already standing where the
                // second objective would put one.
                BlockPos wasLog = cobbie.blockPosition().offset(3, 0, 4);
                BlockPos alreadyThere = cobbie.blockPosition().offset(4, 0, 4);
                targets[0] = wasLog;
                targets[1] = alreadyThere;
                CobbieTestSupport.command(s, "setblock " + wasLog.getX() + " " + wasLog.getY() + " "
                        + wasLog.getZ() + " minecraft:oak_log");
                CobbieTestSupport.command(s, "setblock " + alreadyThere.getX() + " " + alreadyThere.getY() + " "
                        + alreadyThere.getZ() + " minecraft:oak_log");
                cobbie.offerPlan("get the log and the post sorted", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", wasLog.getX(),
                                wasLog.getY(), wasLog.getZ(), ""),
                        new ActionIntent(ActionVocabulary.PLACE, "minecraft:oak_log", alreadyThere.getX(),
                                alreadyThere.getY(), alreadyThere.getZ(), "")));
                // The world achieves the first objective before he starts it.
                CobbieTestSupport.command(s, "setblock " + wasLog.getX() + " " + wasLog.getY() + " "
                        + wasLog.getZ() + " minecraft:air");
                return null;
            });
            BlockPos wasLog = targets[0];
            BlockPos alreadyThere = targets[1];
            awaitPlan(server, beforeStale, STALE, 1600, "The stale project never started");
            long staleId = idOf(server, beforeStale, STALE);
            awaitSettled(server, staleId, 1600, "The stale project never settled");
            TaskPlan stale = planById(server, staleId);
            // (a) It reached a terminal state — and it is COMPLETED, because the world had done the work.
            // A FAILED here would be the defect wearing the right word: "I couldn't manage" about a job
            // nobody had left to do.
            if (!stale.isFinished()) {
                throw new AssertionError("The stale project is not in a terminal state: " + stale.progress());
            }
            if (stale.status() != TaskPlan.Status.COMPLETED) {
                throw new AssertionError("A project with nothing left to do ended as " + stale.status()
                        + ": " + stale.report());
            }
            // (b) The line he says names it: the world had already done it.
            String staleLine;
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.lastSpokenLine()
                        .toLowerCase(Locale.ROOT).contains("nothing left to do")).orElse(false), 200);
                staleLine = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .lastSpokenLine());
            } catch (Throwable t) {
                throw new AssertionError("He never said the work was already done; the last line was \""
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(CobbieEntity::lastSpokenLine).orElse("nothing"))
                        + "\" and the plan reads " + stale.report(), t);
            }
            // The world is the assertion, not the sentence: the log the world removed is still gone, and
            // the post he was asked to place is exactly the block it was — he touched neither.
            boolean logGone = server.computeOnServer(s -> s.overworld().getBlockState(wasLog).isAir());
            boolean postIntact = server.computeOnServer(s -> s.overworld().getBlockState(alreadyThere)
                    .is(Blocks.OAK_LOG));
            if (!logGone) {
                throw new AssertionError("The world's own work was undone: " + wasLog.toShortString()
                        + " holds a block again");
            }
            if (!postIntact) {
                throw new AssertionError("The post at " + alreadyThere.toShortString()
                        + " is no longer an oak log: " + server.computeOnServer(
                        s -> s.overworld().getBlockState(alreadyThere).toString()));
            }
            System.out.println("[lifecycle] stale: " + stale.status() + " — \"" + staleLine + "\" ("
                    + stale.alreadyDoneCount() + " of " + stale.steps().size()
                    + " objectives were already done, " + stale.failedCount() + " failed)");

            System.out.println("[lifecycle] done");
        }
    }

    // ==================================================================
    // Finding the plan a scenario made, rather than whatever ran last
    // ==================================================================

    private static long latestId(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
    }

    /** The id of the newest plan created after {@code since} whose request starts with {@code prefix}, or -1. */
    private static long findPlan(CobbieEntity cobbie, long since, String prefix) {
        for (long id = cobbie.latestPlanId(); id > since; id--) {
            String record = cobbie.planRecord(id);
            if (record != null && record.startsWith(prefix)) return id;
        }
        return -1;
    }

    /** That id once it exists; a scenario whose own plan vanished has nothing to assert about. */
    private static long idOf(TestServerContext server, long since, String prefix) {
        long id = server.computeOnServer(s -> findPlan(CobbieTestSupport.cobbie(s).orElseThrow(), since, prefix));
        if (id < 0) {
            throw new AssertionError("The project starting \"" + prefix + "\" vanished: " + describe(server, since));
        }
        return id;
    }

    private static void awaitPlan(TestServerContext server, long since, String prefix, int ticks, String why) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> findPlan(c, since, prefix) >= 0).orElse(false), ticks);
        } catch (Throwable t) {
            throw new AssertionError(why + "; plans after " + since + " read: " + describe(server, since), t);
        }
    }

    /** Wait on that plan's own record reaching a terminal status — never on "the newest plan is finished". */
    private static void awaitSettled(TestServerContext server, long id, int ticks, String why) {
        try {
            server.waitFor(s -> {
                String record = CobbieTestSupport.cobbie(s).map(c -> c.planRecord(id)).orElse(null);
                return record != null && TERMINAL.contains(statusIn(record));
            }, ticks);
        } catch (Throwable t) {
            throw new AssertionError(why + "; plan " + id + " reads " + recordOf(server, id), t);
        }
    }

    /** One plan's record, "request -> STATUS — report". */
    private static String recordOf(TestServerContext server, long id) {
        String record = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().planRecord(id));
        if (record == null) {
            throw new AssertionError("Plan " + id + " is no longer remembered, so nothing can be said about it");
        }
        return record;
    }

    /** The status word out of a plan record. */
    private static String statusIn(String record) {
        int mark = record.indexOf(" -> ");
        if (mark < 0) return "";
        int end = record.indexOf(" — ", mark + 4);
        return record.substring(mark + 4, end < 0 ? record.length() : end);
    }

    /** The plan itself, by id, for the assertions that need more than its record. */
    private static TaskPlan planById(TestServerContext server, long id) {
        TaskPlan plan = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().planAfter(id - 1));
        if (plan == null || plan.id() != id) {
            throw new AssertionError("Plan " + id + " is no longer remembered, so nothing can be said about it");
        }
        return plan;
    }

    /** Every plan created after {@code since}, so a failure shows what the test actually saw instead of a guess. */
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
}
