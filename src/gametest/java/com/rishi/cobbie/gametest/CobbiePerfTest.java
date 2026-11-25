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
import java.util.List;

/**
 * A probe, not a test: what a companion actually costs a ticking server.
 *
 * <p>Rishi's first two complaints were "he is taking too much time" and "my PC gets laggy". The model round trips
 * were fixed and the brain moved off his GPU, but the mod has grown per-tick work since — perception every few
 * ticks, an initiative scan every second, follow re-aiming, an experience sweep every four ticks, the status tag,
 * narration — and nobody has measured any of it.
 *
 * <p>The measurement is the server's own tick durations, taken as the delta between the same world with and
 * without him, because a delta is the only number that answers "what does he cost" rather than "how fast is this
 * machine". Each phase reports microseconds per tick, and the model-request counter reports calls per minute for
 * the same phase.
 *
 * <p>Prints, never asserts. Run with {@code ./gradlew runClientGameTest -PcobbieTests=Perf}.
 */
public final class CobbiePerfTest implements FabricClientGameTest {

    private static final int WARMUP = 100;
    private static final int SAMPLE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            describeProbe(server);

            // --- 1. The world with nobody in it. ---
            CobbieTestSupport.elapsed(server, WARMUP);
            double empty = sample(server);
            System.out.println("[perf] no companion:            " + fmt(empty) + " us/tick");

            // --- 2. Him, idle, assistive. ---
            CobbieEntity cobbie = CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            int requestsBefore = LlmClient.requestsSent();
            CobbieTestSupport.elapsed(server, WARMUP);
            double idle = sample(server);
            int idleCalls = LlmClient.requestsSent() - requestsBefore;
            System.out.println("[perf] him idle (assistive):    " + fmt(idle) + " us/tick  delta "
                    + fmt(idle - empty) + "  model calls in " + SAMPLE + " ticks: " + idleCalls);

            // --- 3. Him idle with initiative allowed: the once-a-second world scan. ---
            CobbieTestSupport.command(server, "cobbie autonomy creative");
            int creativeRequests = LlmClient.requestsSent();
            CobbieTestSupport.elapsed(server, WARMUP);
            double creative = sample(server);
            int creativeCalls = LlmClient.requestsSent() - creativeRequests;
            System.out.println("[perf] him idle (creative):     " + fmt(creative) + " us/tick  delta "
                    + fmt(creative - empty) + "  model calls in " + SAMPLE + " ticks: " + creativeCalls);
            CobbieTestSupport.command(server, "cobbie autonomy assistive");

            // --- 4. Him working: the same world, a real plan running. ---
            BlockPos target = server.computeOnServer(s -> {
                CobbieEntity body = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = body.blockPosition().offset(2, 0, 2);
                for (int i = 0; i < 6; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + i) + " " + base.getY() + " "
                            + base.getZ() + " minecraft:oak_log");
                }
                body.offerPlan("mine the logs", List.of(
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX(), base.getY(),
                                base.getZ(), ""),
                        new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log", base.getX() + 1,
                                base.getY(), base.getZ(), "")));
                return base;
            });
            int workRequests = LlmClient.requestsSent();
            double working = sample(server);
            int workCalls = LlmClient.requestsSent() - workRequests;
            System.out.println("[perf] him working:             " + fmt(working) + " us/tick  delta "
                    + fmt(working - empty) + "  model calls in " + SAMPLE + " ticks: " + workCalls);

            // --- 5. He is gone: the world again. ---
            CobbieTestSupport.command(server, "kill @e[type=cobbie]");
            CobbieTestSupport.elapsed(server, WARMUP);
            double after = sample(server);
            System.out.println("[perf] after he is killed:      " + fmt(after) + " us/tick  delta vs empty "
                    + fmt(after - empty));

            double ticksPerMinute = 1200.0;
            double idlePerMinute = idleCalls * ticksPerMinute / (SAMPLE * 5);
            double creativePerMinute = creativeCalls * ticksPerMinute / (SAMPLE * 5);
            System.out.println("[perf] model calls/minute — idle(assistive) " + fmt1(idlePerMinute)
                    + ", idle(creative) " + fmt1(creativePerMinute) + ", working "
                    + fmt1(workCalls * ticksPerMinute / (SAMPLE * 5)));
            System.out.println("[perf] done");
        }
    }

    /**
     * A phase is sampled five times and reported as its median: the server's rolling average moves by more than
     * a hundred microseconds between windows on one machine, so a single reading cannot tell a cost from noise.
     */
    private static double sample(TestServerContext server) {
        double[] readings = new double[5];
        for (int i = 0; i < readings.length; i++) {
            CobbieTestSupport.elapsed(server, SAMPLE);
            readings[i] = server.computeOnServer(CobbiePerfTest::averageTickMicros);
        }
        java.util.Arrays.sort(readings);
        StringBuilder all = new StringBuilder();
        for (double reading : readings) {
            if (all.length() > 0) all.append(' ');
            all.append(fmt(reading));
        }
        System.out.println("[perf]   samples: " + all);
        return readings[readings.length / 2];
    }

    /** Reads whatever this version exposes: a method first, then a field. Prints which one worked. */
    private static Double readAverage(MinecraftServer server) {
        for (String name : new String[] {"getAverageTickTimeNanos", "getAverageTickTime"}) {
            try {
                Method method = MinecraftServer.class.getMethod(name);
                Object value = method.invoke(server);
                if (value instanceof Number number) {
                    double nanos = number.doubleValue();
                    return nanos > 100_000 ? nanos / 1000.0 : nanos;   // nanos vs millis
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
                    return mean > 100_000 ? mean / 1000.0 : mean;   // nanos vs millis
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
            System.out.println("[perf] probe: server tick average reads as " + (value == null ? "unavailable"
                    : fmt(value) + " us"));
            return null;
        });
    }

    private static String fmt(double micros) {
        // -1 is the "could not read it" sentinel; a negative DELTA is a real result and must print.
        return micros == -1 ? "unavailable" : String.format("%.1f", micros);
    }

    private static String fmt1(double value) {
        return String.format("%.1f", value);
    }
}
