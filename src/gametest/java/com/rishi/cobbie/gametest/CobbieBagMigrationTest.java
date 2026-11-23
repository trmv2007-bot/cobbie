package com.rishi.cobbie.gametest;

import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one part of the bag work that could still lose items: a save written when his bag was bigger
 * than the config now says.
 *
 * <p>Growing the bag is trivially safe — old stacks land in the same slots. Shrinking it used to
 * delete everything past the last slot on load. This writes a companion with 54 slots of cobblestone,
 * saves it the way the game does, drops the config to 45, loads that save into a fresh companion, and
 * asserts both halves: 45 stacks in the bag, and the other 9 on the ground beside him as items.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=BagMigration}.
 */
public final class CobbieBagMigrationTest implements FabricClientGameTest {

    private static final int BIG = 54;
    private static final int SMALL = 45;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();

            int original = setBagSize(BIG);
            if (original < BIG) {
                throw new AssertionError("Could not raise inventory_size to " + BIG + ", config still says "
                        + original);
            }
            CobbieTestSupport.spawn(context, server);

            // One cobblestone in every one of the big bag's slots, so "45 + 9" is unambiguous.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                for (int i = 0; i < cobbie.getInventoryContainer().getContainerSize(); i++) {
                    cobbie.getInventoryContainer().setItem(i, new ItemStack(Items.COBBLESTONE, 1));
                }
                return null;
            });
            int filled = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .getInventoryContainer().getContainerSize());
            if (filled != BIG) throw new AssertionError("Setup failed: bag is " + filled + " slots, not " + BIG);

            // Save it exactly the way the game does.
            CompoundTag saved = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                        s.registryAccess());
                cobbie.saveWithoutId(out);
                return out.buildResult();
            });

            int now = setBagSize(SMALL);
            if (now != SMALL) throw new AssertionError("Could not lower inventory_size, config says " + now);

            // Load that save into a fresh companion with the smaller bag. The original is discarded
            // first so the "find him near the owner" helper cannot hand back the 54-slot one.
            server.computeOnServer(s -> {
                CobbieEntity old = CobbieTestSupport.cobbie(s).orElseThrow();
                double x = old.getX();
                double y = old.getY();
                double z = old.getZ();
                old.discard();
                CobbieEntity fresh = new CobbieEntity(CobbieEntities.COBBIE, s.overworld());
                fresh.load(TagValueInput.create(ProblemReporter.DISCARDING, s.registryAccess(), saved));
                fresh.setPos(x, y, z);
                s.overworld().addFreshEntity(fresh);
                return null;
            });

            int slots = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .getInventoryContainer().getContainerSize());
            if (slots != SMALL) {
                throw new AssertionError("The loaded companion has " + slots + " slots, expected " + SMALL);
            }

            // One tick of his life runs the spill, so give him a moment.
            try {
                server.waitFor(s -> spilledCobblestone(s) >= BIG - SMALL, 100);
            } catch (Throwable t) {
                throw new AssertionError("The " + (BIG - SMALL) + " stacks past the last slot were not put on"
                        + " the ground — they were lost. Bag holds "
                        + server.computeOnServer(CobbieBagMigrationTest::bagCobblestone)
                        + ", ground holds " + server.computeOnServer(CobbieBagMigrationTest::spilledCobblestone), t);
            }

            int bag = server.computeOnServer(CobbieBagMigrationTest::bagCobblestone);
            int ground = server.computeOnServer(CobbieBagMigrationTest::spilledCobblestone);
            if (bag + ground != BIG) {
                throw new AssertionError("Items were lost across the resize: bag " + bag + " + ground "
                        + ground + " is not " + BIG);
            }

            setBagSize(45);
            System.out.println("[gametest] bag migration ok: " + BIG + " saved -> " + SMALL + " slots kept "
                    + bag + " in the bag and put " + ground + " on the ground, none lost");
        } finally {
            setBagSize(45);
        }
    }

    /** Rewrite inventory_size in the run's config file and re-read it. Returns the value now in force. */
    private static int setBagSize(int size) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cobbie.json");
        try {
            String json = Files.exists(file) ? Files.readString(file) : "{}";
            String updated = json.matches("(?s).*\"inventory_size\"\\s*:\\s*\\d+.*")
                    ? json.replaceAll("\"inventory_size\"\\s*:\\s*\\d+", "\"inventory_size\": " + size)
                    : json.replaceFirst("\\{", "{\"inventory_size\": " + size + ",");
            Files.writeString(file, updated);
        } catch (Exception e) {
            throw new AssertionError("Could not write the config file: " + e, e);
        }
        CobbieConfig.reload();
        return CobbieConfig.getInventorySize();
    }

    private static int bagCobblestone(MinecraftServer server) {
        return CobbieTestSupport.cobbie(server, 32)
                .map(c -> c.countItem("minecraft:cobblestone"))
                .orElse(-1);
    }

    private static int spilledCobblestone(MinecraftServer server) {
        var owner = CobbieTestSupport.owner(server);
        return server.overworld().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(owner.blockPosition()).inflate(16.0),
                        e -> e.getItem().is(Items.COBBLESTONE))
                .stream().mapToInt(e -> e.getItem().getCount()).sum();
    }
}
