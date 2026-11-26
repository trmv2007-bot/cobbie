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

import java.util.List;
import java.util.Set;

/**
 * A walk to somewhere below him that only a killing fall can reach.
 *
 * <p>The pathfinder's own limit for a descent is the game's "comfortable fall distance",
 * {@code floor(health + 3)} — and by the game's own damage arithmetic that is a fall costing exactly his
 * health. At 20 health it will plan a 23-block drop and 20 damage: the last fall he ever takes. So the first
 * assertion is the number itself, asked of the entity in a real world: his limit is 22, one short of the
 * drop the game would have allowed. The second is the refusal: asked to walk down to the ground from a
 * platform 23 blocks up, no route exists within that limit, the step fails, and the failure says how far
 * below him the target is and what a fall of that far costs. The third is that the limit follows his
 * health rather than a constant: at 8 health it drops to 10.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=FatalDrop}.
 */
public final class CobbieFatalDropTest implements FabricClientGameTest {

    /** The drop the platform is built to: one block deeper than the game would call comfortable at 20 health. */
    private static final int DROP = 23;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");

            BlockPos ground = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setHealth(cobbie.getMaxHealth());
                BlockPos base = cobbie.blockPosition().offset(-8, 0, -8);
                int surface = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        base.getX(), base.getZ());
                int top = surface + DROP;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        CobbieTestSupport.command(s, "setblock " + (base.getX() + dx) + " " + (top - 1) + " "
                                + (base.getZ() + dz) + " minecraft:stone");
                    }
                }
                cobbie.teleportTo(s.overworld(), base.getX() + 0.5, top, base.getZ() + 0.5,
                        Set.of(), cobbie.getYRot(), cobbie.getXRot(), true);
                return new BlockPos(base.getX(), surface, base.getZ());
            });

            int limit = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getMaxFallDistance());
            float health = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());
            if (health != 20.0f) {
                throw new AssertionError("He is at " + health + " health, not the 20 this boundary is about");
            }
            if (limit != 22) {
                throw new AssertionError("The deepest drop he will path down is " + limit + " blocks at 20 health;"
                        + " a fall of that far costs " + fallCost(limit) + " and the game's own limit is 23, worth"
                        + " exactly his whole bar");
            }

            long before = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().latestPlanId());
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().offerPlan("come down to me", List.of(
                        new ActionIntent(ActionVocabulary.WALK_TO, "", ground.getX(), ground.getY(),
                                ground.getZ(), "")));
                return null;
            });
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> {
                            TaskPlan plan = c.planAfter(before);
                            return plan != null && plan.isFinished();
                        }).orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("The walk to the ground below never settled", t);
            }

            String report = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.planAfter(before)).map(TaskPlan::report).orElse("no plan"));
            String status = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.planAfter(before)).map(p -> p.status().toString()).orElse("no plan"));
            BlockPos endedAt = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            float healthAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());

            if ("COMPLETED".equals(status)) {
                throw new AssertionError("He reported walking down a " + DROP + "-block drop: " + report);
            }
            if (endedAt.getY() < ground.getY() + DROP) {
                throw new AssertionError("He descended anyway and is at " + endedAt.toShortString() + " — the refusal"
                        + " came after the fall: " + report);
            }
            if (healthAfter < health) {
                throw new AssertionError("He took fall damage he should never have been allowed to take: " + health
                        + " -> " + healthAfter + " (" + report + ")");
            }
            String lower = report.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("drop") || !lower.contains("health")) {
                throw new AssertionError("The walk was refused without saying how far below he was and what it"
                        + " would cost: " + report);
            }

            // ---- and the limit is his health, not a constant ----
            int woundedLimit = server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().setHealth(8.0f);
                return CobbieTestSupport.cobbie(s).orElseThrow().getMaxFallDistance();
            });
            if (woundedLimit != 10) {
                throw new AssertionError("At 8 health the deepest drop he will path down is " + woundedLimit
                        + " blocks, and a fall costing less than 8 health is 10");
            }

            System.out.println("[gametest] fatal drop ok: limit " + limit + " of the game's " + (int) Math.floor(health + 3)
                    + " at " + health + " health, a " + DROP + "-block walk refused (" + report + "), he stayed at "
                    + endedAt.toShortString() + " on " + healthAfter + " health; at 8 health the limit is "
                    + woundedLimit);
        }
    }

    /** The game's arithmetic for a fall, without a world: nothing for three blocks, then a block a block. */
    private static int fallCost(int distance) {
        return distance <= 3 ? 0 : (int) Math.ceil(distance - 3);
    }
}
