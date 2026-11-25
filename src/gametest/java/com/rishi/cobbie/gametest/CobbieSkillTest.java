package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.brain.LlmClient;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.skill.Skill;
import com.rishi.cobbie.skill.SkillExecutor;
import com.rishi.cobbie.skill.SkillRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Milestone 9: a project he has finished becomes a skill he can run again, on a fresh target, for no
 * model calls at all.
 *
 * <p>Both halves of the measure are asserted, because "it works" alone would be satisfied by replanning:
 * the replay must harvest the <b>second</b> pile it has never seen, and it must do so with the model-request
 * counter unchanged. The skill is also checked where the owner would look for it — the file on disk, so it
 * survives a restart, and the list and delete commands.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Skill}.
 */
public final class CobbieSkillTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);

            // A pile to learn on, and nothing else in sight.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + 1 + i) + " " + base.getY() + " "
                            + (base.getZ() + 3) + " minecraft:oak_log");
                }
                return null;
            });

            String refusal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                List<ActionIntent> steps = new java.util.ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    steps.add(new ActionIntent(ActionVocabulary.MINE, "minecraft:oak_log",
                            base.getX() + 1 + i, base.getY(), base.getZ() + 3, ""));
                }
                return cobbie.offerPlan("gather the oak logs", steps);
            });
            if (refusal != null) {
                throw new AssertionError("The first gather was refused: " + refusal);
            }
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.lastPlan() != null && c.lastPlan().isFinished()).orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("The first gather never finished: " + lastReport(server), t);
            }
            String firstStatus = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            if (!"COMPLETED".equals(firstStatus)) {
                throw new AssertionError("The first gather did not complete (" + firstStatus + "): "
                        + lastReport(server));
            }

            // --- He learned it. ---
            // By name, never "whatever is first": this file lives in the config directory and may
            // already hold skills from other worlds, which is exactly how a test starts asserting
            // about somebody else's skill.
            String expected = "gather-the-oak-logs";
            Skill skill = server.computeOnServer(s -> SkillRegistry.get(expected).orElse(null));
            if (skill == null) {
                throw new AssertionError("A clean multi-step gather left no skill called \"" + expected
                        + "\" behind; the registry holds " + server.computeOnServer(s ->
                        SkillRegistry.all().stream().map(Skill::name).toList())
                        + " — " + lastReport(server));
            }
            if (skill.steps().size() < 2) {
                throw new AssertionError("The learned skill \"" + skill.name() + "\" has "
                        + skill.steps().size() + " step(s), which is not a procedure");
            }
            boolean anchored = skill.steps().stream()
                    .anyMatch(st -> st.data() != null && st.data().contains("anchor=minecraft:oak_log"));
            if (!anchored) {
                throw new AssertionError("The skill \"" + skill.name() + "\" was not anchored to the block"
                        + " it was learned against, so it could only replay where it was learned: "
                        + skill.steps().get(0).summary());
            }
            String worldName = server.computeOnServer(s -> s.getWorldData().getLevelName());
            if (skill.learnedIn().isBlank()) {
                throw new AssertionError("The skill \"" + skill.name() + "\" does not record which world"
                        + " it came from, so the owner cannot tell a habit from one learned elsewhere");
            }
            if (!skill.learnedIn().equals(worldName)) {
                throw new AssertionError("The skill says it came from \"" + skill.learnedIn()
                        + "\" but this world is \"" + worldName + "\"");
            }
            boolean listed = server.computeOnServer(s -> SkillRegistry.inspect().contains(" from " + worldName));
            if (!listed) {
                throw new AssertionError("The world is recorded but /cobbie skills list does not show it: "
                        + server.computeOnServer(s -> SkillRegistry.inspect()));
            }
            Path stored = FabricLoader.getInstance().getConfigDir().resolve("cobbie_skills.json");
            boolean onDisk = server.computeOnServer(s -> {
                try {
                    return Files.exists(stored) && Files.readString(stored).contains(skill.name());
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            if (!onDisk) {
                throw new AssertionError("The skill \"" + skill.name() + "\" is not in " + stored
                        + ", so it would not survive a restart");
            }
            System.out.println("[gametest] skill: learned \"" + skill.name() + "\" — "
                    + skill.steps().size() + " anchored step(s), from world \"" + skill.learnedIn()
                    + "\", saved to disk and shown in the list");

            // --- A fresh pile, somewhere it was never learned. ---
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                for (int i = 0; i < 3; i++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() - 1 - i) + " " + base.getY() + " "
                            + (base.getZ() - 3) + " minecraft:oak_log");
                }
                return null;
            });

            int before = LlmClient.requestsSent();
            String message = server.computeOnServer(s -> SkillExecutor
                    .run(CobbieTestSupport.cobbie(s).orElseThrow(), skill.name()).message());
            try {
                server.waitFor(s -> {
                    CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                    BlockPos base = cobbie.blockPosition();
                    for (int i = 0; i < 3; i++) {
                        if (s.overworld().getBlockState(new BlockPos(base.getX() - 1 - i, base.getY(),
                                base.getZ() - 3)).is(Blocks.OAK_LOG)) return false;
                    }
                    return cobbie.lastPlan() != null && cobbie.lastPlan().isFinished();
                }, 900);
            } catch (Throwable t) {
                throw new AssertionError("The replay did not harvest the fresh pile (" + message + "): "
                        + lastReport(server), t);
            }
            int after = LlmClient.requestsSent();
            String replayStatus = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .lastPlan().status().toString());
            System.out.println("[gametest] skill diag: replay plan " + replayStatus + " — "
                    + lastReport(server) + " (requests " + before + " -> " + after + ")");
            if (after != before) {
                throw new AssertionError("The replay cost " + (after - before) + " model request(s); a"
                        + " learned skill exists to cost none");
            }

            Skill credited = server.computeOnServer(s -> SkillRegistry.get(skill.name()).orElseThrow());
            if (credited.successes() < 1) {
                throw new AssertionError("The replay worked but was never credited to the skill: "
                        + credited.successes() + " ok, " + credited.failures() + " failed");
            }
            System.out.println("[gametest] skill ok: replay of \"" + skill.name() + "\" harvested a fresh"
                    + " pile with " + (after - before) + " model request(s) — " + message);

            // --- And it is forgettable, the way the owner would ask. ---
            CobbieTestSupport.command(server, "cobbie skills delete " + skill.name());
            boolean gone = server.computeOnServer(s -> SkillRegistry.get(skill.name()).isEmpty());
            if (!gone) {
                throw new AssertionError("After /cobbie skills delete, \"" + skill.name() + "\" is still"
                        + " there");
            }
            System.out.println("[gametest] skill: \"/cobbie skills delete " + skill.name() + "\" removed it");
        }
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
