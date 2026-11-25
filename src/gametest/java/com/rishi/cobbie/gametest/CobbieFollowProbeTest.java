package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The follow intermittent, by repetition rather than by argument.
 *
 * <p>{@code CobbieFollowTest} failed once in a full suite with {@code before=14.142135620117188
 * after=14.142135620117188} — <b>bit-identical over thirty seconds</b>, which is a body that never started rather
 * than one that tried and failed. Following is the thing he does constantly, so the question is not whether the
 * route exists but why it does not execute. One red in three full runs is not something to argue about, so this
 * probe asks the same question eight times in a row by default, in one world, on the terrain that failed: the normal
 * preset, the owner twelve blocks out on both axes, and him following. Raise it to twenty-five with
 * {@code COBBIE_FOLLOW_ITERATIONS=25} when something is actually being chased.
 *
 * <p>On the first freeze it prints the state and stops — his job and its status, the target and the distance, the
 * navigator's own answer (done, path, target position), whether he is inside a block, on the ground, in water, what
 * he is riding, and his position once a second for the ten seconds before the freeze — because the suspects are
 * distinguishable and guessing is what this exists to avoid: no path and nothing re-asked for one; the repath
 * throttle (every {@code FOLLOW_REPATH_INTERVAL} ticks) landing in a gap; a body that cannot stand where it is; or a
 * path that exists with something other than the navigator holding him.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=FollowProbe}. The iteration count can be lowered with
 * {@code -Dcobbie.followProbe.iterations=N}.
 */
public final class CobbieFollowProbeTest implements FabricClientGameTest {

