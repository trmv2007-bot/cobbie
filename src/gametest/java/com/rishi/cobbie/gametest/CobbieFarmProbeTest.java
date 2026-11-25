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
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A probe, not a test: what the verbs he already has do to a farm.
 *
 * <p>Farming is the one area where most of the capability may already be there — a hoe is a
 * {@code BLOCK_TRANSFORMER} data component rather than a class, seeds are a plain {@code BlockItem},
 * and both reach the world through {@code ItemStack.useOn}, which the actor has been able to call
 * since 0.21.0. Guessing either way would be a waste: five lines of probe settle which parts of
 * farming are missing before any new verb is written.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=FarmProbe}. Deliberately free of
 * assertions so the numbers always print.
 */
public final class CobbieFarmProbeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();
                BlockPos base = cobbie.blockPosition();
                BlockPos dirt = base.offset(1, -1, 1);
                BlockPos beside = base.offset(2, -1, 1);

                // --- 1. a hoe on dirt, through the actor ---
                CobbieTestSupport.command(s, "setblock " + dirt.getX() + " " + dirt.getY() + " " + dirt.getZ()
                        + " minecraft:dirt");
                BlockState before = level.getBlockState(dirt);
                FakeActor.Outcome hoed = FakeActor.useOnBlock(cobbie, dirt, Direction.UP,
                        new ItemStack(Items.IRON_HOE, 1));
                System.out.println("[probe] hoe: " + before.getBlock() + " -> " + level.getBlockState(dirt).getBlock()
                        + " (result=" + (hoed == null ? "null" : hoed.result()) + ")");

                // --- 2. seeds on that farmland ---
                BlockState farmland = level.getBlockState(dirt);
                BlockPos above = dirt.above();
                if (farmland.is(Blocks.FARMLAND)) {
                    FakeActor.Outcome sowed = FakeActor.useOnBlock(cobbie, dirt, Direction.UP,
                            new ItemStack(Items.WHEAT_SEEDS, 2));
                    System.out.println("[probe] seeds on farmland: " + level.getBlockState(above).getBlock()
                            + " (result=" + (sowed == null ? "null" : sowed.result()) + ")");
                } else {
                    System.out.println("[probe] seeds skipped: the hoe did not make farmland");
                }

                // --- 3. bone meal on the seedling ---
                if (level.getBlockState(above).getBlock() instanceof CropBlock) {
                    int ageBefore = level.getBlockState(above).getValue(CropBlock.AGE);
                    FakeActor.useOnBlock(cobbie, above, Direction.UP, new ItemStack(Items.BONE_MEAL, 4));
                    System.out.println("[probe] bone meal: age " + ageBefore + " -> "
                            + level.getBlockState(above).getValue(CropBlock.AGE));
                }

                // --- 4. a ripe crop, and what breaking it gives ---
                CobbieTestSupport.command(s, "setblock " + beside.getX() + " " + beside.getY() + " " + beside.getZ()
                        + " minecraft:farmland");
                CobbieTestSupport.command(s, "setblock " + beside.getX() + " " + (beside.getY() + 1) + " "
                        + beside.getZ() + " minecraft:wheat[age=7]");
                int drops = level.getBlockState(beside.above()).getDrops(
                        new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                                        net.minecraft.world.phys.Vec3.atCenterOf(beside.above()))
                                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,
                                        ItemStack.EMPTY)
                                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE,
                                        level.getBlockState(beside.above()))).stream()
                        .mapToInt(ItemStack::getCount).sum();
                System.out.println("[probe] ripe wheat drops " + drops + " item(s) with a bare hand");
                System.out.println("[probe] done");
                return null;
            });
        }
    }
}
