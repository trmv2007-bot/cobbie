package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

/**
 * A companion with stone in the two blocks his body occupies.
 *
 * <p>That is not a route he chose: a piston, a crash, a teleport or a world built over his head all end
 * in the same place, and the first course test here did it by accident and killed him. The walk-to
 * refusal covers the destination he was given; this covers the body he actually has, which is why the
 * guard is on the tick and not on the verb.
 *
 * <p>Three things are asserted, and each one is a way the guard can be wrong: he is alive afterwards
 * (a guard that only logs is not a guard), he is no longer inside a block (a guard that cancels the job
 * and leaves him in the wall is worse than nothing), and he moved out of the column he was sealed in
 * (being pushed a hair sideways by the game's own collision handling is not a rescue). The line he says
 * is part of it: silently surviving is the other half of the same defect.
 */
public final class CobbieWallRescueTest implements FabricClientGameTest {

    /** Long enough for the ten-tick guard plus the chat step that follows it, short enough to fail fast. */
    private static final int ESCAPE_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");

            BlockPos sealed = server.computeOnServer(s -> {
                BlockPos feet = CobbieTestSupport.cobbie(s).orElseThrow().blockPosition();
                CobbieTestSupport.command(s, "fill " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " "
                        + feet.getX() + " " + (feet.getY() + 1) + " " + feet.getZ() + " minecraft:stone");
                return feet;
            });
            boolean sealedIn = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::isInWall).orElse(false));
            if (!sealedIn) {
                throw new AssertionError("The seal did not put stone in his body, so this test proved nothing: "
                        + "he is not in a wall after /fill at " + sealed.toShortString());
            }

            boolean freed = false;
            for (int tick = 0; tick < ESCAPE_TICKS && !freed; tick++) {
                context.waitTicks(2);
                freed = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> !c.isInWall()).orElse(false));
            }

            String state = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getHealth() + "/" + c.getMaxHealth() + " at " + c.blockPosition().toShortString()
                            + " inWall=" + c.isInWall())
                    .orElse("MISSING"));
            String said = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::lastSpokenLine).orElse(""));

            if (state.equals("MISSING")) {
                throw new AssertionError("He died inside the block instead of getting himself out: he was sealed "
                        + "at " + sealed.toShortString() + " and is nowhere findable afterwards");
            }
            if (!freed) {
                throw new AssertionError("He was still inside the block after " + ESCAPE_TICKS + " ticks (" + state
                        + "); the guard never moved him");
            }
            BlockPos endedAt = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            if (endedAt.equals(sealed)) {
                throw new AssertionError("He reads as not-in-wall but never left the sealed column " + state
                        + " — the game's own collision push is not a rescue");
            }
            if (said == null || said.isBlank()) {
                throw new AssertionError("He got out of the block at " + state + " but said nothing about it");
            }
            if (!said.toLowerCase(java.util.Locale.ROOT).contains("block")) {
                throw new AssertionError("He got out of the block at " + state + " but the line he said does not "
                        + "name the cause: \"" + said + "\"");
            }
            System.out.println("[gametest] wall rescue ok: sealed at " + sealed.toShortString() + ", stood up at "
                    + state + ", saying \"" + said + "\"");
        }
    }
}
