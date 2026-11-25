package com.rishi.cobbie.gametest;

import com.rishi.cobbie.actor.FakeActor;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A probe, not a test: which redstone components the {@code use} verb already operates.
 *
 * <p>Levers and buttons were done at 0.21.1. The fiddly tail is repeaters, comparators, note blocks and
 * the things that only respond to redstone, and the brief's condition is explicit: if a piece needs a
 * fake player to be a redstone *actor* rather than an interactor, say so and drop it. That distinction
 * cannot be read off a class list, so this prints what each component does when he uses it.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=RedstoneProbe}. Prints, never asserts.
 */
public final class CobbieRedstoneProbeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos base = cobbie.blockPosition();
                report(s, cobbie, base, "lever", "minecraft:lever[face=floor,facing=north,powered=false]");
                report(s, cobbie, base, "button", "minecraft:stone_button[face=floor,facing=north,powered=false]");
                report(s, cobbie, base, "repeater", "minecraft:repeater[facing=north,delay=1]");
                report(s, cobbie, base, "comparator", "minecraft:comparator[facing=north,mode=compare]");
                report(s, cobbie, base, "noteblock", "minecraft:note_block[note=0]");
                report(s, cobbie, base, "piston", "minecraft:piston[facing=north]");
                report(s, cobbie, base, "iron_door", "minecraft:iron_door[facing=north,half=lower,open=false]");
                report(s, cobbie, base, "daylight", "minecraft:daylight_detector");
                System.out.println("[probe] done");
                return null;
            });
        }
    }

    /** Place a component beside him, use it, and print the state before and after. */
    private static void report(MinecraftServer s, CobbieEntity cobbie, BlockPos base, String label, String spec) {
        BlockPos pos = base.offset(1, -1, 1);
        CobbieTestSupport.command(s, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + spec);
        BlockState before = s.overworld().getBlockState(pos);
        FakeActor.Outcome used = FakeActor.useOnBlock(cobbie, pos, Direction.UP, ItemStack.EMPTY);
        BlockState after = s.overworld().getBlockState(pos);
        System.out.println("[probe] " + label + ": " + (before.equals(after) ? "unchanged" : "CHANGED")
                + " " + state(before) + " -> " + state(after)
                + " result=" + (used == null ? "null" : used.result()));
    }

    private static String state(BlockState state) {
        StringBuilder sb = new StringBuilder(state.getBlock().getName().getString());
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            sb.append(" powered=").append(state.getValue(BlockStateProperties.POWERED));
        }
        if (state.hasProperty(BlockStateProperties.DELAY)) {
            sb.append(" delay=").append(state.getValue(BlockStateProperties.DELAY));
        }
        if (state.hasProperty(BlockStateProperties.OPEN)) {
            sb.append(" open=").append(state.getValue(BlockStateProperties.OPEN));
        }
        if (state.hasProperty(BlockStateProperties.NOTE)) {
            sb.append(" note=").append(state.getValue(BlockStateProperties.NOTE));
        }
        return sb.toString();
    }
}
