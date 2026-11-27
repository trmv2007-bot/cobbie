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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The tail of the right-click: the abilities that need no verb of their own because the {@code use}
 * verb already reaches them, and that no class in the suite had proved.
 *
 * <p>{@code docs/PLAYER-CAPABILITIES.md} is the inventory of what a player can do, and a row it cannot
 * point at a test for is a row that has to be called <em>untested</em> rather than done. Tilling,
 * milking, naming, taming, a jukebox, a beehive, a water bucket, a spawn egg and a primed TNT all ride
 * the same two paths — {@code ItemStack.useOn} on a block, {@code Player.interactOn} on a mob — as the
 * abilities that were already proved, so the open question was never "does the code exist" but "does
 * the game do it for an actor who is not in the world". This asks the game.
 *
 * <p>Every case is independent and its failure is collected rather than thrown, so one wrong row is one
 * wrong row and not a suite that stops at the first. The class fails at the end with all of them, each
 * naming the block, the item and what he last said.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Tail}.
 */
public final class CobbieTailTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.command(server, "cobbie stay");

            BlockPos base = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().blockPosition());
            List<String> failures = new ArrayList<>();

            // 1. Till a block of dirt with a hoe: a player's click turns it into farmland.
            run(failures, "till with a hoe", server, () -> {
                BlockPos dirt = base.offset(2, -1, 0);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + dirt.getX() + " " + dirt.getY() + " "
                            + dirt.getZ() + " minecraft:dirt");
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.IRON_HOE, 1));
                    return null;
                });
                String refused = use(server, "iron_hoe", dirt, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (server.computeOnServer(s -> !s.overworld().getBlockState(dirt).is(Blocks.FARMLAND))) {
                    throw new AssertionError("the dirt is still "
                            + server.computeOnServer(s -> s.overworld().getBlockState(dirt).toString())
                            + ": " + lastReport(server));
                }
            });

            // 2. Milk a cow with a bucket: the bucket comes back full.
            run(failures, "milk a cow", server, () -> {
                BlockPos at = base.offset(-2, 0, 2);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "summon minecraft:cow " + at.getX() + " " + at.getY() + " "
                            + at.getZ());
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.BUCKET, 1));
                    return null;
                });
                String refused = use(server, "bucket", BlockPos.ZERO, "cow");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                int milk = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .countItem("minecraft:milk_bucket"));
                if (milk < 1) throw new AssertionError("no milk bucket afterwards: " + lastReport(server));
            });

            // 3. Put a name tag on a pig: the name lands and the tag is spent.
            run(failures, "name a pig with a name tag", server, () -> {
                BlockPos at = base.offset(3, 0, 2);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "summon minecraft:pig " + at.getX() + " " + at.getY() + " "
                            + at.getZ());
                    ItemStack tag = new ItemStack(Items.NAME_TAG, 1);
                    tag.set(DataComponents.CUSTOM_NAME, Component.literal("Wilbur"));
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(tag);
                    return null;
                });
                String refused = use(server, "name_tag", BlockPos.ZERO, "pig");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                String named = server.computeOnServer(s -> {
                    Pig pig = all(s, Pig.class).stream().findFirst().orElse(null);
                    return pig == null || pig.getCustomName() == null ? "" : pig.getCustomName().getString();
                });
                if (!named.equals("Wilbur")) {
                    throw new AssertionError("the pig is called \"" + named + "\": " + lastReport(server));
                }
                if (server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .countItem("minecraft:name_tag")) != 0) {
                    throw new AssertionError("the name tag was not spent");
                }
            });

            // 4. Tame a wolf with bones. A tame roll is a chance per bone, exactly as it is for a
            //    player, so this hands him a handful and stops when the wolf stops being wild.
            run(failures, "tame a wolf with bones", server, () -> {
                BlockPos at = base.offset(-3, 0, -2);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "summon minecraft:wolf " + at.getX() + " " + at.getY() + " "
                            + at.getZ());
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.BONE, 8));
                    return null;
                });
                boolean tamed = false;
                for (int bone = 0; bone < 6 && !tamed; bone++) {
                    String refused = use(server, "bone", BlockPos.ZERO, "wolf");
                    if (refused != null) throw new AssertionError("refused outright: " + refused);
                    CobbieTestSupport.awaitPlanSettled(server, 400);
                    tamed = server.computeOnServer(s -> {
                        Wolf wolf = all(s, Wolf.class).stream().findFirst().orElse(null);
                        return wolf != null && wolf.isTame();
                    });
                }
                if (!tamed) throw new AssertionError("the wolf is still wild: " + lastReport(server));
            });

            // 5. A music disc into a jukebox: the block's own HAS_RECORD is the receipt.
            run(failures, "play a disc in a jukebox", server, () -> {
                BlockPos box = base.offset(0, -1, 4);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + box.getX() + " " + box.getY() + " "
                            + box.getZ() + " minecraft:jukebox");
                    CobbieTestSupport.cobbie(s).orElseThrow()
                            .addItem(new ItemStack(Items.MUSIC_DISC_CAT, 1));
                    return null;
                });
                String refused = use(server, "music_disc_cat", box, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (server.computeOnServer(s -> !s.overworld().getBlockState(box)
                        .getValue(BlockStateProperties.HAS_RECORD))) {
                    throw new AssertionError("the jukebox holds no record: " + lastReport(server));
                }
            });

            // 6. Shear a full beehive: honeycomb, and the hive back to empty.
            run(failures, "shear a full beehive", server, () -> {
                BlockPos hive = base.offset(-4, -1, 4);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + hive.getX() + " " + hive.getY() + " "
                            + hive.getZ() + " minecraft:beehive[honey_level=5]");
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.SHEARS, 1));
                    return null;
                });
                String refused = use(server, "shears", hive, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                int level = server.computeOnServer(s -> s.overworld().getBlockState(hive)
                        .getValue(BlockStateProperties.LEVEL_HONEY));
                if (level != 0) throw new AssertionError("the hive is still at honey level " + level
                        + ": " + lastReport(server));
                // The comb drops at the hive and his pickup only reaches 1.6 blocks, so the claim is
                // "the hive gave it up": in his bag if he was close enough to swallow it, otherwise
                // on the ground beside the hive — the same standard the wool case in
                // CobbieInteractTest holds itself to.
                try {
                    server.waitFor(s -> {
                        CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                        if (cobbie.countItem("minecraft:honeycomb") >= 1) return true;
                        return !s.overworld().getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                new net.minecraft.world.phys.AABB(hive).inflate(2),
                                e -> e.getItem().is(Items.HONEYCOMB)).isEmpty();
                    }, 200);
                } catch (Throwable t) {
                    throw new AssertionError("the hive was sheared and no honeycomb exists: "
                            + lastReport(server), t);
                }
            });

            // 7. A water bucket: a source block where he clicked.
            run(failures, "place water with a bucket", server, () -> {
                BlockPos wet = base.offset(4, -1, -4);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + wet.getX() + " " + wet.getY() + " "
                            + wet.getZ() + " minecraft:stone");
                    CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.WATER_BUCKET, 1));
                    return null;
                });
                String refused = use(server, "water_bucket", wet, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                if (server.computeOnServer(s -> s.overworld().getBlockState(wet.above()).getFluidState()
                        .isEmpty())) {
                    throw new AssertionError("no water above " + wet + ": "
                            + server.computeOnServer(s -> s.overworld().getBlockState(wet.above()).toString())
                            + " — " + lastReport(server));
                }
            });

            // 8. A spawn egg: a second cow that nobody else could have put there.
            run(failures, "hatch a cow spawn egg", server, () -> {
                BlockPos spot = base.offset(-5, -1, -4);
                int before = server.computeOnServer(s -> all(s, Cow.class).size());
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " "
                            + spot.getZ() + " minecraft:stone");
                    CobbieTestSupport.cobbie(s).orElseThrow()
                            .addItem(new ItemStack(Items.COW_SPAWN_EGG, 1));
                    return null;
                });
                String refused = use(server, "cow_spawn_egg", spot, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                int after = server.computeOnServer(s -> all(s, Cow.class).size());
                if (after <= before) throw new AssertionError("no cow appeared (" + before + " -> " + after
                        + "): " + lastReport(server));
            });

            // 9. Prime TNT with flint and steel. The primed entity is the receipt; it is defused
            //    immediately so the harness does not grade the rest of the run against a crater.
            run(failures, "prime TNT with flint and steel", server, () -> {
                // The hazard guard refuses TNT within sixteen blocks of the owner whoever asked, so this
                // case lights it where somebody who wanted it lit would: with the owner standing clear.
                // The refusal beside them is proved in CobbiePermissionTest.
                CobbieTestSupport.teleportOwner(server, 32, 0, 0);
                BlockPos bomb = base.offset(8, -1, 0);
                server.computeOnServer(s -> {
                    CobbieTestSupport.command(s, "setblock " + bomb.getX() + " " + bomb.getY() + " "
                            + bomb.getZ() + " minecraft:tnt");
                    CobbieTestSupport.cobbie(s).orElseThrow()
                            .addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
                    return null;
                });
                String refused = use(server, "flint_and_steel", bomb, "");
                if (refused != null) throw new AssertionError("refused outright: " + refused);
                CobbieTestSupport.awaitPlanSettled(server, 400);
                int primed = server.computeOnServer(s -> {
                    List<PrimedTnt> found = all(s, PrimedTnt.class);
                    found.forEach(PrimedTnt::discard);
                    return found.size();
                });
                if (primed < 1) throw new AssertionError("the TNT never lit: " + lastReport(server));
            });

            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " of 9 tail abilities did not work: "
                        + String.join(" | ", failures));
            }
            System.out.println("[gametest] tail ok: tilled dirt, milked a cow, named a pig, tamed a wolf,"
                    + " played a disc, sheared a full hive, placed water, hatched a spawn egg, primed TNT");
        }
    }

    /** One case: its failure is collected with the item and block it was about, never thrown early. */
    private static void run(List<String> failures, String what, TestServerContext server, Case body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(what + " — " + t.getMessage());
        }
    }

    private interface Case {
        void run() throws Throwable;
    }

    private static String use(TestServerContext server, String item, BlockPos at, String mob) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().offerPlan(
                "use my " + item, List.of(new ActionIntent(ActionVocabulary.USE, item,
                        at.getX(), at.getY(), at.getZ(), mob))));
    }

    private static <T extends net.minecraft.world.entity.Entity> List<T> all(
            net.minecraft.server.MinecraftServer server, Class<T> kind) {
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (cobbie == null) return List.of();
        return cobbie.level().getEntitiesOfClass(kind, cobbie.getBoundingBox().inflate(24));
    }

    private static String lastReport(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::lastPlan).map(plan -> plan.report()).orElse("no plan"));
    }
}
