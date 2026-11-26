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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * The block he is standing on, and what is under it.
 *
 * <p>Three phases, and the second and third are the same world twice: a platform with a nine-block drop to
 * the floor. At full health a fall of nine costs six damage and any player would dig it — so he must dig
 * it, and the test watches him take the six. At four health the same dig costs more than he has, so it is
 * refused with the drop and his health in the reason and the block is still standing. Same geometry, same
 * verb, opposite verdict, decided by the number the game reports for his health — which is the point: a
 * guessed constant would answer the same way twice.
 *
 * <p>The first phase is the one the brief names first: a shaft straight down over lava, refused without
 * looking at health at all.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=FatalDig}.
 */
public final class CobbieFatalDigTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);
            CobbieTestSupport.command(server, "cobbie stay");

            // ---- 1. a shaft straight down over lava ----
            Platform lava = platform(server, 8);
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + lava.stand.getX() + " " + (lava.stand.getY() - 6) + " "
                        + lava.stand.getZ() + " minecraft:lava");
                return null;
            });
            String lavaVerdict = dig(server, lava.stand);
            boolean lavaStillStone = server.computeOnServer(s -> s.overworld().getBlockState(lava.stand)
                    .is(Blocks.STONE));
            if (!lavaStillStone) {
                throw new AssertionError("He broke the block under his feet over lava (" + lavaVerdict + ")");
            }
            requireReason(lavaVerdict, "lava", "the dig over lava");

            // ---- 2. a nine-block drop at full health: allowed, and he lands on it ----
            Platform drop = platform(server, 9);
            server.computeOnServer(s -> {
                BlockPos landing = drop.stand.below(9);
                CobbieTestSupport.command(s, "setblock " + landing.getX() + " " + landing.getY() + " "
                        + landing.getZ() + " minecraft:stone");
                CobbieTestSupport.cobbie(s).orElseThrow().setHealth(20.0f);
                return null;
            });
            float beforeHealthy = health(server);
            String healthyVerdict = dig(server, drop.stand);
            boolean dug = server.computeOnServer(s -> s.overworld().getBlockState(drop.stand).isAir());
            // He is in the air the instant the block goes; the landing and its damage are a second later.
            CobbieTestSupport.elapsed(server, 40);
            float afterHealthy = health(server);
            if (!dug) {
                throw new AssertionError("He refused a nine-block drop at " + beforeHealthy + " health, where the"
                        + " fall costs six: " + healthyVerdict);
            }
            if (afterHealthy > beforeHealthy - 5.0f) {
                throw new AssertionError("He dug the block under his feet and did not fall: health went " + beforeHealthy
                        + " -> " + afterHealthy + ", and a nine-block drop costs six");
            }

            // ---- 3. the same nine-block drop at four health: refused ----
            Platform low = platform(server, 9);
            server.computeOnServer(s -> {
                BlockPos landing = low.stand.below(9);
                CobbieTestSupport.command(s, "setblock " + landing.getX() + " " + landing.getY() + " "
                        + landing.getZ() + " minecraft:stone");
                CobbieTestSupport.cobbie(s).orElseThrow().setHealth(4.0f);
                return null;
            });
            String lowVerdict = dig(server, low.stand);
            boolean lowStillStone = server.computeOnServer(s -> s.overworld().getBlockState(low.stand)
                    .is(Blocks.STONE));
            BlockPos endedAt = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            if (!lowStillStone) {
                throw new AssertionError("At four health he broke the block under his feet with a nine-block drop"
                        + " below him: " + lowVerdict);
            }
            if (!endedAt.equals(low.rope)) {
                throw new AssertionError("He refused the dig at " + lowVerdict + " but left the platform anyway: he is"
                        + " at " + endedAt.toShortString() + ", not " + low.rope.toShortString());
            }
            requireReason(lowVerdict, "drop", "the dig at four health");
            requireReason(lowVerdict, "health", "the dig at four health");

            System.out.println("[gametest] fatal dig ok: lava refused (" + lavaVerdict + "); nine-block drop dug at"
                    + " 20 health (" + beforeHealthy + " -> " + afterHealthy + ", " + healthyVerdict + "); refused at"
                    + " 4 health (" + lowVerdict + ")");
        }
    }

    /** A platform he stands on, {@code height} blocks above the flat ground, and where he stands on it. */
    private record Platform(BlockPos stand, BlockPos rope) {}

    private static Platform platform(TestServerContext server, int height) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos base = cobbie.blockPosition().offset(8, 0, 8);
            int ground = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
            int top = ground + height;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    CobbieTestSupport.command(s, "setblock " + (base.getX() + dx) + " " + (top - 1) + " "
                            + (base.getZ() + dz) + " minecraft:stone");
                }
            }
            cobbie.teleportTo(s.overworld(), base.getX() + 0.5, top, base.getZ() + 0.5,
                    Set.of(), cobbie.getYRot(), cobbie.getXRot(), true);
            return new Platform(new BlockPos(base.getX(), top - 1, base.getZ()), new BlockPos(base.getX(), top, base.getZ()));
        });
    }

    /**
     * Ask him to break the block under his feet, and answer with that plan's own report.
     *
     * <p>Bound to the plan id, not to "is the last plan finished": the previous dig is already finished, so
     * the looser question answers yes before the new plan has even been created — the reading-before-the-
     * change mistake this suite has a helper for and a paragraph about.
     */
    private static String dig(TestServerContext server, BlockPos floor) {
        long before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
        server.computeOnServer(s -> {
            CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("break the block under your feet", List.of(
                    new ActionIntent(ActionVocabulary.MINE, "minecraft:stone", floor.getX(), floor.getY(),
                            floor.getZ(), "")));
            return null;
        });
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> {
                        TaskPlan plan = c.planAfter(before);
                        return plan != null && plan.isFinished();
                    }).orElse(false), 400);
        } catch (Throwable t) {
            throw new AssertionError("The dig of " + floor.toShortString() + " never settled", t);
        }
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.planAfter(before)).map(TaskPlan::report).orElse("no plan"));
    }

    private static float health(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(CobbieEntity::getHealth).orElse(-1.0f));
    }

    private static void requireReason(String report, String fragment, String what) {
        if (!report.toLowerCase(java.util.Locale.ROOT).contains(fragment)) {
            throw new AssertionError("The refusal of " + what + " does not mention \"" + fragment + "\": " + report);
        }
    }
}