    /**
     * Rounds in one session: <b>eight by default, about thirty seconds</b>, and twenty-five (about eighty-five
     * seconds) when someone is chasing the freeze — {@code COBBIE_FOLLOW_ITERATIONS=25}. A suite that pays eighty
     * seconds every run is a suite people skip, and a skipped suite catches nothing. A {@code -D} on the gradle
     * command line does not work: it reaches the daemon, not the client this forks.
     */
    private static final int ITERATIONS = iterations();
    /** The same window {@code CobbieFollowTest} gives him to close two blocks. */
    private static final int BUDGET_TICKS = 600;
    private static final int SAMPLE_EVERY = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.getNormalPresetList().stream()
                            .filter(e -> e.preset().unwrapKey().map(WorldPresets.NORMAL::equals).orElse(false))
                            .findFirst()
                            .ifPresent(state::setWorldType);
                    state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    state.setDifficulty(Difficulty.PEACEFUL);
                    state.setAllowCommands(true);
                })
                .create()) {

            context.waitFor(mc -> mc.player != null && mc.level != null, 200);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // His own projects must not be the thing his feet are busy with: this is about following.
            CobbieTestSupport.permit(server, "explore", false);

            BlockPos home = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            double[] away = server.computeOnServer(s -> {
                ServerPlayer owner = CobbieTestSupport.owner(s);
                return new double[]{owner.getX() + 12, owner.getY(), owner.getZ() + 12};
            });

            List<String> samples = new ArrayList<>();
            for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
                samples.clear();
                final int round = iteration;

                // The same fixture every time: him at home, you twelve blocks out on both axes.
                server.computeOnServer(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s)
                            .orElseThrow(() -> new AssertionError("Round " + round
                                    + ": no companion to reset — he died or was removed between rounds"));
                    // Twenty rounds of hill walking with no regen ends with a fall death, which is the loop and
                    // not the behaviour: one round of this fixture never needs healing, twenty do.
                    cobbie.setHealth(cobbie.getMaxHealth());
                    cobbie.cancelTask("probe reset");
                    cobbie.getNavigation().stop();
                    cobbie.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                    cobbie.startFollowing();
                    CobbieTestSupport.owner(s).teleportTo(s.overworld(), away[0], away[1], away[2],
                            Set.of(), 0.0F, 8.0F, true);
                    return null;
                });
                try {
                    server.waitFor(s -> s.overworld().isLoaded(CobbieTestSupport.owner(s).blockPosition()), 200);
                } catch (Throwable t) {
                    throw new AssertionError("Round " + round + ": the owner's chunk never loaded", t);
                }
                context.waitTicks(10);

                double before = server.computeOnServer(CobbieFollowProbeTest::distance);
                if (iteration == 1) {
                    // One healthy reading, so the log shows the format and what "fine" looks like.
                    System.out.println("[probe] round 1, before the walk: " + server.computeOnServer(
                            s -> sample(s, distance(s))) + " | " + diagnosis(server));
                }
                boolean closed;
                try {
                    server.waitFor(s -> {
                        double now = distance(s);
                        if (s.getTickCount() % SAMPLE_EVERY == 0) {
                            samples.add(sample(s, now));
                        }
                        return now < before - 2.0;
                    }, BUDGET_TICKS);
                    closed = true;
                } catch (Throwable t) {
                    closed = false;
                }

                if (!closed) {
                    System.out.println("[probe] follow froze on round " + round + " of " + ITERATIONS
                            + "; before=" + before + " after=" + server.computeOnServer(CobbieFollowProbeTest::distance));
                    System.out.println("[probe] " + diagnosis(server));
                    System.out.println("[probe] last ten seconds, once a second:");
                    for (String s : samples) {
                        System.out.println("[probe]   " + s);
                    }
                    throw new AssertionError("Round " + round + ": he never started moving; the printout above is the"
                            + " finding");
                }
                if (iteration % 5 == 0) {
                    System.out.println("[probe] round " + iteration + " of " + ITERATIONS + " closed the gap");
                }
            }
            System.out.println("[probe] follow: " + ITERATIONS + " of " + ITERATIONS
                    + " rounds closed the gap in one session");
        }
    }

    /** One line of his own state, every few seconds, so the freeze has a history rather than a snapshot. */
    private static String sample(MinecraftServer server, double distance) {
        return CobbieTestSupport.cobbie(server).map(c -> {
            var nav = c.getNavigation();
            return "t=" + server.getTickCount() + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                    + " pos=" + c.blockPosition().toShortString()
                    + " navDone=" + nav.isDone()
                    + " navTarget=" + (nav.getTargetPos() == null ? "none" : nav.getTargetPos().toShortString())
                    + " onGround=" + c.onGround() + " inWater=" + c.isInWater()
                    + " ownerChunkLoaded=" + server.overworld()
                            .isLoaded(CobbieTestSupport.owner(server).blockPosition());
        }).orElse("no companion at t=" + server.getTickCount());
    }

    private static String diagnosis(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElse(null);
            if (cobbie == null) return "no companion in the world after the freeze";
            var nav = cobbie.getNavigation();
            var plan = cobbie.lastPlan();
            return "following=" + cobbie.isFollowing()
                    + " busy=" + cobbie.isBusy()
                    + " status=\"" + cobbie.statusWord() + "\""
                    + " job=\"" + cobbie.currentTaskDescription() + "\""
                    + " plan=" + (plan == null ? "none" : plan.status() + " \"" + plan.request() + "\"")
                    + " latestPlanId=" + cobbie.latestPlanId()
                    + " navDone=" + nav.isDone()
                    + " navPath=" + (nav.getPath() == null ? "none" : "present")
                    + " navTarget=" + (nav.getTargetPos() == null ? "none" : nav.getTargetPos().toShortString())
                    + " inWall=" + cobbie.isInWall()
                    + " onGround=" + cobbie.onGround()
                    + " inWater=" + cobbie.isInWater()
                    + " passenger=" + cobbie.isPassenger()
                    + " pos=" + cobbie.position()
                    + " owner=" + CobbieTestSupport.owner(s).position()
                    + " ownerChunkLoaded=" + s.overworld()
                            .isLoaded(CobbieTestSupport.owner(s).blockPosition())
                    + " ownerChunkSimulation=" + s.overworld().isPositionEntityTicking(
                            CobbieTestSupport.owner(s).blockPosition())
                    + " said=\"" + cobbie.lastSpokenLine() + "\"";
        });
    }

    private static int iterations() {
        String raw = System.getenv("COBBIE_FOLLOW_ITERATIONS");
        if (raw == null || raw.isBlank()) raw = System.getProperty("cobbie.followProbe.iterations");
        try {
            return raw == null || raw.isBlank() ? 8 : Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static double distance(MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return -1;
        return Math.sqrt(cobbie.distanceToSqr(CobbieTestSupport.owner(server)));
    }
}
