package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.brain.LlmClient;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * A probe, not a test: what a companion costs a ticking server, measured so that drift cannot pass for effect.
 *
 * <p>The first version of this probe ran its phases in blocks and every phase read the same number — which turned
 * out to be the vacuous-wait bug, in the probe written to measure things. The second version sampled properly and
 * produced a result that was honest but useless as a number: the server's own tick average drifts further across
 * a session than the companion moves it, so "with him" and "without him" overlapped and the sign flipped.
 *
 * <p>So the phases are **interleaved**: kill him, sample; spawn him, sample; five rounds. Each pair is adjacent in
 * time, so drift affects both halves. The report is the paired deltas — median, range, and how many rounds put him
 * above the empty world — rather than one mean that hides whether the effect is even resolvable.
 *
 * <p>Prints, never asserts. Run with {@code ./gradlew runClientGameTest -PcobbieTests=Perf}.
 */
public final class CobbiePerfTest implements FabricClientGameTest {

    private static final int ROUNDS = 5;
    private static final int WARMUP = 60;
    private static final int SAMPLE = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            describeProbe(server);

            double[] without = new double[ROUNDS];
            double[] with = new double[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                kill(server);
                CobbieTestSupport.elapsed(server, WARMUP);
                without[round] = sample(server);

                spawn(server);
                CobbieTestSupport.command(server, "cobbie stay");
                CobbieTestSupport.elapsed(server, WARMUP);
                with[round] = sample(server);
                System.out.println("[perf] round " + (round + 1) + ": without " + fmt(without[round])
                        + "  with " + fmt(with[round]) + "  delta " + fmt(with[round] - without[round])
                        + " us/tick");
            }

            double[] deltas = new double[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) deltas[i] = with[i] - without[i];
            double[] sortedDeltas = deltas.clone();
            Arrays.sort(sortedDeltas);
            int above = 0;
            for (double delta : deltas) {
                if (delta > 0) above++;
            }
            System.out.println("[perf] paired delta median " + fmt(sortedDeltas[ROUNDS / 2]) + " us/tick, range "
                    + fmt(sortedDeltas[0]) + " .. " + fmt(sortedDeltas[ROUNDS - 1]) + ", " + above + " of " + ROUNDS
                    + " rounds above zero");
            System.out.println("[perf] verdict: " + verdict(sortedDeltas, above));

            // --- model calls: idle in both autonomy modes, then while a plan runs ---
            int idleAssistive = requestsOver(server, 200, null);
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            int idleCreative = requestsOver(server, 200, null);
            CobbieTestSupport.command(server, "cobbie autonomy assistive");
            int working = requestsOver(server, 200, s -> {
                CobbieEntity body = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = body.blockPosition().offset(2, 0, 2);
                for (int i = 0; i < 4; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + i) + " " + base.getY() + " "
                            + base.getZ() + " minecraft:oak_log");
                }
                body.offerPlan("mine the logs", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), "")));
            });
            System.out.println("[perf] model calls per minute — idle(assistive) " + fmt1(idleAssistive * 6.0)
                    + ", idle(creative) " + fmt1(idleCreative * 6.0) + ", working " + fmt1(working * 6.0)
                    + "   (measured over 200 ticks each)");
            System.out.println("[perf] done");
        }
    }

    /**
     * The honest sentence for a set of paired deltas: an effect smaller than its own spread is not a number.
     */
    private static String verdict(double[] sortedDeltas, int above) {
        double median = sortedDeltas[sortedDeltas.length / 2];
        double spread = sortedDeltas[sortedDeltas.length - 1] - sortedDeltas[0];
        if (Math.abs(median) < Math.abs(spread) / 2.0) {
            return "he costs less than this measurement can resolve (median " + fmt(median) + " us/tick against a"
                    + " spread of " + fmt(spread) + "), so nothing here justifies an optimisation";
        }
        return "he costs " + fmt(median) + " us/tick, which is larger than the spread (" + fmt(spread) + ")";
    }

    private static int requestsOver(TestServerContext server, int ticks, java.util.function.Consumer<MinecraftServer> setup) {
        if (setup != null) server.computeOnServer(s -> { setup.accept(s); return null; });
        int before = LlmClient.requestsSent();
        CobbieTestSupport.elapsed(server, ticks);
        return LlmClient.requestsSent() - before;
    }

    private static void kill(TestServerContext server) {
        CobbieTestSupport.command(server, "kill @e[type=cobbie]");
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).isEmpty(), 120);
        } catch (Throwable ignored) {
            // nothing to kill: already gone
        }
    }

    private static void spawn(TestServerContext server) {
        CobbieTestSupport.command(server, "spawncobbie");
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 200);
        } catch (Throwable t) {
            throw new AssertionError("The probe could not respawn him, so the interleaved comparison stopped", t);
        }
    }

    /** One sample: wait a measured window, then read the server's own rolling average. */
    private static double sample(TestServerContext server) {
        CobbieTestSupport.elapsed(server, SAMPLE);
        return server.computeOnServer(CobbiePerfTest::averageTickMicros);
    }

    private static Double readAverage(MinecraftServer server) {
        for (String name : new String[] {"getAverageTickTimeNanos", "getAverageTickTime"}) {
            try {
                Method method = MinecraftServer.class.getMethod(name);
                Object value = method.invoke(server);
                if (value instanceof Number number) {
                    double raw = number.doubleValue();
                    return raw > 100_000 ? raw / 1000.0 : raw;
                }
            } catch (ReflectiveOperationException ignored) {
                // try the next shape
            }
        }
        for (String name : new String[] {"tickTimesNanos", "tickTimes"}) {
            try {
                Field field = MinecraftServer.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(server);
                if (value instanceof long[] times && times.length > 0) {
                    long total = 0;
                    for (long time : times) total += time;
                    double mean = (double) total / times.length;
                    return mean > 100_000 ? mean / 1000.0 : mean;
                }
            } catch (ReflectiveOperationException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    private static double averageTickMicros(MinecraftServer server) {
        Double value = readAverage(server);
        return value == null ? -1 : value;
    }

    private static void describeProbe(TestServerContext server) {
        server.computeOnServer(s -> {
            Double value = readAverage(s);
            System.out.println("[perf] probe: server tick average reads as "
                    + (value == null ? "unavailable" : fmt(value) + " us"));
            return null;
        });
    }

    private static String fmt(double micros) {
        return micros == -1 ? "unavailable" : String.format("%.1f", micros);
    }

    private static String fmt1(double value) {
        return String.format("%.1f", value);
    }
}
