package com.rishi.cobbie.gametest;

import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Following you through a portal.
 *
 * <p>Two separate claims, asserted in order so a failure says which one broke. First ours: with you in
 * the nether and no path to you at all, he walks to the portal and stands in it. Then the game's: the
 * portal carries him across, he arrives in the nether, and his bag and levels come with him.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Portal}.
 */
public final class CobbiePortalTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // A lit nether portal beside him: an obsidian frame with the portal blocks inside it.
            BlockPos start = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition());
            int x = start.getX() + 3;
            int y = start.getY();
            int z = start.getZ();
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "fill " + (x - 1) + " " + (y - 1) + " " + z + " "
                        + (x + 2) + " " + (y + 3) + " " + z + " minecraft:obsidian hollow");
                CobbieTestSupport.command(s, "fill " + x + " " + y + " " + z + " " + (x + 1) + " " + (y + 2)
                        + " " + z + " minecraft:nether_portal[axis=x]");
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.DIAMOND, 5));
                cobbie.setExperience(7, 0.0f);
                return null;
            });
            boolean lit = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, y, z))
                    .is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL));
            if (!lit) throw new AssertionError("Setup failed: the portal did not light");

            int levelBefore = server.computeOnServer(CobbiePortalTest::xp);
            int diamondsBefore = server.computeOnServer(CobbiePortalTest::diamonds);

            // You go through, properly, to the nether.
            server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                var nether = s.getLevel(Level.NETHER);
                owner.teleportTo(nether, 8, 64, 8, java.util.Set.of(), 0.0F, 0.0F, true);
                return null;
            });
            context.waitTicks(10);

            // If the owner never actually left, this test proves nothing: fail with the fact, not a hang.
            String ownerWhere = server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                return owner.level().dimension().toString() + " " + owner.blockPosition().toShortString();
            });
            System.out.println("[portal] owner is " + ownerWhere + "; cobbie is " + where(server));
            if (!ownerWhere.contains("the_nether")) {
                throw new AssertionError("Setup failed: the owner was not moved to the nether (" + ownerWhere + ")");
            }

            // Ours: he finds the portal and stands in it.
            try {
                server.waitFor(s -> anyCobbie(s)
                        .map(c -> c.level().getBlockState(c.blockPosition())
                                .is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL))
                        .orElse(false), 900);
            } catch (Throwable t) {
                throw new AssertionError("He never reached the portal; he is at " + where(server), t);
            }

            // The game's: the portal carries him across.
            try {
                server.waitFor(s -> anyCobbie(s)
                        .map(c -> c.level().dimension() == Level.NETHER)
                        .orElse(false), 1200);
            } catch (Throwable t) {
                throw new AssertionError("The portal never carried him across; he is " + where(server), t);
            }

            int levelAfter = server.computeOnServer(CobbiePortalTest::xp);
            int diamondsAfter = server.computeOnServer(CobbiePortalTest::diamonds);
            if (diamondsAfter != diamondsBefore) {
                throw new AssertionError("His bag did not survive the crossing: " + diamondsBefore + " -> "
                        + diamondsAfter + " diamonds");
            }
            if (levelAfter != levelBefore) {
                throw new AssertionError("His level did not survive the crossing: " + levelBefore + " -> "
                        + levelAfter);
            }

            // The ticket exists only while a crossing is pending: after it, nothing may be held open.
            int pending = com.rishi.cobbie.entity.CrossDimensionFollow.pending();
            if (pending != 0) {
                throw new AssertionError("A chunk ticket leaked: " + pending + " crossing(s) still held open");
            }

            System.out.println("[gametest] portal ok: walked into the portal with the owner in the nether,"
                    + " crossed, arrived carrying " + diamondsAfter + " diamonds and level " + levelAfter);
        }
    }

    /**
     * The companion in any dimension.
     *
     * <p>The ordinary helper searches near the owner, and once the owner is in the nether it cannot find
     * a companion still in the overworld — which is exactly the state this test is about.
     */
    private static java.util.Optional<CobbieEntity> anyCobbie(net.minecraft.server.MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CobbieEntity cobbie && cobbie.isAlive()) return java.util.Optional.of(cobbie);
            }
        }
        return java.util.Optional.empty();
    }

    private static int xp(net.minecraft.server.MinecraftServer server) {
        return anyCobbie(server).map(CobbieEntity::experienceLevel).orElse(-1);
    }

    private static int diamonds(net.minecraft.server.MinecraftServer server) {
        return anyCobbie(server).map(c -> c.countItem("minecraft:diamond")).orElse(-1);
    }

    private static String where(TestServerContext server) {
        return server.computeOnServer(s -> anyCobbie(s)
                .map(c -> c.level().dimension().toString() + " " + c.blockPosition().toShortString())
                .orElse("not in any loaded level"));
    }
}
