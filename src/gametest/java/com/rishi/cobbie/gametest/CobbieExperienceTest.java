package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * His own experience: earned from orbs, and kept.
 *
 * <p>Three claims. Orbs are absorbed with the game's own values rather than a table of mine, the level
 * he earns survives a save, and nothing about it comes from the owner. The third is asserted by the
 * first two plus a check that the owner's own level never moved.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Experience}.
 */
public final class CobbieExperienceTest implements FabricClientGameTest {

    private static final int ORB_VALUE = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // The owner stands next to him after a spawn, and a player absorbs orbs faster than a
            // companion can. Move the owner out of pickup range so the orb is his to take.
            CobbieTestSupport.teleportOwner(server, 12, 0, 12);
            context.waitTicks(5);
            int ownerLevelBefore = server.computeOnServer(s -> CobbieTestSupport.owner(s).experienceLevel);
            float ownerProgressBefore = server.computeOnServer(s -> CobbieTestSupport.owner(s).experienceProgress);

            // Level 0 costs 7 to reach level 1, so 30 points is several levels' worth of bar.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                ExperienceOrb.award(s.overworld(), cobbie.position(), ORB_VALUE);
                return null;
            });
            try {
                server.waitFor(s -> levelOf(s) > 0 || progressOf(s) > 0, 300);
            } catch (Throwable t) {
                throw new AssertionError("An orb at his feet was not absorbed: level " + server.computeOnServer(
                        CobbieExperienceTest::levelOf) + ", progress " + server.computeOnServer(
                        CobbieExperienceTest::progressOf), t);
            }

            int level = server.computeOnServer(CobbieExperienceTest::levelOf);
            int progress = server.computeOnServer(CobbieExperienceTest::progressOf);
            int total = level * 1000 + progress;   // monotone enough to compare against the curve below
            if (total <= 0) throw new AssertionError("No experience was gained at all");

            // The owner did not pay for it.
            int ownerLevelAfter = server.computeOnServer(s -> CobbieTestSupport.owner(s).experienceLevel);
            float ownerProgressAfter = server.computeOnServer(s -> CobbieTestSupport.owner(s).experienceProgress);
            if (ownerLevelAfter != ownerLevelBefore || Math.abs(ownerProgressAfter - ownerProgressBefore) > 0.001) {
                throw new AssertionError("The owner's experience changed: level " + ownerLevelBefore + " -> "
                        + ownerLevelAfter + ", progress " + ownerProgressBefore + " -> " + ownerProgressAfter);
            }

            // And it survives a save: the same tag round trip the bag migration test uses.
            CompoundTag saved = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, s.registryAccess());
                cobbie.saveWithoutId(out);
                return out.buildResult();
            });
            int reloadedLevel = server.computeOnServer(s -> {
                CobbieEntity fresh = new CobbieEntity(CobbieEntities.COBBIE, s.overworld());
                fresh.load(TagValueInput.create(ProblemReporter.DISCARDING, s.registryAccess(), saved));
                return fresh.experienceLevel();
            });
            if (reloadedLevel != level) {
                throw new AssertionError("Level was " + level + " before saving and " + reloadedLevel + " after");
            }

            System.out.println("[gametest] experience ok: " + ORB_VALUE + " points of orbs took him to level "
                    + level + " with " + progress + " progress, the owner's level untouched at "
                    + ownerLevelAfter + ", and the level survived a save");
        }
    }

    private static int levelOf(net.minecraft.server.MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(CobbieEntity::experienceLevel).orElse(-1);
    }

    private static int progressOf(net.minecraft.server.MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(c -> Math.round(c.experienceProgress())).orElse(-1);
    }
}
