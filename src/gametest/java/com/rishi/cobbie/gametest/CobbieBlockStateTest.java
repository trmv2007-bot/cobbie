package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placement is a blockstate, not a block appearing.
 *
 * <p>Every block this mod can be asked to place and that carries state from the click — stairs, slabs,
 * torches, doors, beds, signs, chests, ladders — is placed twice here: once by <b>the owner</b>, a real
 * player standing in a named spot and clicking a named face, and once by <b>Cobbie</b> from the same
 * spot on an identical square a few blocks away. The two blockstates have to agree, property by
 * property, and the properties that matter ({@code facing}, {@code half}, {@code type},
 * {@code attachment}, {@code rotation}, {@code part}, {@code hinge}) are also asserted by name so a
 * failure says which one is wrong rather than "the states differ".
 *
 * <p>Why the oracle instead of a table of expectations: the answer <em>is</em> the game's. A stair's
 * facing, a chest's facing and a bed's head are decided by vanilla from the clicker's yaw and the face
 * he clicked, and the rules differ between blocks — the stair faces the way the player looks, the chest
 * faces the player, the bed's head goes away from him. Writing those three rules into the test would be
 * my reading of the game; asking the game, with a real player's right-click, is the thing itself. The
 * named properties are the second witness, so the oracle cannot make a broken state look right by
 * agreeing with itself.
 *
 * <p>Until 0.83.0 the placement path wrote the block into the world itself — {@code setBlock} with a
 * state built from {@code defaultBlockState()} and one guessed {@code facing} — so all of this passed
 * as "a block is there" and none of it was true: the facing was the mirror of the game's, and the
 * half, type, attachment, rotation and part every one of these blocks needs were simply missing.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=BlockState}.
 */
public final class CobbieBlockStateTest implements FabricClientGameTest {

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

