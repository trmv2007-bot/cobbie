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

            // 5. Bare-handed block use: a lever has to toggle. This is the branch with no item at all.
            BlockPos lever = new BlockPos(start.getX() - 2, start.getY(), start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + lever.getX() + " " + (lever.getY() - 1) + " "
                        + lever.getZ() + " minecraft:stone");
                CobbieTestSupport.command(s, "setblock " + lever.getX() + " " + lever.getY() + " "
                        + lever.getZ() + " minecraft:lever[face=floor,facing=north,powered=false]");
                return null;
            });
            if (server.computeOnServer(s -> !s.overworld().getBlockState(lever)
                    .is(net.minecraft.world.level.block.Blocks.LEVER))) {
                throw new AssertionError("Setup failed: no lever at " + lever.toShortString());
            }
            String pull = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "pull the lever",
                    List.of(new ActionIntent(ActionVocabulary.USE, "", lever.getX(), lever.getY(),
                            lever.getZ(), ""))));
            if (pull != null) throw new AssertionError("bare-hand use refused: " + pull);
            try {
                server.waitFor(s -> s.overworld().getBlockState(lever)
                        .getValue(net.minecraft.world.level.block.LeverBlock.POWERED), 300);
            } catch (Throwable t) {
                throw new AssertionError("The lever never moved; it reads "
                        + server.computeOnServer(s -> s.overworld().getBlockState(lever).toString()), t);
            }

            // 6. An item that acts on its own: a bucket. Filling is not a block interaction in this
            // version - it raycasts from the eye - so the water is placed right beside him and the
            // plan runs immediately, which is the state the probe showed working.
            BlockPos water = server.computeOnServer(s ->
                    CobbieTestSupport.cobbie(s).orElseThrow().blockPosition().offset(2, 0, 0));
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "fill " + (water.getX() - 1) + " " + water.getY() + " "
                        + (water.getZ() - 1) + " " + (water.getX() + 1) + " " + water.getY() + " "
                        + (water.getZ() + 1) + " minecraft:water");
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.BUCKET, 1));
                return null;
            });
            String fill = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "fill the bucket",
                    List.of(new ActionIntent(ActionVocabulary.USE, "bucket", water.getX(), water.getY(),
                            water.getZ(), ""))));
            if (fill != null) throw new AssertionError("bucket use refused: " + fill);
            try {
                server.waitFor(s -> carried(s, "minecraft:water_bucket") >= 1, 300);
            } catch (Throwable t) {
                throw new AssertionError("No water bucket was filled; he carries " + inventory(server)
                        + " and the water is " + server.computeOnServer(s ->
                        s.overworld().getBlockState(water).toString()), t);
            }

            // 7. A second bare-handed block: a button. Pressing it is a momentary state, so the test
            // watches for it during the press rather than after.
            BlockPos button = new BlockPos(start.getX() - 3, start.getY(), start.getZ());
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "setblock " + button.getX() + " " + (button.getY() - 1) + " "
                        + button.getZ() + " minecraft:stone");
                CobbieTestSupport.command(s, "setblock " + button.getX() + " " + button.getY() + " "
                        + button.getZ() + " minecraft:stone_button[face=floor,facing=north,powered=false]");
                return null;
            });
            String press = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                    "press the button",
                    List.of(new ActionIntent(ActionVocabulary.USE, "", button.getX(), button.getY(),
                            button.getZ(), ""))));
            if (press != null) throw new AssertionError("button use refused: " + press);
            boolean pressed = false;
            for (int i = 0; i < 60 && !pressed; i++) {
                context.waitTicks(1);
                pressed = server.computeOnServer(s -> s.overworld().getBlockState(button)
                        .getValue(net.minecraft.world.level.block.ButtonBlock.POWERED));
            }
            if (!pressed) {
                throw new AssertionError("The button was never pressed; it reads "
                        + server.computeOnServer(s -> s.overworld().getBlockState(button).toString()));
            }

            System.out.println("[gametest] use ok: sowed wheat on farmland, bone meal took age " + ageBefore
                    + " -> " + ageAfter + "; a bare hand toggled a lever and pressed a button; a bucket"
                    + " came back as a water bucket; a throwing"
                    + " use left his bag and the actor's hand untouched; no phantom player data in the"
                    + " save");
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

    private static int carried(TestServerContext server, String id) {
        return server.computeOnServer(s -> carried(s, id));
    }

    private static int carried(MinecraftServer server, String id) {
        return CobbieTestSupport.cobbie(server).map(c -> c.countItem(id)).orElse(-1);
    }

    private static String inventory(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::describeInventory).orElse("no companion"));
    }
}
