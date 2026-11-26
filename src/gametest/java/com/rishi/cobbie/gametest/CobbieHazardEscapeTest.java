package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * His body in something that is taking his health: fire at his feet, lava around him, a cactus against
 * his side.
 *
 * <p>Three phases, one per hazard, and each is a way the escape can be wrong: he got out (his feet are no
 * longer in the hazard), he is alive (a guard that only logs is not a guard), and he said what he got out
 * of (silently surviving is the other half of the same defect). The lava goes into a one-block pool rather
 * than loose on the ground, because a flowing source spreads and the escape would then be measured against
 * a world that is filling up behind him.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=HazardEscape}.
 */
public final class CobbieHazardEscapeTest implements FabricClientGameTest {

    /** Well past the five-tick guard plus the tick the chat step lands on, short enough to fail fast. */
    private static final int ESCAPE_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            // Staying put: nothing is asking him to walk anywhere, so every move from here is his own idea.
            CobbieTestSupport.command(server, "cobbie stay");

            // ---- 1. fire at his feet ----
            BlockPos lit = server.computeOnServer(s -> {
                BlockPos feet = CobbieTestSupport.cobbie(s).orElseThrow().blockPosition().immutable();
                CobbieTestSupport.command(s, "setblock " + feet.getX() + " " + feet.getY() + " " + feet.getZ()
                        + " minecraft:fire");
                return feet;
            });
            boolean inFire = server.computeOnServer(s -> s.overworld().getBlockState(lit).is(Blocks.FIRE));
            if (!inFire) {
                throw new AssertionError("The fire did not land in his feet block at " + lit.toShortString()
                        + ", so this phase proves nothing");
            }
            boolean outOfFire = waitUntilOff(context, server, lit);
            String fireState = describe(server);
            if (!outOfFire) {
                throw new AssertionError("He was still standing in the fire after " + ESCAPE_TICKS + " ticks ("
                        + fireState + ")");
            }
            assertSaid(server, "fire", fireState);

            // ---- 2. lava, into a one-block pool so it cannot spread under him ----
            BlockPos pool = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos spot = cobbie.blockPosition().offset(3, 0, 0);
                BlockPos lava = spot.below();
                CobbieTestSupport.command(s, "setblock " + lava.getX() + " " + lava.getY() + " " + lava.getZ()
                        + " minecraft:lava");
                cobbie.teleportTo(s.overworld(), lava.getX() + 0.5, lava.getY(), lava.getZ() + 0.5,
                        Set.of(), cobbie.getYRot(), cobbie.getXRot(), true);
                return lava;
            });
            // Asked of the block he is standing in rather than of isInLava(): the fluid interaction is
            // refreshed on the entity's own tick, so on the tick of a teleport it still reads "not in it".
            boolean inLava = server.computeOnServer(s -> s.overworld()
                    .getFluidState(CobbieTestSupport.cobbie(s).orElseThrow().blockPosition())
                    .is(net.minecraft.tags.FluidTags.LAVA));
            if (!inLava) {
                throw new AssertionError("He is not in the lava at " + pool.toShortString()
                        + ", so the lava phase proves nothing");
            }
            boolean outOfLava = waitUntilOff(context, server, pool);
            String lavaState = describe(server);
            if (!outOfLava) {
                throw new AssertionError("He was still standing in the lava after " + ESCAPE_TICKS + " ticks ("
                        + lavaState + ")");
            }
            assertSaid(server, "lava", lavaState);

            // ---- 3. a cactus his shoulder is in ----
            //
            // Standing a block away from a cactus does not hurt - his body is 0.6 wide, so the gap is
            // real - which is why the hazard is asked of the box he actually occupies. So the cactus is
            // planted in the column he leans into and his centre moved to the edge of it: what is escaped
            // here is the game's own cactus damage, not a neighbour this test declared dangerous.
            BlockPos cactus = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos beside = cobbie.blockPosition().east();
                CobbieTestSupport.command(s, "setblock " + beside.getX() + " " + beside.below().getY() + " "
                        + beside.getZ() + " minecraft:sand");
                CobbieTestSupport.command(s, "setblock " + beside.getX() + " " + beside.getY() + " "
                        + beside.getZ() + " minecraft:cactus");
                cobbie.teleportTo(s.overworld(), beside.getX() + 0.05, beside.getY(), beside.getZ() + 0.5,
                        Set.of(), cobbie.getYRot(), cobbie.getXRot(), true);
                return beside.immutable();
            });
            boolean touching = server.computeOnServer(s -> s.overworld().getBlockState(cactus).is(Blocks.CACTUS)
                    && CobbieTestSupport.cobbie(s).map(c -> c.getBoundingBox().intersects(new AABB(cactus)))
                    .orElse(false));
            if (!touching) {
                throw new AssertionError("His body is not in the cactus at " + cactus.toShortString()
                        + ", so the cactus phase proves nothing");
            }
            boolean clearOfCactus = false;
            for (int tick = 0; tick < ESCAPE_TICKS && !clearOfCactus; tick++) {
                context.waitTicks(2);
                clearOfCactus = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> !c.getBoundingBox().intersects(new AABB(cactus))).orElse(false));
            }
            String cactusState = describe(server);
            if (!clearOfCactus) {
                throw new AssertionError("He never got his body out of the cactus after " + ESCAPE_TICKS + " ticks ("
                        + cactusState + ")");
            }
            assertSaid(server, "cactus", cactusState);

            System.out.println("[gametest] hazard escapes ok: fire at " + lit.toShortString() + " -> " + fireState
                    + ", lava pool at " + pool.toShortString() + " -> " + lavaState
                    + ", cactus at " + cactus.toShortString() + " -> " + cactusState);
        }
    }

    /** Wait until his body is out of the hazard block, or the window runs out. */
    private static boolean waitUntilOff(ClientGameTestContext context, TestServerContext server, BlockPos hazard) {
        for (int tick = 0; tick < ESCAPE_TICKS / 2; tick++) {
            context.waitTicks(2);
            boolean off = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> !c.getBoundingBox().intersects(new AABB(hazard))).orElse(false));
            if (off) return true;
        }
        return false;
    }

    private static void assertSaid(TestServerContext server, String word, String state) {
        boolean said = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.saidRecently(word)).orElse(false));
        String lines = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.recentLines().toString()).orElse(""));
        if (!said) {
            throw new AssertionError("He got out of the " + word + " at " + state + " but nothing he said names it: "
                    + lines);
        }
    }

    private static String describe(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.getHealth() + "/" + c.getMaxHealth() + " at " + c.blockPosition().toShortString()
                        + " inLava=" + c.isInLava())
                .orElse("MISSING"));
    }
}
