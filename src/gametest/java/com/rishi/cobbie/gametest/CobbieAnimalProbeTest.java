package com.rishi.cobbie.gametest;

import com.rishi.cobbie.actor.FakeActor;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A probe, not a test: what feeding an animal through the actor actually does.
 *
 * <p>Breeding is the one animal capability with real preconditions — two adults, the right food, and a
 * cooldown after each calf — and the vanilla path runs through {@code mobInteract}, which the actor
 * already reaches for shearing. What is not obvious is whether {@code setInLove} from a fake player
 * leaves the two animals able to find each other, or whether the breeding "cause" pointing at a
 * player who is not in the world breaks the pairing. Five lines settle it.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=AnimalProbe}. Prints, never asserts.
 */
public final class CobbieAnimalProbeTest implements FabricClientGameTest {

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
                var at = cobbie.blockPosition();
                Cow one = new Cow(EntityTypes.COW, level);
                one.setPos(at.getX() + 1.5, at.getY(), at.getZ());
                Cow two = new Cow(EntityTypes.COW, level);
                two.setPos(at.getX() + 2.5, at.getY(), at.getZ());
                level.addFreshEntity(one);
                level.addFreshEntity(two);

                FakeActor.Outcome fedOne = FakeActor.useOnEntity(cobbie, one, new ItemStack(Items.WHEAT, 2));
                FakeActor.Outcome fedTwo = FakeActor.useOnEntity(cobbie, two, new ItemStack(Items.WHEAT, 1));
                System.out.println("[probe] feed: first=" + (fedOne == null ? "null" : fedOne.result())
                        + " second=" + (fedTwo == null ? "null" : fedTwo.result())
                        + " oneInLove=" + one.isInLove() + " twoInLove=" + two.isInLove()
                        + " canMate=" + (one.isInLove() && two.isInLove() ? one.canMate(two) : "n/a"));
                System.out.println("[probe] loveCause first=" + describe(one) + " two=" + describe(two));
                System.out.println("[probe] cooldown: one.canFallInLove=" + one.canFallInLove());
                return null;
            });

            // Give them a few seconds to find each other and produce a calf.
            CobbieTestSupport.elapsed(server, 240);
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();
                long calves = level.getEntitiesOfClass(Cow.class,
                        cobbie.getBoundingBox().inflate(16), cow -> cow.isBaby()).size();
                long cows = level.getEntitiesOfClass(Cow.class, cobbie.getBoundingBox().inflate(16)).size();
                System.out.println("[probe] after ~12s: cows=" + cows + " calves=" + calves);
                return null;
            });

            // And what taming looks like through the actor: a wolf with bones.
            server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                var level = s.overworld();
                var wolf = new net.minecraft.world.entity.animal.wolf.Wolf(EntityTypes.WOLF, level);
                if (wolf != null) {
                    wolf.setPos(cobbie.getX() + 1.0, cobbie.getY(), cobbie.getZ() + 1.0);
                    level.addFreshEntity(wolf);
                    FakeActor.useOnEntity(cobbie, wolf,
                            new ItemStack(Items.BONE, 24));
                    System.out.println("[probe] wolf tamed=" + (wolf instanceof net.minecraft.world.entity.TamableAnimal t
                            && t.isTame()) + " owner=" + ((net.minecraft.world.entity.TamableAnimal) wolf).getOwner());
                }
                return null;
            });
            System.out.println("[probe] done");
        }
    }

    private static String describe(Entity e) {
        if (e instanceof Cow cow) {
            var cause = cow.getLoveCause();
            return cause == null ? "none" : cause.getClass().getSimpleName() + " [" + cause.getName().getString() + "]";
        }
        return "?";
    }
}
