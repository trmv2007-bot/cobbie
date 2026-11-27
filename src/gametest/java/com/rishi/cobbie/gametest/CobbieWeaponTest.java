package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Which weapon he raises, and whether a shield he carries actually stops a blow.
 *
 * <p>Both claims are the game's, not a table of ours. The weapon is chosen by the attack-damage modifier
 * the item itself carries for the main hand — read in the test from the same component data, by
 * separate code — so the case where a pickaxe wins is a real case: he must take the pickaxe when the
 * game rates it higher, which is what proves the sword preference is a tie-break and not a bias.
 *
 * <p>The shield is measured the only way a block can be: the same zombie, the same standing spot, one
 * fight with a shield in his off hand and one without, and the health each blow cost him read off the
 * world. A blocked blow and an unblocked one are then two numbers, not two adjectives.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Weapon}.
 */
public final class CobbieWeaponTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "combat", true);
            // Midnight, and pinned: a summoned zombie burns in daylight, and a fire tick is a health
            // drop that is not his swing — the one number this measurement cannot have inside it.
            CobbieTestSupport.command(server, "gamerule advanceTime false");
            CobbieTestSupport.command(server, "time set midnight");
            CobbieTestSupport.command(server, "effect give @p minecraft:resistance 1200 4 true");
            CobbieTestSupport.command(server, "effect give @p minecraft:regeneration 1200 2 true");

            double wasFraction = CobbieConfig.getRetreatHealthFraction();
            try {
                // The shield comparison needs several blows landing on him; a retreat in the middle of
                // them would be a different behaviour under test, and 0.82.0's own class covers it.
                setRetreatFraction(0.0);
                weaponCase(server, Items.IRON_SWORD, Items.DIAMOND_PICKAXE);
                weaponCase(server, Items.GOLDEN_SWORD, Items.NETHERITE_PICKAXE);
                shieldCase(server);
            } finally {
                setRetreatFraction(wasFraction);
            }
        }
    }

    /**
     * A sword and a pickaxe in the bag, an empty hand, and one fight: the item he ends up holding is the
     * one the game's own damage figure names, whichever of the two that is.
     */
    private static void weaponCase(TestServerContext server, Item sword, Item pickaxe) {
        BlockPos cobbieAt = reset(server);
        Item expected = better(sword, pickaxe) ? sword : pickaxe;
        Item other = expected == sword ? pickaxe : sword;

        UUID zombie = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.addItem(new ItemStack(sword, 1));
            cobbie.addItem(new ItemStack(pickaxe, 1));
            BlockPos at = cobbieAt.offset(0, 0, 4);
            CobbieTestSupport.command(s, "summon minecraft:zombie " + (at.getX() + 0.5) + " " + at.getY()
                    + " " + (at.getZ() + 0.5) + " {NoAI:1b}");
            return s.overworld().getEntitiesOfClass(Zombie.class, new AABB(at).inflate(2)).stream()
                    .findFirst().map(Entity::getUUID).orElseThrow();
        });
        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan("attack the zombie", List.of(new ActionIntent(
                        ActionVocabulary.ATTACK, "minecraft:zombie", 0, 0, 0, ""))));
        if (refusal != null) throw new AssertionError("the attack was refused: " + refusal);

        try {
            server.waitFor(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                Entity target = entity(s, zombie);
                return !cobbie.getMainHandItem().isEmpty() || target == null || !target.isAlive();
            }, 300);
        } catch (Throwable t) {
            throw new AssertionError("he never raised a weapon; it reports " + CobbieTestSupport.lastReport(server), t);
        }
        String held = server.computeOnServer(s -> idOf(CobbieTestSupport.cobbie(s).orElseThrow().getMainHandItem()));
        String wanted = idOf(new ItemStack(expected));
        if (!wanted.equals(held)) {
            throw new AssertionError("carrying " + idOf(new ItemStack(sword)) + " " + figureText(sword)
                    + " and " + idOf(new ItemStack(pickaxe)) + " " + figureText(pickaxe)
                    + ", he fought with " + held + " — the game rates " + wanted + " the better weapon");
        }
        server.waitFor(s -> {
            Entity target = entity(s, zombie);
            return target == null || !target.isAlive();
        }, 600);
        int kept = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .countItem(idOf(new ItemStack(other))));
        if (kept < 1) {
            throw new AssertionError("the weapon he did not choose (" + idOf(new ItemStack(other))
                    + ") is gone from his bag; a choice must not cost him the other tool");
        }
        System.out.println("[weapon] " + idOf(new ItemStack(sword)) + " " + figureText(sword) + " vs "
                + idOf(new ItemStack(pickaxe)) + " " + figureText(pickaxe) + ": fought with " + held);
        clean(server, zombie);
    }

    /**
     * One blow, twice: with the shield he carries and without it.
     *
     * <p>The blow is the game's own mob attack — {@code Zombie.doHurtTarget}, the same call the zombie's
     * melee goal makes — so what is measured is not a number in the code but what
     * {@code LivingEntity.applyItemBlocking} decides to subtract. The zombie stands in front of him and
     * he faces it, because the game only blocks what arrives inside the shield's angle.
     *
     * <p>Both halves are needed: without the control, "he took no damage" is also what a zombie that
     * never swung looks like.
     */
    private static void shieldCase(TestServerContext server) {
        Blow bare = blow(server, false);
        Blow shielded = blow(server, true);
        if (bare.blocking()) {
            throw new AssertionError("the control blow found him blocking without a shield — the measurement"
                    + " is not measuring the shield");
        }
        if (!shielded.blocking()) {
            throw new AssertionError("with a shield in his off hand and a zombie in front of him, the game"
                    + " still read him as not blocking, so nothing was raised (" + shielded + ")");
        }
        if (shielded.damage() >= bare.damage()) {
            throw new AssertionError("the same blow cost " + bare.damage() + " health without a shield and "
                    + shielded.damage() + " with one raised (" + shielded + ") — the shield is not blocking");
        }
        System.out.println("[shield] the same zombie blow cost " + bare.damage() + " health without a shield"
                + " and " + shielded.damage() + " with one raised, blocking " + shielded.blocking());
    }

    /** What one blow from a zombie in front of him cost, and whether the game had him blocking. */
    private record Blow(float damage, boolean blocking) {
        @Override
        public String toString() {
            return damage + " health, blocking " + blocking;
        }
    }

    private static Blow blow(TestServerContext server, boolean withShield) {
        BlockPos cobbieAt = reset(server);
        UUID zombie = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            if (withShield) cobbie.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            cobbie.setHealth(cobbie.getMaxHealth());
            BlockPos at = cobbieAt.offset(0, 0, 2);
            CobbieTestSupport.command(s, "summon minecraft:zombie " + (at.getX() + 0.5) + " " + at.getY()
                    + " " + (at.getZ() + 0.5) + " {NoAI:1b}");
            Zombie found = s.overworld().getEntitiesOfClass(Zombie.class, new AABB(at).inflate(2)).stream()
                    .filter(Entity::isAlive)
                    .findFirst().orElseThrow(() -> new AssertionError("the zombie never appeared"));
            // Engaging, so the shield has a reason to come up; and facing him, so the block angle is
            // the one a player standing there would have.
            found.setTarget(cobbie);
            found.getLookControl().setLookAt(cobbie);
            return found.getUUID();
        });
        // The shield takes the game's five-tick block delay to count as raised, so the blow comes after
        // it — a blow inside those five ticks is a blow the game does not block, and would say nothing
        // about the shield.
        CobbieTestSupport.elapsed(server, 12);
        Blow blow = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            Entity found = entity(s, zombie);
            if (!(found instanceof Zombie attacker)) {
                throw new AssertionError("the zombie vanished before it could swing");
            }
            attacker.getLookControl().setLookAt(cobbie);
            cobbie.getLookControl().setLookAt(attacker);
            boolean blocking = cobbie.isBlocking();
            float before = cobbie.getHealth();
            attacker.doHurtTarget(s.overworld(), cobbie);
            float after = cobbie.getHealth();
            return new Blow(before - after, blocking);
        });
        clean(server, zombie);
        return blow;
    }

    // ---- the game's own figure, read the same way for both sides of the comparison ----

    private static double[] figure(ItemStack stack) {
        double[] damage = {0.0};
        double[] speed = {0.0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) return;
            if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) damage[0] += modifier.amount();
            else if (attribute.value() == Attributes.ATTACK_SPEED.value()) speed[0] += modifier.amount();
        });
        return new double[] {damage[0], speed[0]};
    }

    private static String figureText(Item item) {
        double[] figure = figure(new ItemStack(item));
        return "(damage " + figure[0] + ", speed " + figure[1] + ")";
    }

    /** The stated order: the game's damage, then its speed, then a sword before an axe before a pickaxe. */
    private static boolean better(Item challenger, Item incumbent) {
        double[] a = figure(new ItemStack(challenger));
        double[] b = figure(new ItemStack(incumbent));
        if (a[0] != b[0]) return a[0] > b[0];
        if (a[1] != b[1]) return a[1] > b[1];
        return rung(challenger) < rung(incumbent);
    }

    private static int rung(Item item) {
        ItemStack stack = new ItemStack(item);
        if (stack.is(net.minecraft.tags.ItemTags.SWORDS)) return 0;
        if (stack.is(net.minecraft.tags.ItemTags.AXES)) return 1;
        if (stack.is(net.minecraft.tags.ItemTags.PICKAXES)) return 2;
        return 3;
    }

    private static String idOf(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ---- harness ----

    private static BlockPos reset(TestServerContext server) {
        BlockPos at = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.cancelTask("the next case");
            cobbie.interrupt();
            for (Entity e : s.overworld().getEntitiesOfClass(Entity.class,
                    new AABB(CobbieTestSupport.owner(s).blockPosition()).inflate(40), e -> e instanceof Zombie)) {
                e.discard();
            }
            cobbie.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            cobbie.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            for (int i = cobbie.getInventoryContainer().getContainerSize() - 1; i >= 0; i--) {
                cobbie.getInventoryContainer().setItem(i, ItemStack.EMPTY);
            }
            cobbie.setTarget(null);
            cobbie.setHealth(cobbie.getMaxHealth());
            BlockPos anchor = CobbieTestSupport.owner(s).blockPosition().offset(-2, 0, 0);
            cobbie.teleportTo(s.overworld(), anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5,
                    java.util.Set.of(), 0.0f, 0.0f, true);
            cobbie.getNavigation().stop();
            return anchor;
        });
        CobbieTestSupport.elapsed(server, 10);
        return at;
    }

    private static Entity entity(net.minecraft.server.MinecraftServer server, UUID id) {
        return ((ServerLevel) server.overworld()).getEntity(id);
    }

    private static void clean(TestServerContext server, UUID... ids) {
        server.computeOnServer(s -> {
            for (UUID id : ids) {
                Entity e = entity(s, id);
                if (e != null) e.discard();
            }
            CobbieTestSupport.cobbie(s).ifPresent(c -> {
                c.cancelTask("the case is over");
                c.interrupt();
                c.setTarget(null);
            });
            return null;
        });
        CobbieTestSupport.elapsed(server, 10);
    }

    /** The threshold is a setting, so it is changed through the file the mod reads, and put back. */
    private static void setRetreatFraction(double fraction) {
        CobbieConfig.save();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cobbie.json");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            root.addProperty("retreat_health_fraction", fraction);
            Files.writeString(file, root.toString());
        } catch (Exception e) {
            throw new AssertionError("Could not rewrite the retreat threshold in " + file, e);
        }
        CobbieConfig.reload();
    }
}
