package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The mod's one client game test entrypoint, and the reason the suite can be trusted after a failure.
 *
 * <p>Fabric's runner hands each registered entrypoint the same client and lets the first one that
 * throws take the process down with it. With fifteen registered classes that means the first red
 * test hides every test behind it, so a run tells you one thing and then crashes — and a class that
 * fails is a class whose neighbours never got to run. This runs them in order behind a try/catch,
 * prints one line per class, and only then rethrows, so a red class is one red line and fourteen
 * real results instead of a stack trace and a guess.
 *
 * <p>Narrow the run without editing anything: {@code ./gradlew runClientGameTest -PcobbieTests=Status}
 * (matched against the simple class name, case-insensitive substring, comma-separated for several).
 */
public final class CobbieGameTests implements FabricClientGameTest {

    private static final List<FabricClientGameTest> ALL = List.of(
            new CobbieWorldChangeTest(),
            new CobbieTerrainTest(),
            new CobbieWallRescueTest(),
            new CobbieReattachTest(),
            new CobbieRideTest(),
            new CobbieCapabilityTest(),
            new CobbieAbilitiesTest(),
            new CobbieDeathLootTest(),
            new CobbieImperativeTest(),
            new CobbiePlanTest(),
            new CobbieStatusTest(),
            new CobbiePanelTest(),
            new CobbieConfigUiTest(),
            new CobbieFollowTest(),
            new CobbieReachTest(),
            new CobbieSwimTest(),
            new CobbieExploreTest(),
            new CobbieCraftTest(),
            new CobbieCommandsTest(),
            new CobbieMineTest(),
            new CobbieDoorTest(),
            new CobbieContainerTest(),
            new CobbieBagMigrationTest(),
            new CobbieExperienceTest(),
            new CobbieEnchantTest(),
            new CobbieAnvilTest(),
            new CobbieBrewTest(),
            new CobbieSmithTest(),
            new CobbieInteractTest(),
            new CobbieGrindTest(),
            new CobbieLoomTest(),
            new CobbieCartographyTest(),
            new CobbieClimbTest(),
            new CobbieUseTest(),
            new CobbiePlaceTest(),
            new CobbieCombatTest(),
            new CobbiePersistenceTest(),
            new CobbieSmokeTest(),
            new CobbieChatTest(),
            new CobbieVerbChoiceTest(),
            new CobbieNarrationTest(),
            new CobbieFarmTest(),
            new CobbieFarmProbeTest(),
            new CobbieBreedTest(),
            new CobbieAnimalProbeTest(),
            new CobbieTradeTest(),
            new CobbieTradeProbeTest(),
            new CobbieRedstoneTest(),
            new CobbieRedstoneProbeTest(),
            new CobbieInitiativeTest(),
            new CobbieSkillTest(),
            new CobbieNameTest(),
            new CobbieDecorProbeTest(),
            new CobbieDecorTest(),
            new CobbieSpendTest(),
            new CobbieDescribeTest(),
            new CobbiePerfTest(),
            new CobbieClaimProbeTest(),
            new CobbieAnvilEndTest(),
            new CobbieAdversityTest(),
            new CobbieLifecycleTest(),
            new CobbieFollowProbeTest(),
            new CobbieAlreadyDoneTest(),
            new CobbieUnsatTest(),
            new CobbieStalePlanTest(),
            new CobbieEndingsTest(),
            new CobbieEvidenceTest(),
            new CobbieToolChoiceTest());

    @Override
    public void runTest(ClientGameTestContext context) {
        List<FabricClientGameTest> selected = select(System.getProperty("cobbie.gametests", ""));
        System.out.println("[gametest] running " + selected.size() + " class(es): " + names(selected));

        Map<String, String> outcome = new LinkedHashMap<>();
        for (FabricClientGameTest test : selected) {
            String name = test.getClass().getSimpleName();
            long started = System.currentTimeMillis();
            try {
                test.runTest(context);
                outcome.put(name, "ok");
                System.out.println("[gametest] " + name + " ok (" + (System.currentTimeMillis() - started) + "ms)");
            } catch (Throwable t) {
                outcome.put(name, "FAILED");
                System.out.println("[gametest] " + name + " FAILED after "
                        + (System.currentTimeMillis() - started) + "ms: " + t);
                t.printStackTrace(System.out);
            }
        }

        long failed = outcome.values().stream().filter("FAILED"::equals).count();
        System.out.println("[gametest] summary: " + (outcome.size() - failed) + " passed, " + failed
                + " failed of " + outcome.size() + " — " + outcome);
        if (failed > 0) {
            // The process still has to fail: the exit code is what CI and a human read.
            throw new AssertionError(failed + " of " + outcome.size() + " client game test classes failed: "
                    + outcome.entrySet().stream()
                            .filter(e -> "FAILED".equals(e.getValue()))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.joining(", ")));
        }
    }

    /** Empty filter means all of them; anything else is a substring match on the class name. */
    private static List<FabricClientGameTest> select(String filter) {
        if (filter == null || filter.isBlank()) return ALL;
        List<String> wanted = new ArrayList<>();
        for (String part : filter.split(",")) {
            String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) wanted.add(trimmed);
        }
        if (wanted.isEmpty()) return ALL;
        return ALL.stream()
                .filter(t -> wanted.stream()
                        .anyMatch(w -> t.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains(w)))
                .toList();
    }

    private static String names(List<FabricClientGameTest> tests) {
        return tests.stream().map(t -> t.getClass().getSimpleName()).collect(Collectors.joining(", "));
    }
}
