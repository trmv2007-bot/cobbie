package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.skill.SkillExecutor;
import com.rishi.cobbie.skill.SkillRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The abilities added in 0.7.0: {@code light} must place a real torch from his bag, {@code give} must
 * move an item into the owner's inventory rather than deleting it, and the built-in skills must exist
 * and be runnable through the same executor as everything else.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieAbilitiesTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieEntity cobbie = CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "building", true);

            // ---- light ----
            server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                BlockPos spot = owner.blockPosition().offset(0, 0, 4);
                CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + (spot.getY() - 1) + " " + spot.getZ()
                        + " minecraft:stone");
                CobbieTestSupport.command(s, "setblock " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                        + " minecraft:air");
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.TORCH, 4));
                return null;
            });

            BlockPos spot = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition().offset(0, 0, 4));
            String lightRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("put a torch down over there",
                            List.of(new ActionIntent(ActionVocabulary.LIGHT, "minecraft:torch",
                                    spot.getX(), spot.getY(), spot.getZ(), ""))));
            if (lightRefusal != null) throw new AssertionError("light refused: " + lightRefusal);

            try {
                server.waitFor(s -> s.overworld().getBlockState(spot).is(Blocks.TORCH), 600);
            } catch (Throwable t) {
                throw new AssertionError("No torch appeared at " + spot + "; state="
                        + server.computeOnServer(s -> s.overworld().getBlockState(spot).toString()), t);
            }
            int torchesLeft = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:torch"));
            if (torchesLeft != 3) {
                throw new AssertionError("Placed a torch but his stack is " + torchesLeft + ", expected 3");
            }

            // ---- give ----
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.STONE, 3));
                return null;
            });
            int ownerBefore = server.computeOnServer(s -> CobbieTestSupport.owner(s).getInventory()
                    .countItem(Items.STONE));

            String giveRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("hand me a stone",
                            List.of(new ActionIntent(ActionVocabulary.GIVE, "minecraft:stone", 0, 0, 0, ""))));
            if (giveRefusal != null) throw new AssertionError("give refused: " + giveRefusal);

            try {
                server.waitFor(s -> CobbieTestSupport.owner(s).getInventory().countItem(Items.STONE) > ownerBefore, 300);
            } catch (Throwable t) {
                throw new AssertionError("The owner never received the stone; cobbie holds "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .countItem("minecraft:stone")), t);
            }
            int hisStone = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .countItem("minecraft:stone"));
            if (hisStone != 2) {
                throw new AssertionError("Item duplicated or lost: he had 3, gave one, now holds " + hisStone);
            }

            // ---- drop ----
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.DIAMOND, 3));
                return null;
            });

            // Deliberately un-namespaced: the model writes "diamond", not "minecraft:diamond", and a
            // namespaced-only match made him deny carrying things he was holding.
            String dropRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("drop all your diamonds",
                            List.of(new ActionIntent(ActionVocabulary.DROP, "diamond", 0, 0, 0, "all"))));
            if (dropRefusal != null) throw new AssertionError("drop refused: " + dropRefusal);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).orElseThrow().countItem("minecraft:diamond") == 0, 300);
            } catch (Throwable t) {
                throw new AssertionError("He never let go of the diamonds; he still holds "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .countItem("minecraft:diamond")), t);
            }

            int onGround = server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                return s.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                                new net.minecraft.world.phys.AABB(owner.blockPosition()).inflate(8))
                        .stream()
                        .filter(e -> e.getItem().is(Items.DIAMOND))
                        .mapToInt(e -> e.getItem().getCount())
                        .sum();
            });
            if (onGround != 3) {
                throw new AssertionError("Bag emptied but " + onGround + " diamonds are on the ground, expected 3");
            }

            // ---- equip ----
            server.computeOnServer(s -> {
                CobbieEntity body = CobbieTestSupport.cobbie(s).orElseThrow();
                body.addItem(new ItemStack(Items.IRON_HELMET, 1));
                body.addItem(new ItemStack(Items.IRON_SWORD, 1));
                return null;
            });

            String helmetRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("put on the helmet",
                            List.of(new ActionIntent(ActionVocabulary.EQUIP, "minecraft:iron_helmet", 0, 0, 0, ""))));
            if (helmetRefusal != null) throw new AssertionError("equip helmet refused: " + helmetRefusal);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.IRON_HELMET), 300);
            } catch (Throwable t) {
                throw new AssertionError("The helmet never reached his head; head slot holds "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).toString()), t);
            }

            String swordRefusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("hold the sword",
                            List.of(new ActionIntent(ActionVocabulary.EQUIP, "minecraft:iron_sword", 0, 0, 0, ""))));
            if (swordRefusal != null) throw new AssertionError("equip sword refused: " + swordRefusal);

            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .getMainHandItem().is(Items.IRON_SWORD), 300);
            } catch (Throwable t) {
                throw new AssertionError("The sword never reached his hand; holding "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .getMainHandItem().toString()), t);
            }

            // ---- built-in skills ----
            List<String> names = server.computeOnServer(s -> SkillRegistry.all().stream()
                    .map(skill -> skill.name()).toList());
            for (String expected : List.of("wave hello", "light the way", "gather some wood")) {
                String wanted = expected.replace(' ', '-');
                if (names.stream().noneMatch(n -> n.equalsIgnoreCase(wanted))) {
                    throw new AssertionError("Built-in skill \"" + expected + "\" is missing; registry has " + names);
                }
            }
            String started = server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().interrupt();
                SkillExecutor.Outcome outcome = SkillExecutor.run(CobbieTestSupport.cobbie(s).orElseThrow(), "wave hello");
                return outcome.started() + " :: " + outcome.message();
            });
            if (!started.startsWith("true")) {
                throw new AssertionError("Built-in skill did not run: " + started);
            }

            System.out.println("[gametest] abilities ok: torch at " + spot.toShortString()
                    + " (4->" + torchesLeft + "), gave 1 stone (" + hisStone + " left), dropped "
                    + onGround + " diamonds, helmet on head, sword in hand, skills=" + names);
        }
    }
}
