package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Persistence: he is the same Cobbie with the same bag after the world is closed and reopened.
 * That is the claim 0.5.0 made loudest and never tested.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbiePersistenceTest implements FabricClientGameTest {

    private static final int STONE = 7;

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        UUID ownerId;
        UUID cobbieId;

        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            CobbieEntity cobbie = CobbieTestSupport.spawn(context, world.getServer());
            CobbieTestSupport.command(world.getServer(), "cobbie stay");

            cobbie.addItem(new ItemStack(Items.STONE, STONE));
            int stocked = cobbie.countItem("minecraft:stone");
            if (stocked != STONE) {
                throw new AssertionError("Test setup failed: gave " + STONE + " stone but he holds " + stocked);
            }

            ownerId = cobbie.getOwner();
            cobbieId = cobbie.getUUID();
            save = world.getWorldSave();
        }

        try (TestSingleplayerContext reopened = save.open()) {
            TestServerContext server = reopened.getServer();
            CobbieTestSupport.awaitWorld(context);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isPresent(), 600);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie was not there after relogging into the saved world", t);
            }

            CobbieEntity again = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow());
            if (!cobbieId.equals(again.getUUID())) {
                throw new AssertionError("A different companion came back: was " + cobbieId
                        + ", now " + again.getUUID());
            }
            if (!ownerId.equals(again.getOwner())) {
                throw new AssertionError("Owner changed across the relog: was " + ownerId
                        + ", now " + again.getOwner());
            }
            int carried = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:stone"));
            if (carried != STONE) {
                throw new AssertionError("He came back with " + carried + " stone instead of " + STONE);
            }

            System.out.println("[gametest] persistence ok: same uuid, same owner, stone intact at " + carried);
        }
    }
}
