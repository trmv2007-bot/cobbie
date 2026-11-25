package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

import java.util.List;

/**
 * Riding a boat or a minecart and getting out again.
 *
 * <p>Riding, not driving: a fake player cannot even board one, so steering is impossible by
 * construction. What is asserted here is what a companion can honestly do — he is a passenger of the
 * craft, and the craft carries him when it moves.
 *
 * <p>Both craft get the same three assertions, because 0.47.0's note claimed minecarts on the strength
 * of a shared code path and never ran one: the cart's own passenger list contains him, the cart carries
 * him when it moves along its rails, and asking for anything else gets him out.
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

            // --- The minecart half. Same three claims, on a cart and a rail line. ---
            // 0.47.0's note extends riding to minecarts on the strength of a shared code path and no
            // run; this is the run. A cart on rails rather than in water, moved by a powered rail line
            // rather than teleported, so what is tested is the cart carrying him.
            Entity cart = server.computeOnServer(s -> {
                ServerLevel level = s.overworld();
                var owner = CobbieTestSupport.owner(s);
                int x = owner.blockPosition().getX();
                int y = owner.blockPosition().getY() - 1;
                int z = owner.blockPosition().getZ() - 5;
                CobbieTestSupport.command(s, "fill " + (x - 2) + " " + y + " " + z + " " + (x + 14) + " " + y + " "
                        + z + " minecraft:rail[shape=east_west]");
                // Two powered rails where the cart waits, unpowered until he is aboard: powered rails
                // push a cart on their own, and one that launched before he got in would prove nothing.
                CobbieTestSupport.command(s, "fill " + (x + 1) + " " + y + " " + z + " " + (x + 2) + " " + y + " "
                        + z + " minecraft:powered_rail[shape=east_west,powered=false]");
                // Summoned onto the rail the way a player places one, rather than setPos'd in the air:
                // the first attempt put the cart one fraction above the track and it never rolled.
                CobbieTestSupport.command(s, "summon minecraft:minecart " + (x + 1.5) + " " + (y + 1) + " " + (z + 0.5)
                        + " {Motion:[0.0,0.0,0.0]}");
                return level.getEntitiesOfClass(Entity.class,
                                new net.minecraft.world.phys.AABB(new BlockPos(x + 1, y, z)).inflate(2.0),
                                e -> e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart)
                        .stream().findFirst().orElse(null);
            });
            if (cart == null) throw new AssertionError("No minecart on the rails; the fixture did not place one");

            String cartRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "get in the minecart",
                    List.of(new ActionIntent(ActionVocabulary.BOARD, "minecart", 0, 0, 0, ""))));
            if (cartRefusal != null) throw new AssertionError("board refused: " + cartRefusal);
            CobbieTestSupport.awaitPlanSettled(server, 200);

            // 1. He is a passenger — asserted by the cart's own list, not by a return value.
            boolean inCart = server.computeOnServer(s -> {
                var craft = s.overworld().getEntity(cart.getUUID());
                var cobbie = CobbieTestSupport.cobbie(s).orElse(null);
                return craft != null && cobbie != null && cobbie.getVehicle() == craft
                        && craft.getPassengers().contains(cobbie);
            });
            if (!inCart) {
                throw new AssertionError("He is not a passenger of the minecart; he is at "
                        + where(server) + " and the cart holds " + cartSeats(server, cart));
            }

            // 2. The cart carries him when it moves. The rail line is powered only now, so the push
            // comes from the track and not from a teleport: a passenger who falls out on the first
            // tick of real movement cannot be caught by a boolean, and a cart that never rolls would
            // pass a position copy.
            double[] cartBefore = position(server);
            server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                int x = owner.blockPosition().getX();
                int y = owner.blockPosition().getY() - 1;
                int z = owner.blockPosition().getZ() - 5;
                // A redstone block beside each powered rail is what keeps it powered; setting the
                // blockstate by hand would be undone by the next neighbour update.
                CobbieTestSupport.command(s, "setblock " + (x + 1) + " " + y + " " + (z + 1) + " minecraft:redstone_block");
                CobbieTestSupport.command(s, "setblock " + (x + 2) + " " + y + " " + (z + 1) + " minecraft:redstone_block");
                // A flat powered rail does not launch a cart at rest, so it gets the push by hand and
                // the power keeps it rolling: a cart that coasts on real rails is the closest this world
                // gets to a player driving one.
                var craft = s.overworld().getEntity(cart.getUUID());
                if (craft != null) craft.setDeltaMovement(0.4, 0.0, 0.0);
                return null;
            });
            double cartMoved = 0;
            for (int tick = 0; tick < 40 && cartMoved < 1.0; tick++) {
                context.waitTicks(5);
                double[] now = position(server);
                cartMoved = Math.sqrt(Math.pow(now[0] - cartBefore[0], 2) + Math.pow(now[2] - cartBefore[2], 2));
            }
            if (cartMoved < 1.0) {
                // Say which failure this is: a cart that will not roll, or a body it left behind.
                String cartWhere = server.computeOnServer(s -> {
                    Entity craft = s.overworld().getEntity(cart.getUUID());
                    return craft == null ? "no cart" : craft.getX() + ", " + craft.getY() + ", " + craft.getZ();
                });
                throw new AssertionError("The minecart moved but he stayed: " + cartBefore[0] + " -> "
                        + position(server)[0] + " (moved " + String.format("%.2f", cartMoved) + " blocks); the cart "
                        + "is at " + cartWhere + " holding " + cartSeats(server, cart) + ", he is at " + where(server));
            }

            // 3. Anything else gets him out — the same dismount path the boat proves.
            String leaveCart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "get back to me",
                    List.of(new ActionIntent(ActionVocabulary.FOLLOW, "", 0, 0, 0, ""))));
            if (leaveCart != null) throw new AssertionError("follow refused while riding: " + leaveCart);
            CobbieTestSupport.awaitPlanSettled(server, 200);
            boolean stillInCart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getVehicle() != null).orElse(false));
            if (stillInCart) {
                throw new AssertionError("He asked to follow and stayed in the minecart; he is at " + where(server));
            }

            System.out.println("[gametest] ride ok: boarded the minecart, was a passenger and was carried "
                    + String.format("%.1f", cartMoved) + " blocks along the rails, then got out when asked to follow");
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

    /** What the cart we made is actually carrying — the same evidence the boat's failure names. */
    private static String cartSeats(TestServerContext server, Entity cart) {
        return server.computeOnServer(s -> {
            Entity craft = s.overworld().getEntity(cart.getUUID());
            return craft == null ? "no cart" : craft.getPassengers().size() + " passenger(s)";
        });
    }
}
