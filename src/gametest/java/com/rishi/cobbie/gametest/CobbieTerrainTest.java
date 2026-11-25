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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

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
 * arrives, or he stops and says what stopped him. Looping is a failure, so is a claim of arrival made
 * from a distance, and so is a death: a companion who suffocated on the way, or arrived dead, passes
 * nothing. Every stage prints his health, so a reader can see whether the body survived the route
 * rather than only that the coordinates match.
 *
 * <p>Stage 1 also proves <em>how</em> he got there. Arrival alone cannot: in an open yard he can walk
 * round every obstacle and still end at the goal. So the lane is closed by a wall across the door's
 * plane, wider than anything he can plan around, with the closed door as its only gap, and his
 * {@code blockPosition} is sampled every two ticks while he walks. A green means at least one sample
 * stood in the door's own block column, and the door was seen open at some point during the walk — he
 * went through the doorway, not round it. The door is built closed, so opening it is part of the claim;
 * vanilla shuts it again the moment he is past, which is why the state at the crossing sample is
 * recorded but not required to be open.
 *
 * <p>Nothing in this class may be built over his own column. The first version of the course filled the
 * lane from one block behind him, which put stone at his feet and his head and killed him before the
 * walk began — the test was the obstacle.
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

    /**
     * Half-width of the wall on the door's plane. Wide enough that going round it costs 32 blocks more
     * than the straight line, past his {@code navigation_range} of 24 — the same 33-wide wall
     * {@code CobbieDoorTest} uses, because a route round the end has to be impossible, not merely long.
     */
    private static final int WALL_HALF_WIDTH = 16;

    /** Tall enough to be hopeless: he climbs one block, this is nine above the walkway surface. */
    private static final int WALL_TOP = 8;

    /** Sample every this many ticks while he walks: ~0.4 blocks of travel, well inside one doorway. */
    private static final int SAMPLE_TICKS = 2;

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
            Course course = buildCourse(server, start);
            BlockPos goal = course.goal();
            requireAlive(server, goal, "stage 1, before the walk");

            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("walk to the far end of the yard", List.of(new ActionIntent(
                            ActionVocabulary.WALK_TO, "", goal.getX(), goal.getY(), goal.getZ(), ""))));
            if (refusal != null) throw new AssertionError("walk-to refused: " + refusal);

            Walk walk = walkTheCourse(context, server, goal, course.doorLower(), 1400);
            Reading endOfCourse = requireAlive(server, goal, "stage 1, at the far end");
            String where = endOfCourse.where();
            if (!walk.arrived()) {
                // Allowed only if he stopped and said why — never while still trying, never claiming it.
                String stoppedWith = stoppedWith(server);
                throw new AssertionError("He never reached the end of the course. He is " + where
                        + " alive at " + endOfCourse.healthText() + "; he said " + stoppedWith + ". Crossing: "
                        + walk.crossingText() + ". A run that stops must name what stopped it, and a run that is "
                        + "still walking is neither of the two allowed outcomes.");
            }
            if (walk.crossing() == null) {
                throw new AssertionError("He arrived at the far end (" + where + " alive at "
                        + endOfCourse.healthText() + ") but was never seen inside the door's block column in "
                        + walk.samples() + " samples taken every " + SAMPLE_TICKS + " ticks. The lane is walled "
                        + "at the door's plane with the door as its only gap, so an arrival that never occupied "
                        + "that column means this run did not use the doorway and stage 1's claim is not proven.");
            }
            if (walk.doorOpenAtSample() == 0) {
                throw new AssertionError("He arrived at the far end (" + where + " alive at "
                        + endOfCourse.healthText() + ") and was seen inside the door's block column, but the door "
                        + "at " + course.doorLower().toShortString() + " was never seen open in " + walk.samples()
                        + " samples taken every " + SAMPLE_TICKS + " ticks. It was built closed, so a crossing "
                        + "with it never open would mean he went through a shut door — the thing the door was "
                        + "supposed to stop.");
            }
            System.out.println("[gametest] terrain: doorway crossed at " + walk.crossingText());
            System.out.println("[gametest] terrain ok: walked the yard through the open doorway, past the ledge, gate "
                    + "and water to " + where + " alive at " + endOfCourse.healthText() + ", saying "
                    + stoppedWith(server));

            // --- 2. The door becomes wall while he walks. ---
            BlockPos sealedStart = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            BlockPos sealedGoal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                int z = base.getZ() + 14;
                // Wider than he can plan around: his follow range is 48, so a wall this long with no gap
                // has no route at all rather than a long one — otherwise walking round the end would be
                // a legitimate arrival and this stage would fail for the wrong reason.
                CobbieTestSupport.command(s, "fill " + (base.getX() - 40) + " " + base.getY() + " " + z + " "
                        + (base.getX() + 40) + " " + (base.getY() + 3) + " " + z + " minecraft:stone");
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
                CobbieTestSupport.command(s, "fill " + (base.getX() - 40) + " " + base.getY() + " " + z + " "
                        + (base.getX() + 40) + " " + (base.getY() + 3) + " " + z + " minecraft:stone");
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

            // --- 4. A destination inside stone is refused, not walked into. ---
            // The wall from stage 3 is still there. Asked to stand in one of its blocks, he has to say he
            // cannot rather than plan a route to a place his body does not fit and drive himself into it.
            assertRefusesWallDestination(context, server);
        }
    }

    /**
     * Walk-to a block that is stone: the step must fail with a plain refusal before anything moves. This is
     * the destination half of the fatality — the suffocation guard catches the body that is already inside
     * a block, and this stops the one thing that can deliberately put him there.
     */
    private static void assertRefusesWallDestination(ClientGameTestContext context, TestServerContext server) {
        // The nearest solid block in front of him is a block of the wall he just stopped against. Found by
        // probing rather than by arithmetic: he stops wherever the pathfinder gives up, and a hard-coded
        // offset from his own feet is how this stage aimed at empty air the first time.
        BlockPos[] setup = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos feet = cobbie.blockPosition();
            for (int dz = 1; dz <= 16; dz++) {
                for (int dx = 0; dx <= 6; dx++) {
                    for (int sx : new int[] {feet.getX() + dx, feet.getX() - dx}) {
                        BlockPos probe = new BlockPos(sx, feet.getY(), feet.getZ() + dz);
                        if (!cobbie.level().getBlockState(probe).isAir()) {
                            return new BlockPos[] {feet, probe};
                        }
                    }
                }
            }
            return null;
        });
        if (setup == null) {
            throw new AssertionError("No solid block within 16 in front of him to aim at, so the walk-to "
                    + "refusal was not tested");
        }
        BlockPos wall = setup[1];

        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan("walk into the wall", List.of(new ActionIntent(ActionVocabulary.WALK_TO, "",
                        wall.getX(), wall.getY(), wall.getZ(), ""))));
        if (refusal != null) throw new AssertionError("walk-to into stone refused the whole plan: " + refusal);

        String said = "";
        for (int tick = 0; tick < 100 && !said.contains("cannot stand there"); tick++) {
            context.waitTicks(5);
            said = stoppedWith(server);
        }
        Reading after = requireAlive(server, wall, "after being asked to stand in stone");
        if (!said.contains("cannot stand there")) {
            throw new AssertionError("Asked to walk into stone at " + wall.toShortString() + " he did not say he "
                    + "cannot stand there; he said \"" + said + "\" and ended at " + after.where());
        }
        if (!after.where().startsWith(setup[0].toShortString())) {
            throw new AssertionError("He refused the destination at " + wall.toShortString() + " but moved to "
                    + after.where() + " anyway — a refusal that still walks is not a refusal");
        }
        System.out.println("[gametest] terrain ok: a destination inside stone is refused — \"" + said
                + "\", still at " + after.where() + " (" + after.healthText() + ")");
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
     * A yard in front of him: a wall across the lane with a closed door he has to open, a step down off
     * the ledge it stands on, a fence with a closed gate, a water channel, and the far end.
     *
     * <p>The lane starts one block <em>in front</em> of him. Building it from one block behind put solid
     * stone in the two blocks his body occupied and suffocated him — the test was the obstacle — so the
     * geometry now keeps his own column clear, and the goal is a place he can stand: on the ground at the
     * far end, not two blocks above it.
     *
     * <p>The wall at the door's plane is the width of the yard, not of the walkway: 33 blocks, because
     * anything narrower leaves a route round the end that legitimately arrives without using the door.
     * With it walled, the doorway is the only way to the far end and stage 1's arrival <em>is</em> a
     * crossing — which the walk then checks directly rather than inferring.
     */
    private static Course buildCourse(TestServerContext server, BlockPos start) {
        return server.computeOnServer(s -> {
            int x = start.getX();
            int y = start.getY();
            int z = start.getZ();
            // A raised walkway one block up: he can always step up one, and the ledge at its end is a
            // step down to the ground. Filled from the block below the walkway's surface, starting in
            // front of him, so nothing lands where he is standing.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + (y - 1) + " " + (z + 1) + " " + (x + 3) + " "
                    + y + " " + (z + LEDGE_END) + " minecraft:stone");
            // The yard wall, on the door's plane: full width, from the ground to well above his reach.
            CobbieTestSupport.command(s, "fill " + (x - WALL_HALF_WIDTH) + " " + (y - 1) + " " + (z + DOOR_AT) + " "
                    + (x + WALL_HALF_WIDTH) + " " + (y + WALL_TOP) + " " + (z + DOOR_AT) + " minecraft:stone");
            // The doorway: the wall's only gap, closed, at his body.
            CobbieTestSupport.command(s, "setblock " + x + " " + (y + 1) + " " + (z + DOOR_AT)
                    + " minecraft:oak_door[half=lower,facing=north,open=false]");
            CobbieTestSupport.command(s, "setblock " + x + " " + (y + 2) + " " + (z + DOOR_AT)
                    + " minecraft:oak_door[half=upper,facing=north,open=false]");
            // A fence with a closed gate, at ground level.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + y + " " + (z + GATE_AT) + " " + (x + 3) + " "
                    + (y + 2) + " " + (z + GATE_AT) + " minecraft:oak_fence");
            CobbieTestSupport.command(s, "setblock " + x + " " + y + " " + (z + GATE_AT)
                    + " minecraft:oak_fence_gate[facing=north,open=false]");
            // A water channel three wide, two deep: he has to swim it, not step over it.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + (y - 2) + " " + (z + WATER_AT) + " "
                    + (x + 3) + " " + (y + 1) + " " + (z + WATER_AT + 2) + " minecraft:water");
            // A lip on the far side, one block above the ground, so leaving the water means climbing one
            // block rather than two: a two-block bank is a wall he can neither walk up nor swim over, and
            // the swim assist finds no bank beside him because the block above its top is stone too.
            CobbieTestSupport.command(s, "fill " + (x - 3) + " " + (y - 1) + " " + (z + WATER_AT + 3) + " "
                    + (x + 3) + " " + y + " " + (z + WATER_AT + 3) + " minecraft:stone");
            return new Course(new BlockPos(x, y, z + GOAL_AT),
                    new BlockPos(x, y + 1, z + DOOR_AT));
        });
    }

    /** Where the course leads, and the lower half of its doorway — the column a crossing has to touch. */
    private record Course(BlockPos goal, BlockPos doorLower) {
    }

    /** The walk itself: whether he arrived, what was seen at the doorway, and how much was sampled. */
    private record Walk(boolean arrived, int samples, BlockPos crossing, int crossingSample,
            boolean doorOpenAtCrossing, int doorOpenAtSample) {
        String crossingText() {
            if (crossing == null) {
                return "never inside the door column in " + samples + " samples";
            }
            return crossing.toShortString() + " on sample " + crossingSample + " of " + samples
                    + ", door OPEN=" + doorOpenAtCrossing + " at that sample, first seen OPEN on sample "
                    + doorOpenAtSample;
        }
    }

    /**
     * Walk the course while watching the body, not only the destination: every {@link #SAMPLE_TICKS}
     * ticks his {@code blockPosition} is read, and the first sample standing in the door's own block
     * column (same x and z, y equal to the lower or upper half) is kept together with the door's
     * {@code OPEN} state at that instant. Arrival alone cannot tell a doorway he used from a wall he
     * walked round; this can.
     *
     * <p>The door's state is read on <em>every</em> sample, not only inside the column, because vanilla
     * {@code OpenDoorGoal} shuts the door the moment he is past it: the crossing sample is often the
     * instant after it closed, so "he was ever seen with it open" is the evidence that he opened a door
     * built closed, and the state at the crossing is recorded rather than required.
     *
     * <p>A death is raised here, in the stage it happened in, rather than surfacing as a timeout.
     */
    private static Walk walkTheCourse(ClientGameTestContext context, TestServerContext server, BlockPos goal,
            BlockPos doorLower, int ticks) {
        int samples = 0;
        BlockPos crossing = null;
        int crossingSample = 0;
        boolean crossingOpen = false;
        int openAtSample = 0;
        boolean arrived = false;
        for (int spent = 0; spent < ticks; spent += SAMPLE_TICKS) {
            context.waitTicks(SAMPLE_TICKS);
            Sample now = server.computeOnServer(s -> CobbieTestSupport.cobbie(s, 96)
                    .map(c -> {
                        BlockPos feet = c.blockPosition();
                        BlockState doorState = s.overworld().getBlockState(doorLower);
                        boolean open = doorState.getBlock() instanceof DoorBlock && doorState.getValue(DoorBlock.OPEN);
                        boolean inDoorway = feet.getX() == doorLower.getX() && feet.getZ() == doorLower.getZ()
                                && (feet.getY() == doorLower.getY() || feet.getY() == doorLower.getY() + 1);
                        return new Sample(feet, c.getHealth(), inDoorway, open,
                                feet.distSqr(goal) <= ARRIVED && c.onGround());
                    })
                    .orElse(null));
            if (now == null || now.health() <= 0.0f) {
                requireAlive(server, goal, "stage 1, walking the yard"); // always throws here
            }
            samples++;
            if (now.doorOpen() && openAtSample == 0) openAtSample = samples;
            if (crossing == null && now.inDoorway()) {
                crossing = now.feet();
                crossingSample = samples;
                crossingOpen = now.doorOpen();
            }
            if (now.atGoal()) {
                arrived = true;
                break;
            }
        }
        System.out.println("[gametest] terrain: walked " + samples + " samples, "
                + (arrived ? "arrived" : "did not arrive") + ", doorway "
                + (crossing == null ? "never crossed" : "crossed at sample " + crossingSample
                        + " (door " + (crossingOpen ? "open" : "closed") + " then, first seen open at sample "
                        + openAtSample + ")"));
        return new Walk(arrived, samples, crossing, crossingSample, crossingOpen, openAtSample);
    }

    /** One read of the body while he walks. {@code inDoorway} means he occupies the door's block column. */
    private record Sample(BlockPos feet, float health, boolean inDoorway, boolean doorOpen, boolean atGoal) {
    }

    /**
     * The adversarial rule: he stops. Not looping, not reporting an arrival he did not make. A stop with
     * no reason is the other half of the same defect, so the line he said is part of the assertion, and so
     * is the body: a companion who died against the wall has stopped, and stopping is not passing.
     */
    private static void assertStopsRatherThanLoops(ClientGameTestContext context, TestServerContext server,
            BlockPos goal, String obstacle, double distanceAtStart) {
        requireAlive(server, goal, "the walk into " + obstacle);
        // Long enough that a real attempt has clearly failed, short enough that a loop is caught.
        boolean stopped = false;
        for (int tick = 0; tick < 360 && !stopped; tick++) {
            context.waitTicks(5);
            Reading now = reading(server, goal);
            if (now == null || now.dead()) {
                // Wording the death belongs to requireAlive: "he never stopped" would be the wrong story.
                requireAlive(server, goal, "standing against " + obstacle);
            }
            stopped = !now.busy();
        }

        Reading end = requireAlive(server, goal, "after facing " + obstacle);
        String where = end.where();
        String said = stoppedWith(server);

        if (!stopped) {
            throw new AssertionError("He was still walking after 90 s at " + where + " with " + obstacle
                    + " in the way — that is the loop this test exists to catch");
        }
        if (end.atGoal()) {
            throw new AssertionError("He reported being at the goal with " + obstacle + " in the way (" + where
                    + ") — arrival from a distance is the claim this test refuses");
        }
        if (said == null || said.isBlank()) {
            throw new AssertionError("He stopped with " + obstacle + " in the way but said nothing about it ("
                    + where + ")");
        }
        System.out.println("[gametest] terrain adversarial ok: " + obstacle + " — stopped alive at " + where
                + " (" + end.healthText() + ") saying \"" + said + "\" (started "
                + Math.round(Math.sqrt(distanceAtStart)) + " blocks away)");
    }

    /**
     * Walk until he is standing at the goal, without letting a death pass as a timeout: a companion who
     * suffocated on the way is found dead here, in the stage he died in, instead of as "he never arrived"
     * seventy seconds later.
     */
    private static boolean awaitArrival(TestServerContext server, BlockPos goal, int ticks) {
        for (int spent = 0; spent < ticks; spent += 20) {
            Reading now = reading(server, goal);
            if (now == null || now.dead()) {
                requireAlive(server, goal, "walking to the end of the course"); // always throws here
                return false;
            }
            if (now.atGoal()) return true;
            CobbieTestSupport.elapsed(server, 20);
        }
        return false;
    }

    /** One reading of the body. Null means he cannot be found at all. */
    private record Reading(String where, String healthText, float health, boolean atGoal, boolean busy) {
        boolean dead() {
            return health <= 0.0f;
        }
    }

    /**
     * Where he is and how he is, read in one go on the server thread. A box of 96 blocks around the owner
     * rather than the everyday 48: the course is longer than that, and a companion standing at the far end
     * of it is not "nowhere findable".
     */
    private static Reading reading(TestServerContext server, BlockPos goal) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s, 96)
                .map(c -> new Reading(
                        c.blockPosition().toShortString() + " onGround=" + c.onGround(),
                        c.getHealth() + "/" + c.getMaxHealth(),
                        c.getHealth(),
                        c.blockPosition().distSqr(goal) <= ARRIVED && c.onGround(),
                        c.isBusy()))
                .orElse(null));
    }

    /**
     * A stage only passes on a body that is still there and still alive. Printed every time it is called,
     * so the run's log shows his health stage by stage rather than only at the end.
     */
    private static Reading requireAlive(TestServerContext server, BlockPos goal, String stage) {
        Reading now = reading(server, goal);
        if (now == null) {
            throw new AssertionError("He is missing at " + stage + " — dead, removed, or more than 96 blocks "
                    + "from you. A companion who is not there has not passed anything, whatever the goal says.");
        }
        if (now.dead()) {
            throw new AssertionError("He died at " + stage + " (" + now.where() + ", last words \""
                    + stoppedWith(server) + "\"). A dead companion standing at the goal is not an arrival.");
        }
        System.out.println("[gametest] " + stage + ": alive, health " + now.healthText() + " at " + now.where());
        return now;
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