            // Feet height on the flat world, and a strip of lanes to the owner's south: each case owns
            // six blocks of it, with the oracle square three east of the subject's.
            int feet = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition().getY());
            BlockPos origin = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition()
                    .offset(0, 0, 6));
            Lanes lanes = new Lanes(feet, origin);

            // 1. Stairs: the half they sit on, and the way they face.
            expect(server, lanes, "oak stairs", Items.OAK_STAIRS, "minecraft:oak_stairs",
                    Map.of("facing", "south", "half", "bottom"), true);

            // 2. Slab: which half of the square it fills. A click at the middle of a face is the
            //    bottom half — the top half needs a click in the upper part of a side, which is a finer
            //    aim than a right-click at a block carries, for him and for a player alike.
            expect(server, lanes, "stone slab", Items.STONE_SLAB, "minecraft:stone_slab",
                    Map.of("type", "bottom"), true);

            // 3. A torch on the floor, and 4. the same torch on a wall. In 26.3 the floor/wall
            //    distinction is two blocks rather than a property — a floor torch carries no state at all
            //    and a wall torch carries only its facing — so the block id is the attachment claim here,
            //    and the facing is the property that comes from the face that was clicked.
            expect(server, lanes, "torch on the ground", Items.TORCH, "minecraft:torch",
                    Map.of(), true);
            expect(server, lanes, "torch on a wall", Items.TORCH, "minecraft:wall_torch",
                    Map.of("facing", "south"), false);

            // 5. A ladder hung on the same wall: it faces away from the wall, toward whoever placed it.
            expect(server, lanes, "ladder on a wall", Items.LADDER, "minecraft:ladder",
                    Map.of("facing", "south"), false);

            // 6. A door: both halves, the same facing, and the hinge the game picks.
            expect(server, lanes, "oak door", Items.OAK_DOOR, "minecraft:oak_door",
                    Map.of("half", "lower", "facing", "south"), true);

            // 7. A bed: the foot where he was asked to build, the head where the game puts it.
            expect(server, lanes, "white bed", Items.BED.white(), "minecraft:white_bed",
                    Map.of("part", "foot", "facing", "south"), true);

            // 8. A standing sign: the rotation that points it at the placer. The block id is the claim
            //    here — a sign clicked on a wall would be oak_wall_sign with a facing instead.
            expect(server, lanes, "oak sign", Items.OAK_SIGN, "minecraft:oak_sign",
                    Map.of("rotation", "8"), true);

            // 9. A chest: it faces the player who placed it, which is the opposite direction from the
            //    stair's — one more reason the answer has to come from the game and not from a rule.
            expect(server, lanes, "chest", Items.CHEST, "minecraft:chest",
                    Map.of("facing", "north", "type", "single"), true);

            // 10. The control: a block with no state at all, so "nothing to assert" is proved rather
            //     than assumed, and the stack still shrinks by exactly one.
            expect(server, lanes, "dirt", Items.DIRT, "minecraft:dirt", Map.of(), true);

            System.out.println("[gametest] blockstate ok: " + lanes.count
                    + " placements match the owner's own right-click, property by property"
                    + " (stairs, slab, torch on floor and on wall, ladder, door both halves, bed both"
                    + " halves, sign, chest, dirt)");
        }
    }

    /** Lanes of clear ground, allocated one per case, so no two cases share a square or a neighbour. */
    private static final class Lanes {
        private final int feet;
        private final BlockPos origin;
        private int used;
        int count;

        Lanes(int feet, BlockPos origin) {
            this.feet = feet;
            this.origin = origin;
        }

        /** The next case's subject stance; the oracle is the same stance three blocks east. */
        CobbieTestSupport.Spot next(TestServerContext server, boolean ground, boolean wallNorth) {
            BlockPos square = origin.offset(used * 6, 0, 0);
            used++;
            if (!ground) square = square.above();
            if (wallNorth) CobbieTestSupport.stoneAt(server, square.north());
            CobbieTestSupport.Spot spot;
            if (ground) {
                // Standing on the ground two north of the square, clicking the ground under it.
                spot = new CobbieTestSupport.Spot(square, square.offset(0, 0, -2), square.below(),
                        net.minecraft.core.Direction.UP);
            } else {
                // Standing on the ground two south, clicking the wall's south face: a torch or a
                // ladder at head height is attached to the wall, not to the ground.
                spot = new CobbieTestSupport.Spot(square, square.offset(0, -1, 2), square.north(),
                        net.minecraft.core.Direction.SOUTH);
            }
            if (wallNorth) CobbieTestSupport.stoneAt(server, spot.moved(OracleDistance, 0).clicked());
            return spot;
        }

        private static final int OracleDistance = 3;
    }

    /**
     * Place the same item twice from the same stance, and require the two states to agree.
     *
     * @param named the properties that must read this way, asserted by name as the second witness
     */
    private static void expect(TestServerContext server, Lanes lanes, String what, Item item, String blockId,
            Map<String, String> named, boolean ground) {
        CobbieTestSupport.Spot subject = lanes.next(server, ground, !ground);
        CobbieTestSupport.Spot oracle = subject.moved(Lanes.OracleDistance, 0);
        CobbieTestSupport.placedLikeAPlayer(server, what, item, subject, oracle, blockId, named);
        lanes.count++;
        if (blockId.equals("minecraft:oak_door") || blockId.equals("minecraft:white_bed")) {
            // The other half of each of these is placed by the game beside the square he was asked to
            // build in, and it is part of what was asked for: a door with no top is not a door.
            BlockPos beside = blockId.equals("minecraft:oak_door") ? subject.square().above()
                    : subject.square().south();
            Map<String, String> half = blockId.equals("minecraft:oak_door")
                    ? Map.of("half", "upper", "facing", "south")
                    : Map.of("part", "head", "facing", "south");
            String text = server.computeOnServer(s -> CobbieTestSupport.stateText(s.overworld().getBlockState(beside)));
            if (!text.startsWith(blockId)) {
                throw new AssertionError(what + ": " + beside + " holds " + text + " instead of " + blockId);
            }
            for (Map.Entry<String, String> property : half.entrySet()) {
                if (!text.contains(property.getKey() + "=" + property.getValue())) {
                    throw new AssertionError(what + ": " + beside + " should read " + property.getKey() + "="
                            + property.getValue() + " and reads " + text);
                }
            }
        }
    }
}
