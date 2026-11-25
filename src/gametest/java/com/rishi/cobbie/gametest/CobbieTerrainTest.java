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
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Real terrain, not a corridor: a course with a door, a gate, a drop and water, and then the same idea
 * gone wrong.
 *
 * <p>Every other class proves a verb on ground it built to be easy. What a player hits in their first
 * hour is the compound case — a wall with a door in it, a fence with a gate, a ledge, a river — where
 * A* finds a route and the body has to actually walk it. That is a different failure from "no path",
 * and nobody has tested it under adversity.
 *
 * <p>The assertion is **where he ends up**, never that a path was found. Two outcomes are allowed: he
 * arrives, or he stops and says what stopped him. Looping is a failure, and so is a claim of arrival
 * made from a distance.
 *
 * <p>Three stages, in one world:
 * <ol>
 *   <li>the course, walked end to end;</li>
 *   <li>the same corridor with the door replaced by solid stone the moment he sets off;</li>
 *   <li>the doorway filled by a mob that will not move.</li>
 * </ol>
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Terrain}.
 */
public final class CobbieTerrainTest implements FabricClientGameTest {

    /** How far along the lane each obstacle sits, from where he starts. */
    private static final int DOOR_AT = 5;
    private static final int LEDGE_END = 9;
    private static final int GATE_AT = 13;
    private static final int WATER_AT = 17;
    private static final int GOAL_AT = 23;

    /** Close enough to call it arrived: two blocks, because a target is a place and not a pixel. */
    private static final double ARRIVED = 4.0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");

