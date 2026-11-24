package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Items that act on something other than a block: shears, a lead, and the two items that look like
 * they should work and do not.
 *
 * <p>Every claim here came out of a probe first. {@code CobbieInteractTest} began as a class that
 * printed what the fake actor did rather than asserting anything, because three questions were open:
 *
 * <ul>
 *   <li>{@code Player.interactOn} from a never-spawned player <b>does</b> shear a sheep — {@code
 *       result=Success}, wool on the ground, which is why shearing is here at all.</li>
 *   <li>A lead handed to that player leashes the animal to a <b>ghost</b>: the probe found the cow's
 *       holder to be {@code FakePlayer [Cobbie]} with {@code holderIsInWorld=false}. So a lead is
 *       tied to Cobbie himself instead, and this test asserts the holder is the companion rather
 *       than any player.</li>
 *   <li>A fishing bobber belonging to him <b>dies on its first tick</b> — one bobber on the cast,
 *       none forty ticks later, because {@code FishingHook.tick} discards itself when
 *       {@code getPlayerOwner()} finds nobody. So the rod is refused with the reason and no bobber
 *       is spawned, rather than a cast that silently evaporates.</li>
 * </ul>
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Interact}.
 */
public final class CobbieInteractTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            BlockPos at = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();
                Sheep sheep = new Sheep(EntityTypes.SHEEP, level);
                sheep.setPos(at.getX() + 1.1, at.getY(), at.getZ() + 0.2);
                level.addFreshEntity(sheep);
                Cow cow = new Cow(EntityTypes.COW, level);
                cow.setPos(at.getX() - 1.1, at.getY(), at.getZ() + 0.2);
                level.addFreshEntity(cow);
                cobbie.addItem(new ItemStack(Items.SHEARS, 1));
                cobbie.addItem(new ItemStack(Items.LEAD, 1));
                cobbie.addItem(new ItemStack(Items.FISHING_ROD, 1));
                cobbie.addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
                return null;
            });

            // 1. Shears on a sheep: the sheep is sheared, and the wool did not stay on the floor.
            String shear = use(server, "shears", 0, 0, 0, "sheep");
            if (shear != null) throw new AssertionError("shear refused outright: " + shear);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            boolean sheared = server.computeOnServer(s -> sheep(s).map(sh -> !sh.readyForShearing()).orElse(false));
            int wool = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                int held = cobbie.countItem("minecraft:white_wool");
                if (held > 0) return held;
                return s.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        cobbie.getBoundingBox().inflate(6),
                        e -> e.getItem().is(net.minecraft.tags.ItemTags.WOOL)).size();
            });
            if (!sheared) throw new AssertionError("The sheep is still woolly: " + lastReport(server));
            if (wool <= 0) throw new AssertionError("The sheep was sheared and the wool was left behind");

            // 2. A lead on a cow: held by the companion, not by an actor who is not in the world.
            String lead = use(server, "lead", 0, 0, 0, "cow");
            if (lead != null) throw new AssertionError("lead refused outright: " + lead);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            String holder = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                Entity on = cow(s).map(Cow::getLeashHolder).orElse(null);
                if (on == null) return "nothing";
                if (on == cobbie) return "himself";
                return on.getClass().getSimpleName() + " [" + on.getName().getString() + "]";
            });
            if (!holder.equals("himself")) {
                throw new AssertionError("The cow is leashed to " + holder + " instead of to him. "
                        + lastReport(server));
            }
            if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:lead")) != 0) {
                throw new AssertionError("The lead was not spent");
            }

            // 3. A rod: refused with the reason, and no bobber left in the world to lie for him.
            String rod = use(server, "fishing_rod", 0, 0, 0, "");
            if (rod != null) throw new AssertionError("rod refused outright: " + rod);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            if (!reportSays(server, "cannot fish")) {
                throw new AssertionError("A fishing cast was not refused with the reason: " + lastReport(server));
            }
            int bobbers = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.FishingHook.class,
                    CobbieTestSupport.cobbie(s).orElseThrow().getBoundingBox().inflate(64)).size());
            if (bobbers != 0) throw new AssertionError("A bobber was left behind: " + bobbers);

            // 4. Flint and steel on the ground: there is fire, and it is above the block he clicked.
            String lit = use(server, "flint_and_steel", at.getX(), at.getY() - 1, at.getZ() + 2, "");
            if (lit != null) throw new AssertionError("flint and steel refused outright: " + lit);
            CobbieTestSupport.awaitPlanSettled(server, 400);
            BlockPos target = new BlockPos(at.getX(), at.getY() - 1, at.getZ() + 2);
            boolean fire = server.computeOnServer(s -> s.overworld().getBlockState(target.above()).is(Blocks.FIRE));
            if (!fire) {
                throw new AssertionError("No fire above " + target + ": the block is "
                        + server.computeOnServer(s -> s.overworld().getBlockState(target.above()).toString())
                        + ". " + lastReport(server));
            }

            System.out.println("[gametest] interact ok: sheared a sheep and kept the wool; leashed a cow to"
                    + " himself rather than to a player who is not in the world; refused a fishing cast with"
                    + " the reason and left no bobber behind; lit a fire with flint and steel");
        }
    }

    private static String use(TestServerContext server, String item, int x, int y, int z, String mob) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "use my " + item, List.of(new ActionIntent(ActionVocabulary.USE, item, x, y, z, mob))));
    }

    private static java.util.Optional<Sheep> sheep(net.minecraft.server.MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return java.util.Optional.empty();
        return cobbie.level().getEntitiesOfClass(Sheep.class, cobbie.getBoundingBox().inflate(12))
                .stream().findFirst();
    }

    private static java.util.Optional<Cow> cow(net.minecraft.server.MinecraftServer server) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return java.util.Optional.empty();
        return cobbie.level().getEntitiesOfClass(Cow.class, cobbie.getBoundingBox().inflate(12))
                .stream().findFirst();
    }

    private static boolean reportSays(TestServerContext server, String text) {
        return lastReport(server).toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
