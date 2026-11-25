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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A probe, not a test: what the decorative tail actually needs.
 *
 * <p>Signs, item frames, banners and beacons are four different problems wearing one label. Half of a
 * previous slice turned out to need no code at all because {@code use} already did it through
 * {@code ItemStack.useOn}, so this prints what each one does rather than guessing — and, where a
 * mechanism cannot be reached without a real player, prints that too instead of inventing a way.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=DecorProbe}. Prints, never asserts.
 */
public final class CobbieDecorProbeTest implements FabricClientGameTest {

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

                // --- 1. A sign: does using it write anything, and can the text be set at all? ---
                BlockPos signPos = base.offset(2, 0, 2);
                CobbieTestSupport.command(s, "setblock " + xyz(signPos) + " minecraft:oak_sign[rotation=0]");
                BlockEntity be = s.overworld().getBlockEntity(signPos);
                FakeActor.Outcome used = FakeActor.useOnBlock(cobbie, signPos, Direction.UP, ItemStack.EMPTY);
                System.out.println("[probe] sign: blockEntity=" + (be instanceof SignBlockEntity)
                        + " use=" + (used == null ? "null" : used.result()));
                System.out.println("[probe] sign API: " + methods(net.minecraft.world.level.block.entity.SignBlockEntity.class));
                System.out.println("[probe] sign text API: " + methods(net.minecraft.world.level.block.entity.SignText.class));

                // --- 2. An item frame: place it, put something in it, take it out. ---
                BlockPos wall = base.offset(-2, 0, 2);
                CobbieTestSupport.command(s, "setblock " + xyz(wall) + " minecraft:stone");
                ItemStack frame = new ItemStack(Items.ITEM_FRAME, 1);
                FakeActor.Outcome placedFrame = FakeActor.useOnBlock(cobbie, wall, Direction.NORTH, frame);
                BlockPos framePos = wall.north();
                var entities = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                        new net.minecraft.world.phys.AABB(framePos).inflate(1.5));
                System.out.println("[probe] frame: placed=" + (placedFrame == null ? "null"
                        : placedFrame.result()) + " frames=" + entities.size());
                if (!entities.isEmpty()) {
                    var one = entities.get(0);
                    FakeActor.Outcome put = FakeActor.useOnHanging(cobbie, one, new ItemStack(Items.DIAMOND, 1));
                    System.out.println("[probe] frame: insert=" + (put == null ? "null" : put.result())
                            + " holds=" + one.getItem().getItem());
                    FakeActor.Outcome got = FakeActor.useOnHanging(cobbie, one, ItemStack.EMPTY);
                    System.out.println("[probe] frame: use-empty=" + (got == null ? "null" : got.result())
                            + " holds=" + one.getItem().getItem() + " carried="
                            + cobbie.countItem("minecraft:diamond"));
                }

                // --- 3. A banner: what does the item look like in this version, and what does a placed
                // one carry? Printed from reflection because the colour and patterns moved into item
                // components and guessing the API twice is exactly what a probe is for.
                StringBuilder banners = new StringBuilder();
                for (var field : Items.class.getFields()) {
                    if (field.getName().contains("BANNER")) {
                        banners.append(field.getName()).append('(')
                                .append(field.getType().getSimpleName()).append(") ");
                    }
                }
                System.out.println("[probe] banner items: " + banners);
                System.out.println("[probe] color collection API: " + methods(Items.BANNER.getClass()));

                // --- 4. A beacon: can it be switched on without a player? ---
                BlockPos beaconPos = base.offset(-3, 0, -2);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        CobbieTestSupport.command(s, "setblock " + xyz(beaconPos.offset(dx, -1, dz))
                                + " minecraft:iron_block");
                    }
                }
                CobbieTestSupport.command(s, "setblock " + xyz(beaconPos) + " minecraft:beacon");
                BlockEntity beaconBe = s.overworld().getBlockEntity(beaconPos);
                FakeActor.Outcome paid = FakeActor.useOnBlock(cobbie, beaconPos, Direction.UP,
                        new ItemStack(Items.IRON_INGOT, 1));
                BlockState after = s.overworld().getBlockState(beaconPos);
                System.out.println("[probe] beacon: blockEntity=" + (beaconBe == null ? "none"
                        : beaconBe.getClass().getSimpleName()) + " use=" + (paid == null ? "null"
                        : paid.result()) + " block=" + after.getBlock().getName().getString());

                System.out.println("[probe] done");
                return null;
            });
        }
    }

    /** Method names worth knowing, read from the class at run time rather than guessed in source. */
    private static String methods(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (var m : type.getMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("text") || n.contains("message") || n.contains("pattern")
                    || n.contains("item") || n.contains("level")) {
                sb.append(m.getName()).append('/').append(m.getParameterCount()).append(' ');
            }
        }
        return sb.toString();
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
