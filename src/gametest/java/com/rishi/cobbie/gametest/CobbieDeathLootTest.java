package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Death: whatever he was carrying goes on the ground where he fell. Vanilla deletes a mob's inventory,
 * which for a body that carries what you gave him is silent data loss.
 *
 * <p>Verified through the player's own inventory: the owner walks onto the spot after the death and
 * must pick the items up. That is ground truth — an item entity count can lie if the query is wrong.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieDeathLootTest implements FabricClientGameTest {

    private static final int DIAMONDS = 5;
    private static final int INGOTS = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            BlockPos at = server.computeOnServer(s -> {
                var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIAMOND, DIAMONDS));
                cobbie.addItem(new ItemStack(Items.IRON_INGOT, INGOTS));
                return cobbie.blockPosition();
            });

            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "kill @e[type=cobbie:cobbie]");
                return null;
            });

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).isEmpty(), 200);
            } catch (Throwable t) {
                throw new AssertionError("Cobbie survived /kill", t);
            }

            // Stand on the corpse and let the game decide whether anything is there.
            server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                owner.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                        java.util.Set.of(), 0.0F, 0.0F, true);
                return null;
            });

            try {
                server.waitFor(s -> pickedUp(s) >= DIAMONDS + INGOTS, 400);
            } catch (Throwable t) {
                throw new AssertionError("He died carrying " + (DIAMONDS + INGOTS)
                        + " items and the owner picked up only " + server.computeOnServer(
                        CobbieDeathLootTest::pickedUp), t);
            }

            int got = server.computeOnServer(CobbieDeathLootTest::pickedUp);
            System.out.println("[gametest] death loot ok: owner recovered " + got
                    + " items from the corpse at " + at.toShortString());
        }
    }

    private static int pickedUp(net.minecraft.server.MinecraftServer server) {
        var inventory = CobbieTestSupport.owner(server).getInventory();
        return inventory.countItem(Items.DIAMOND) + inventory.countItem(Items.IRON_INGOT);
    }
}
