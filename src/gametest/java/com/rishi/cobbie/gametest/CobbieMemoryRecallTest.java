package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.OperationalMemory;
import com.rishi.cobbie.memory.OperationalMemory.Entry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

import java.util.List;
import java.util.UUID;

/**
 * That he remembers the world across a session, a correction and his own death — and that the owner can
 * read it back and prune it.
 *
 * <p>The store is {@code config/cobbie_memories.json}, keyed by owner, written as it changes. Nothing
 * of it lives in the entity's save data, which is the decision this test proves: the world is saved and
 * reopened underneath him, and the same memory comes back with its coordinates because it was never the
 * body's to lose. A memory that died with the body would be memory for one session only.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=MemoryRecall}.
 */
public final class CobbieMemoryRecallTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        UUID ownerId;
        BlockPos home;
        BlockPos movedHome;
        TestWorldSave saved;

        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie autonomy off");
            CobbieTestSupport.command(server, "cobbie stay");

            // This file outlives a test run, so the class starts from an empty book rather than from
            // whatever an earlier class left behind for this owner.
            ownerId = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                OperationalMemory.clear(cobbie.getOwner());
                return cobbie.getOwner();
            });

            home = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            movedHome = home.offset(4, 0, 4);

            // 1. He is told something, in chat, with no model in the loop.
            tell(context, "home", home);
            String listing = CobbieTestSupport.captureCommand(server, "cobbie memory");
            if (!listing.contains("home") || !listing.contains(at(home)) || !listing.contains("you told me")) {
                throw new AssertionError("/cobbie memory did not show what he was just told: " + listing);
            }
            System.out.println("[gametest] remembered: " + listing.trim());

            // 2. The owner contradicts himself: the same thing twice is one memory, corrected in place.
            tell(context, "home", movedHome);
            List<Entry> afterCorrection = server.computeOnServer(s -> OperationalMemory.entries(ownerId));
            if (afterCorrection.size() != 1) {
                throw new AssertionError("A correction stacked instead of overwriting: " + afterCorrection);
            }
            Entry only = afterCorrection.get(0);
            if (only.x() != movedHome.getX() || only.z() != movedHome.getZ()) {
                throw new AssertionError("The corrected coordinates are not the ones the owner gave: "
                        + only.x() + " " + only.y() + " " + only.z() + " instead of " + movedHome);
            }
            String corrected = CobbieTestSupport.captureCommand(server, "cobbie memory");
            if (corrected.contains(at(home))) {
                throw new AssertionError("The old coordinates are still listed beside the new ones: " + corrected);
            }
            System.out.println("[gametest] corrected, not duplicated: " + corrected.trim());

            saved = world.getWorldSave();
        }

        // 3. Close the world and open the save: the same memory, coordinates and all.
        try (TestSingleplayerContext reopened = saved.open()) {
            TestServerContext server = reopened.getServer();
            CobbieTestSupport.awaitWorld(context);
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie was not there after relogging into the saved world", t);
            }
            String list = CobbieTestSupport.captureCommand(server, "cobbie memory");
            if (!list.contains("home") || !list.contains(at(movedHome))) {
                throw new AssertionError("The memory did not survive the reload with its coordinates: " + list);
            }
            List<Entry> loaded = server.computeOnServer(s -> OperationalMemory.entries(ownerId));
            if (loaded.size() != 1 || loaded.get(0).x() != movedHome.getX()) {
                throw new AssertionError("The reloaded memory is not the one that was saved: " + loaded);
            }
            System.out.println("[gametest] reload ok: " + list.trim());

            // 4. /cobbie forget <n> drops exactly that one.
            String afterForget = CobbieTestSupport.captureCommand(server, "cobbie forget 1");
            if (!afterForget.contains("Forgot number 1")) {
                throw new AssertionError("/cobbie forget 1 did not drop it: " + afterForget);
            }
            if (server.computeOnServer(s -> OperationalMemory.entries(ownerId)).size() != 0) {
                throw new AssertionError("A memory survived /cobbie forget 1");
            }
            String empty = CobbieTestSupport.captureCommand(server, "cobbie memory");
            // The empty listing's own example sentence mentions "home", so the assertion is on the
            // numbered row being gone rather than on the word.
            if (!empty.contains("I do not know this world yet") || empty.contains("1. home")) {
                throw new AssertionError("The listing still names a memory that was forgotten: " + empty);
            }
            System.out.println("[gametest] forget ok: \"" + afterForget.trim() + "\"");

            // 5. He dies, and what he knew comes back with the next body: the book is keyed by owner,
            //    not by the entity, because an entity that dies takes its save data with it.
            tell(context, "home", movedHome);
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "kill @e[type=cobbie:cobbie]");
                return null;
            });
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isEmpty(), 200);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie survived /kill, so the death half cannot be measured", t);
            }
            CobbieTestSupport.command(server, "spawncobbie");
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 400);
            } catch (Throwable t) {
                throw new AssertionError("No companion came back after the one that died", t);
            }
            String afterDeath = CobbieTestSupport.captureCommand(server, "cobbie memory");
            if (!afterDeath.contains("home") || !afterDeath.contains(at(movedHome))) {
                throw new AssertionError("His memory died with his body: " + afterDeath);
            }
            System.out.println("[gametest] death ok: the next body still knows \"" + afterDeath.trim() + "\"");
        }
    }

    /**
     * What the owner types, through the real chat channel — not the command system. {@code cobbie
     * remember ...} is an ordinary chat line, and a test that dispatched it as a command would prove
     * nothing about the path a person actually uses.
     */
    private static void tell(ClientGameTestContext context, String subject, BlockPos where) {
        String line = "cobbie remember " + subject + " is at "
                + where.getX() + " " + where.getY() + " " + where.getZ();
        context.runOnClient(mc -> mc.getConnection().sendChat(line));
        context.waitTicks(5);   // the server reads the chat packet on its next tick
    }

    /** The coordinates as the listing writes them, which is what makes a round trip visible. */
    private static String at(BlockPos pos) {
        return " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in overworld";
    }
}
