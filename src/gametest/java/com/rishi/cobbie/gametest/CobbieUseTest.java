package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.actor.FakeActor;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Using the world the way a player does, through the fake-player actor.
 *
 * <p>One in-game assertion carries the feature — sow seeds and then bone-meal the crop — and two
 * carry the conditions the actor has to meet: nothing survives a call that throws, and no phantom
 * player ends up in the save.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Use}.
 */
public final class CobbieUseTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            BlockPos soil = new BlockPos(start.getX() + 2, start.getY() - 1, start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + soil.getX() + " " + soil.getY() + " " + soil.getZ()
                        + " minecraft:farmland");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
                cobbie.addItem(new ItemStack(Items.BONE_MEAL, 8));
                return null;
            });
            if (server.computeOnServer(s -> !s.overworld().getBlockState(soil).is(Blocks.FARMLAND))) {
                throw new AssertionError("Setup failed: no farmland at " + soil.toShortString());
            }

            // 1. Sow. Success is a wheat crop standing where airtight farmland was.
            String sow = use(server, "wheat_seeds", soil);
            if (sow != null) throw new AssertionError("use refused: " + sow);
            try {
                server.waitFor(s -> s.overworld().getBlockState(soil.above()).getBlock() instanceof CropBlock, 300);
            } catch (Throwable t) {
                throw new AssertionError("No crop was planted; the block above the farmland is "
                        + server.computeOnServer(s -> s.overworld().getBlockState(soil.above()).toString())
                        + " and he carries " + inventory(server), t);
            }
            int ageBefore = server.computeOnServer(s -> cropAge(s, soil));

            // 2. Bone meal it. Success is an age that went up.
            String feed = use(server, "bone_meal", soil.above());
            if (feed != null) throw new AssertionError("use refused: " + feed);
            try {
                server.waitFor(s -> cropAge(s, soil) > ageBefore, 300);
            } catch (Throwable t) {
                throw new AssertionError("Bone meal did not advance the crop: age was " + ageBefore
                        + " and is still " + server.computeOnServer(s -> cropAge(s, soil)), t);
            }
            int ageAfter = server.computeOnServer(s -> cropAge(s, soil));

            // 3. A use that throws must leave nothing behind, on the actor or in his bag.
            String bagBefore = inventory(server);
            boolean threw = false;
            try {
                server.computeOnServer(s -> {
                    FakeActor.useOnBlock(CobbieTestSupport.cobbie(s).orElseThrow(), soil, null,
                            new ItemStack(Items.WHEAT_SEEDS, 3));
                    return null;
                });
            } catch (RuntimeException expected) {
                threw = true;
            }
            if (!threw) {
                throw new AssertionError("The deliberate failure did not throw, so the cleanup path is untested");
            }
            boolean handCleared = server.computeOnServer(s ->
                    net.fabricmc.fabric.api.entity.FakePlayer
                            .get(s.overworld(), new com.mojang.authlib.GameProfile(
                                    FakeActor.PROFILE_ID, FakeActor.PROFILE_NAME))
                            .getMainHandItem().isEmpty());
            if (!handCleared) {
                throw new AssertionError("The actor kept the stack after a throwing call, so the next use"
                        + " would start with his leftover in hand");
            }
            String bagAfter = inventory(server);
            if (!bagBefore.equals(bagAfter)) {
                throw new AssertionError("His bag changed across a throwing use: was " + bagBefore
                        + " now " + bagAfter);
            }

            // 4. No phantom in the save: his bag and identity are stable, and no player data file
            // appeared for the actor's profile.
            server.computeOnServer(s -> {
                s.getPlayerList().saveAll();
                return null;
            });
            Path playerData = server.computeOnServer(s -> s.getWorldPath(LevelResource.PLAYER_DATA_DIR));
            Path phantom = playerData.resolve(FakeActor.PROFILE_ID + ".dat");
            if (Files.exists(phantom)) {
                throw new AssertionError("The actor wrote player data at " + phantom + " — he is in the save");
            }
            int players = server.computeOnServer(s -> s.getPlayerList().getPlayers().size());
            if (players != 1) {
                throw new AssertionError("The player list has " + players + " entries, expected 1");
            }

            System.out.println("[gametest] use ok: sowed wheat on farmland, bone meal took age " + ageBefore
                    + " -> " + ageAfter + "; a throwing use left his bag and the actor's hand untouched;"
                    + " no phantom player data in the save");
        }
    }

    private static String use(TestServerContext server, String item, BlockPos at) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "use " + item,
                List.of(new ActionIntent(ActionVocabulary.USE, item, at.getX(), at.getY(), at.getZ(), ""))));
    }

    private static int cropAge(MinecraftServer server, BlockPos soil) {
        var state = server.overworld().getBlockState(soil.above());
        return state.getBlock() instanceof CropBlock ? state.getValue(CropBlock.AGE) : -1;
    }

    private static String inventory(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::describeInventory).orElse("no companion"));
    }
}
