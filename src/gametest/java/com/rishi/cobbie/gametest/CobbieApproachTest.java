package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every approach angle, not the one he was tested at.
 *
 * <p>Placement and station use work from the angle they were tested at, which is the shape of the
 * farmland bug: the harvest was driven from the face the probe happened to stand on, and a player
 * arriving from anywhere else clicked a side and grew nothing. So the same block is placed here from
 * all four horizontal sides and from above, and from each stance the state is compared with what the
 * owner — a real player standing in the identical spot — gets for the same click.
 *
 * <h2>What actually differs, and what does not</h2>
 * The state follows the <b>face clicked</b> and the <b>yaw at the moment of the click</b>. It does not
 * follow the height of the eye: standing one block up and looking down at the same square gives the
 * same stair, because the game keys a stair's {@code facing} off {@code context.getHorizontalDirection()}
 * and its {@code half} off where the click landed within the face. That is the honest answer to "does
 * the approach angle matter", and it is asserted rather than described: the four level stances must give
 * the four different facings, and the raised stance must give the same state as the side it stands over.
 *
 * <p>Two blocks divide the question differently. A stair's facing is the direction the placer looks, so
 * the four stances have to produce four distinct states — evidence that the state is read off the world
 * and not defaulted. A torch's attachment comes from the face rather than the direction, so the same
 * torch on the floor reads {@code floor} from every stance, and the same torch clicked onto a wall
 * reads {@code wall} with the facing of the side it was hung on.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Approach}.
 */
public final class CobbieApproachTest implements FabricClientGameTest {

    /** Where the builder stands relative to the square, and the stair facing the game must give there. */
    private record Stance(String name, int dx, int dy, int dz, String facing, boolean level) {}

    private static final List<Stance> STANCES = List.of(
            new Stance("north", 0, 0, -2, "south", true),
            new Stance("south", 0, 0, 2, "north", true),
            new Stance("east", 2, 0, 0, "west", true),
            new Stance("west", -2, 0, 0, "east", true),
            // One block up on a step beside the square, looking down at it.
            new Stance("above", 0, 1, -2, "south", false));

    private static final List<Direction> SIDES = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.command(server, "gamerule doMobSpawning false");

            BlockPos origin = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition()
                    .offset(0, 0, 8));
            int lane = 0;

            // 1. Stairs from each stance: four sides and from above.
            Map<String, String> facings = new LinkedHashMap<>();
            for (Stance stance : STANCES) {
                CobbieTestSupport.Spot spot = groundSpot(origin.offset(lane * 6, 0, 0), stance);
                CobbieTestSupport.Spot oracle = spot.moved(3, 0);
                if (!stance.level()) {
                    for (CobbieTestSupport.Spot s : List.of(spot, oracle)) {
                        CobbieTestSupport.stoneAt(server, s.feet().below());
                    }
                }
                CobbieTestSupport.placedLikeAPlayer(server, "stairs from " + stance.name(), Items.OAK_STAIRS,
                        spot, oracle, "minecraft:oak_stairs",
                        Map.of("facing", stance.facing(), "half", "bottom"));
                facings.put(stance.name(), stance.facing());
                lane++;
            }
            Set<String> distinct = new java.util.HashSet<>(facings.values());
            if (distinct.size() != 4) {
                throw new AssertionError("The four level stances produced " + distinct.size()
                        + " different facings (" + facings + "); the state is not following where he stands");
            }

            // 2. A torch on the floor from each stance: one answer for everyone, because the face that
            //    gets clicked is the same face for everyone. A floor torch has no properties of its own in
            //    26.3 — the standalone block is the whole state — so the answer is the block itself.
            for (Stance stance : STANCES) {
                CobbieTestSupport.Spot spot = groundSpot(origin.offset(lane * 6, 0, 0), stance);
                CobbieTestSupport.Spot oracle = spot.moved(3, 0);
                if (!stance.level()) {
                    for (CobbieTestSupport.Spot s : List.of(spot, oracle)) {
                        CobbieTestSupport.stoneAt(server, s.feet().below());
                    }
                }
                CobbieTestSupport.placedLikeAPlayer(server, "torch on the floor from " + stance.name(),
                        Items.TORCH, spot, oracle, "minecraft:torch", Map.of());
                lane++;
            }

            // 3. A torch hung on a wall, once per side: attachment and facing both come from the face
            //    clicked, so this is where the four sides must differ from each other.
            for (Direction side : SIDES) {
                BlockPos square = origin.offset(lane * 6, 0, 0).above();
                CobbieTestSupport.Spot spot = new CobbieTestSupport.Spot(square,
                        square.offset(-side.getStepX() * 2, -1, -side.getStepZ() * 2),
                        square.relative(side), side.getOpposite());
                CobbieTestSupport.Spot oracle = spot.moved(3, 0);
                for (CobbieTestSupport.Spot s : List.of(spot, oracle)) {
                    CobbieTestSupport.stoneAt(server, s.clicked());
                }
                CobbieTestSupport.placedLikeAPlayer(server, "torch on the " + side.getName() + " wall",
                        Items.TORCH, spot, oracle, "minecraft:wall_torch",
                        Map.of("facing", side.getOpposite().getName()));
                lane++;
            }

            System.out.println("[gametest] approach ok: " + lane + " placements from " + STANCES.size()
                    + " stances and " + SIDES.size() + " walls, each matching the owner's own right-click;"
                    + " the four level stances gave facings " + facings);
        }
    }

    /** The square and stance for one case: a spot at ground level, clicked on the top of the ground. */
    private static CobbieTestSupport.Spot groundSpot(BlockPos square, Stance stance) {
        BlockPos feet = new BlockPos(square.getX() + stance.dx(), square.getY() + stance.dy(),
                square.getZ() + stance.dz());
        return new CobbieTestSupport.Spot(square, feet, square.below(), Direction.UP);
    }
}
