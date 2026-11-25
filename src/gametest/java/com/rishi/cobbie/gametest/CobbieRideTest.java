package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

import java.util.List;

/**
 * Riding a boat and getting out again.
 *
 * <p>Riding, not driving: a fake player cannot even board one, so steering is impossible by
 * construction. What is asserted here is what a companion can honestly do — he is a passenger of the
 * craft, and the craft carries him when it moves.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Ride}.
 */
public final class CobbieRideTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // A boat in a pool, so it has water to float on and can be pushed.
            server.computeOnServer(s -> {
                var start = CobbieTestSupport.owner(s).blockPosition();
                CobbieTestSupport.command(s, "fill " + (start.getX() + 2) + " " + (start.getY() - 1) + " "
                        + (start.getZ() + 2) + " " + (start.getX() + 8) + " " + (start.getY() - 1) + " "
                        + (start.getZ() + 6) + " minecraft:water");
                return null;
            });
            Entity boat = server.computeOnServer(s -> {
                ServerLevel level = s.overworld();
                var owner = CobbieTestSupport.owner(s);
                var created = EntityTypes.OAK_BOAT.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                created.setPos(owner.getX() + 3.5, owner.getY(), owner.getZ() + 3.5);
                level.addFreshEntity(created);
                return created;
            });

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "get in the boat",
                    List.of(new ActionIntent(ActionVocabulary.BOARD, "boat", 0, 0, 0, ""))));
            if (refusal != null) throw new AssertionError("board refused: " + refusal);
            CobbieTestSupport.awaitPlanSettled(server, 200);

            // 1. He is a passenger — asserted by the craft, not by a return value.
            boolean carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getVehicle() != null && c.getVehicle().getPassengers().contains(c))
                    .orElse(false));
            if (!carried) {
                throw new AssertionError("He is not a passenger of the boat; he is at "
                        + where(server) + " and the boat holds " + seats(server));
            }

            // 2. The boat carries him when it moves.
            double[] before = position(server);
            server.computeOnServer(s -> {
                var craft = s.overworld().getEntity(boat.getUUID());
                if (craft != null) craft.setPos(craft.getX() + 2.0, craft.getY(), craft.getZ());
                return null;
            });
            context.waitTicks(5);
            double[] after = position(server);
            double moved = Math.sqrt(Math.pow(after[0] - before[0], 2) + Math.pow(after[2] - before[2], 2));
            if (moved < 1.0) {
                throw new AssertionError("The boat moved but he stayed: " + before[0] + " -> " + after[0]);
            }

            // 3. Anything else gets him out — a companion stuck in a boat is stuck in the water.
            String walk = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "get back to me",
                    List.of(new ActionIntent(ActionVocabulary.FOLLOW, "", 0, 0, 0, ""))));
            if (walk != null) throw new AssertionError("follow refused while riding: " + walk);
            CobbieTestSupport.awaitPlanSettled(server, 200);
            boolean still = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getVehicle() != null).orElse(false));
            if (still) {
                throw new AssertionError("He asked to follow and stayed in the boat; he is at " + where(server));
            }

            System.out.println("[gametest] ride ok: boarded the boat, was a passenger and was carried "
                    + String.format("%.1f", moved) + " blocks, then got out when asked to follow");
        }
    }

    private static double[] position(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> new double[]{c.getX(), c.getY(), c.getZ()})
                .orElse(new double[]{0, 0, 0}));
    }

    private static String seats(TestServerContext server) {
        return server.computeOnServer(CobbieRideTest::seats);
    }

    private static String seats(MinecraftServer server) {
        return server.overworld().getEntitiesOfClass(Entity.class,
                        new net.minecraft.world.phys.AABB(CobbieTestSupport.owner(server).blockPosition())
                                .inflate(20.0),
                        e -> e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat)
                .stream()
                .map(e -> e.getPassengers().size() + " passenger(s)")
                .findFirst().orElse("no boat");
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.blockPosition().toShortString() + " vehicle=" + c.getVehicle())
                .orElse("nowhere near the owner"));
    }
}