            // --- 1. The course. ---
            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            BlockPos goal = buildCourse(server, start);

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk to the far end of the yard", List.of(new ActionIntent(
                            ActionVocabulary.WALK_TO, "", goal.getX(), goal.getY(), goal.getZ(), ""))));
            if (refusal != null) throw new AssertionError("walk-to refused: " + refusal);

            boolean arrived = awaitArrival(server, goal, 1400);
            String where = where(server);
            if (!arrived) {
                // Allowed only if he stopped and said why — never while still trying, never claiming it.
                String stoppedWith = stoppedWith(server);
                throw new AssertionError("He never reached the end of the course. He is " + where
                        + "; he said " + stoppedWith + ". A run that stops must name what stopped it, and "
                        + "a run that is still walking is neither of the two allowed outcomes.");
            }
            System.out.println("[gametest] terrain ok: walked the door, ledge, gate and water to "
                    + where + ", saying " + stoppedWith(server));

            // --- 2. The door becomes wall while he walks. ---
            BlockPos sealedStart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            BlockPos sealedGoal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int z = base.getZ() + 14;
                CobbieTestSupport.command(s, "fill " + (base.getX() - 16) + " " + base.getY() + " " + z + " "
                        + (base.getX() + 16) + " " + (base.getY() + 3) + " " + z + " minecraft:stone");
                // One doorway, then sealed with stone a moment later: there is no route at all.
                CobbieTestSupport.command(s, "setblock " + base.getX() + " " + base.getY() + " " + z
                        + " minecraft:stone");
                return new BlockPos(base.getX(), base.getY(), z + 4);
            });
            String sealedRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk through the wall", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            sealedGoal.getX(), sealedGoal.getY(), sealedGoal.getZ(), ""))));
            if (sealedRefusal != null) throw new AssertionError("walk-to refused with a sealed wall: " + sealedRefusal);

            assertStopsRatherThanLoops(context, server, sealedGoal, "a wall with no doorway",
                    sealedStart.distSqr(sealedGoal));

            // --- 3. A mob standing in the only gap. ---
            BlockPos mobStart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            BlockPos mobGoal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int z = base.getZ() + 14;
                CobbieTestSupport.command(s, "fill " + (base.getX() - 16) + " " + base.getY() + " " + z + " "
                        + (base.getX() + 16) + " " + (base.getY() + 3) + " " + z + " minecraft:stone");
                // A three-wide hole, filled with animals that do not move aside.
                CobbieTestSupport.command(s, "fill " + (base.getX() - 1) + " " + base.getY() + " " + z + " "
                        + (base.getX() + 1) + " " + (base.getY() + 2) + " " + z + " minecraft:air");
                for (int dx = -1; dx <= 1; dx++) {
                    CobbieTestSupport.command(s, "summon minecraft:cow " + (base.getX() + dx + 0.5) + " "
                            + base.getY() + " " + (z + 0.5) + " {NoAI:1b,PersistenceRequired:1b}");
                }
                return new BlockPos(base.getX(), base.getY(), z + 4);
            });
            String mobRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk past the animals", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                            mobGoal.getX(), mobGoal.getY(), mobGoal.getZ(), ""))));
            if (mobRefusal != null) throw new AssertionError("walk-to refused with animals in the gap: " + mobRefusal);

            assertStopsRatherThanLoops(context, server, mobGoal, "three animals filling the only gap",
                    mobStart.distSqr(mobGoal));
        }
    }

    /**
     * The gate shuts behind him and a second door is closed ahead. Arriving means he kept walking and
     * opened the door in front of him rather than turning round to where he came from.
     */
    private static void closedBehind(ClientGameTestContext context, TestServerContext server) {
        BlockPos goal = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos base = cobbie.blockPosition();
            int x = base.getX();
            int y = base.getY();
            int z = base.getZ();
            int gateZ = z + 4;
            int doorZ = z + 10;
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + (z - 1) + " " + (x + 3) + " "
                    + (y + 3) + " " + (z - 1) + " minecraft:stone");
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + gateZ + " " + (x + 3) + " "
                    + (y + 2) + " " + gateZ + " minecraft:oak_fence");
            CobbieTestSupport.command(s, "setblock " + x + " " + y + " " + gateZ
                    + " minecraft:oak_fence_gate[facing=north,open=true]");
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + doorZ + " " + (x + 3) + " "
                    + (y + 4) + " " + doorZ + " minecraft:stone");
            CobbieTestSupport.command(s, "setblock " + x + " " + y + " " + doorZ
                    + " minecraft:oak_door[half=lower,facing=north,open=false]");
            CobbieTestSupport.command(s, "setblock " + x + " " + (y + 1) + " " + doorZ
                    + " minecraft:oak_door[half=upper,facing=north,open=false]");
            BlockPos target = new BlockPos(x, y, z + 16);
            String refusal = cobbie.offerPlan("walk down the corridor", List.of(new ActionIntent(
                    ActionVocabulary.WALK_TO, "", target.getX(), target.getY(), target.getZ(), "")));
            if (refusal != null) throw new AssertionError("walk-to refused in the corridor: " + refusal);
            return target;
        });

        // Shut the gate behind him while he walks: the way back is gone and the door ahead is the only
        // route. Without this, "walked back the way he came" would pass the arrival check.
        boolean past = false;
        for (int tick = 0; tick < 300 && !past; tick++) {
            context.waitTicks(2);
            past = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::isBusy).orElse(false)) && server.computeOnServer(s ->
                    CobbieTestSupport.cobbie(s).map(c -> c.blockPosition().getZ() > c.getOnPos().getZ() + 2)
                            .orElse(false));
        }
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos p = cobbie.blockPosition();
            CobbieTestSupport.command(s, "setblock " + p.getX() + " " + p.getY() + " " + (p.getZ() - 2)
                    + " minecraft:oak_fence_gate[facing=north,open=false]");
            CobbieTestSupport.command(s, "setblock " + p.getX() + " " + p.getY() + " " + (p.getZ() - 3)
                    + " minecraft:stone");
            return null;
        });

        if (!awaitArrival(server, goal, 900)) {
            throw new AssertionError("The gate closed behind him and he did not go on through the door ahead; he is "
                    + where(server) + " saying \"" + stoppedWith(server) + "\"");
        }
        System.out.println("[gametest] terrain ok: gate shut behind him, went on through the closed door to "
                + where(server));
    }

    /**
     * A two-block drop where the direct line down lands on a cactus. Arriving unhurt means the route took
     * the safe landing; a stop with a reason is also allowed. Arriving hurt is not.
     */
    private static void unsafeLanding(ClientGameTestContext context, TestServerContext server) {
        BlockPos goal = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos base = cobbie.blockPosition();
            int x = base.getX();
            int y = base.getY();
            int z = base.getZ();
            CobbieTestSupport.command(s, "fill " + (x - 1) + " " + y + " " + (z + 2) + " " + (x + 3) + " "
                    + (y + 1) + " " + (z + 8) + " minecraft:stone");
            CobbieTestSupport.command(s, "setblock " + (x + 1) + " " + y + " " + (z + 9) + " minecraft:cactus");
            BlockPos target = new BlockPos(x + 3, y, z + 11);
            String refusal = cobbie.offerPlan("get down to the far side", List.of(new ActionIntent(
                    ActionVocabulary.WALK_TO, "", target.getX(), target.getY(), target.getZ(), "")));
            if (refusal != null) throw new AssertionError("walk-to refused at the ledge: " + refusal);
            return target;
        });

        float healthBefore = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());
        boolean arrived = awaitArrival(server, goal, 900);
        String where = where(server);
        float healthAfter = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().getHealth());
        String said = stoppedWith(server);

        if (arrived && healthAfter < healthBefore - 0.01f) {
            throw new AssertionError("He arrived via the cactus: health " + healthBefore + " -> " + healthAfter
                    + " at " + where + ". Arriving hurt is not the safe version of arriving.");
        }
        if (!arrived) {
            throw new AssertionError("He did not get down to the far side; he is " + where + " saying \"" + said
                    + "\", health " + healthBefore + " -> " + healthAfter);
        }
        System.out.println("[gametest] terrain ok: two-block drop, arrived unhurt (" + healthAfter
                + ") without the cactus, saying \"" + said + "\"");
    }

    /**
     * Water three deep: not a wade along the bottom. Swimming is asserted as a fact about the body — in
     * water <em>and</em> at the surface — because arriving alone would pass whichever way he crossed.
     */
    private static void deepWater(ClientGameTestContext context, TestServerContext server) {
        BlockPos goal = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos base = cobbie.blockPosition();
            int x = base.getX();
            int y = base.getY();
            int z = base.getZ();
            int waterZ = z + 4;
            CobbieTestSupport.command(s, "fill " + (x - 4) + " " + (y - 3) + " " + waterZ + " " + (x + 4) + " "
                    + (y + 1) + " " + (waterZ + 4) + " minecraft:water");
            CobbieTestSupport.command(s, "fill " + (x - 4) + " " + y + " " + (waterZ + 5) + " " + (x + 4) + " "
                    + (y + 2) + " " + (waterZ + 5) + " minecraft:stone");
            BlockPos target = new BlockPos(x, y + 3, waterZ + 8);
            String refusal = cobbie.offerPlan("swim across and get out", List.of(new ActionIntent(
                    ActionVocabulary.WALK_TO, "", target.getX(), target.getY(), target.getZ(), "")));
            if (refusal != null) throw new AssertionError("walk-to refused at the water: " + refusal);
            return target;
        });

        int surfaceY = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition().getY()) + 1;
        boolean sawSwim = false;
        boolean arrived = false;
        for (int tick = 0; tick < 460; tick++) {
            context.waitTicks(2);
            boolean[] state = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return new boolean[] {
                        cobbie.isInWater() && cobbie.blockPosition().getY() >= surfaceY,
                        cobbie.blockPosition().distSqr(goal) <= ARRIVED && cobbie.onGround()};
            });
            if (state[0]) sawSwim = true;
            if (state[1]) {
                arrived = true;
                break;
            }
        }

        if (!arrived) {
            throw new AssertionError("He never got out of the deep water; he is " + where(server) + " saying \""
                    + stoppedWith(server) + "\", swam=" + sawSwim);
        }
        if (!sawSwim) {
            throw new AssertionError("He arrived but was never seen swimming at the surface, so three-deep water "
                    + "was crossed some other way and this stage's claim is not proven. He is " + where(server));
        }
        System.out.println("[gametest] terrain ok: swam three-deep water at the surface and climbed out to "
                + where(server));
    }

    /**
     * A yard in front of him: a wall with a closed door he has to open, a step down off the ledge it
     * stands on, a fence with a closed gate, a water channel, and the far end.
     */
    private static BlockPos buildCourse(TestServerContext server, BlockPos start) {
        return server.computeOnServer(s -> {
            int x = start.getX();
            int y = start.getY();
            int z = start.getZ();
            // A raised walkway, because a two-block drop has to start from higher ground: he can always
            // go down, and never up more than one.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + (z - 1) + " " + (x + 3) + " "
                    + (y + 1) + " " + (z + LEDGE_END) + " minecraft:stone");
            // Wall with a closed door, on the walkway.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + (y + 2) + " " + (z + DOOR_AT) + " "
                    + (x + 3) + " " + (y + 5) + " " + (z + DOOR_AT) + " minecraft:stone");
            CobbieTestSupport.command(s, "setblock " + x + " " + (y + 2) + " " + (z + DOOR_AT)
                    + " minecraft:oak_door[half=lower,facing=north,open=false]");
            CobbieTestSupport.command(s, "setblock " + x + " " + (y + 3) + " " + (z + DOOR_AT)
                    + " minecraft:oak_door[half=upper,facing=north,open=false]");
            // A fence with a closed gate, at ground level.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + (z + GATE_AT) + " " + (x + 3) + " "
                    + (y + 2) + " " + (z + GATE_AT) + " minecraft:oak_fence");
            CobbieTestSupport.command(s, "setblock " + x + " " + y + " " + (z + GATE_AT)
                    + " minecraft:oak_fence_gate[facing=north,open=false]");
            // A water channel three wide, two deep: he has to swim it, not step over it.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + (y - 2) + " " + (z + WATER_AT) + " "
                    + (x + 3) + " " + (y + 1) + " " + (z + WATER_AT + 2) + " minecraft:water");
            // A lip on the far side so leaving the water means climbing one block, not two.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + (z + WATER_AT + 3) + " " + (x + 3)
                    + " " + (y + 1) + " " + (z + WATER_AT + 3) + " minecraft:stone");
            return new BlockPos(x, y + 2, z + GOAL_AT);
        });
    }

    /**
     * The adversarial rule: he stops. Not looping, not reporting an arrival he did not make. A stop with
     * no reason is the other half of the same defect, so the line he said is part of the assertion.
     */
    private static void assertStopsRatherThanLoops(ClientGameTestContext context, TestServerContext server,
            BlockPos goal, String obstacle, double distanceAtStart) {
        // Long enough that a real attempt has clearly failed, short enough that a loop is caught.
        boolean stopped = false;
        for (int tick = 0; tick < 360 && !stopped; tick++) {
            context.waitTicks(5);
            stopped = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> !c.isBusy()).orElse(false));
        }

        String where = where(server);
        String said = stoppedWith(server);
        boolean atGoal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.blockPosition().distSqr(goal) <= ARRIVED).orElse(false));

        if (!stopped) {
            throw new AssertionError("He was still walking after 90 s at " + where + " with " + obstacle
                    + " in the way — that is the loop this test exists to catch");
        }
        if (atGoal) {
            throw new AssertionError("He reported being at the goal with " + obstacle + " in the way (" + where
                    + ") — arrival from a distance is the claim this test refuses");
        }
        if (said == null || said.isBlank()) {
            throw new AssertionError("He stopped with " + obstacle + " in the way but said nothing about it ("
                    + where + ")");
        }
        System.out.println("[gametest] terrain adversarial ok: " + obstacle + " — stopped at " + where
                + " saying \"" + said + "\" (started " + Math.round(Math.sqrt(distanceAtStart)) + " blocks away)");
    }

    private static boolean awaitArrival(TestServerContext server, BlockPos goal, int ticks) {
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.blockPosition().distSqr(goal) <= ARRIVED && c.onGround())
                    .orElse(false), ticks);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.level().dimension() == Level.OVERWORLD
                        ? c.blockPosition().toShortString() + " onGround=" + c.onGround()
                        : "in another dimension")
                .orElse("nowhere findable"));
    }

    private static String stoppedWith(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastSpokenLine).orElse(""));
    }
}
