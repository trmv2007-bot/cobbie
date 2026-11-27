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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What his swing costs, measured against the owner's own swing with the same item.
 *
 * <p>The rule this file exists for is the one 0.83.0 wrote down for placement: the game decides, the
 * code does not guess. Combat was the other place the guess lived — a mob's {@code ATTACK_DAMAGE} of
 * 3.0 rather than a player's 1.0, a damage number applied at full strength on a tick timer of the
 * code's own, and a melee goal that hurt on a flat twenty ticks whatever was in his hand.
 *
 * <p>The oracle is the owner himself. He is teleported to the same footing, given the same item, and
 * his hit is a real {@code Player.attack} at full attack strength; the damage the zombie loses is read
 * off the world. Nothing of the companion's combat path is on that number. His own fight is measured
 * the same way, tick by tick, so what is compared is one zombie's health against another's.
 *
 * <p>The pacing is measured, not asserted from a constant: the game's own cooldown for the item
 * ({@code Player.getCurrentItemAttackStrengthDelay}) against the ticks between the hits that actually
 * landed on his zombie, read from the health drops. A swing absorbed by the target's hurt-immunity
 * window leaves no drop, so the interval reported is the interval a landed hit really had.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=CombatOracle}.
 */
public final class CobbieCombatOracleTest implements FabricClientGameTest {

    /** What one kill looked like from outside: when the health fell, by how much, and how many hits it took. */
    private record Kill(List<Integer> hitTicks, List<Float> drops, float startHealth) {
        int hits() {
            return hitTicks.size();
        }

        float firstDrop() {
            return drops.isEmpty() ? 0.0f : drops.get(0);
        }

        /** Ticks between landed hits, in order. Gaps with no drop are the target's own immunity, not a miss. */
        List<Integer> gaps() {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < hitTicks.size(); i++) gaps.add(hitTicks.get(i) - hitTicks.get(i - 1));
            return gaps;
        }

        String describe() {
            return hits() + " hit(s) of " + drops + " over gaps " + gaps();
        }
    }

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
            CobbieTestSupport.command(server, "time set midnight");
            // The owner must survive being next to a zombie while he measures; resistance does not
            // change what the zombie takes, only what the owner does.
            CobbieTestSupport.command(server, "effect give @p minecraft:resistance 600 4 true");
            // Nothing may make him break off mid-measurement: the retreat is a different test's job.
            CobbieTestSupport.command(server, "cobbie permit combat on");

            oneWeapon(server, Items.IRON_SWORD, "an iron sword");
            oneWeapon(server, Items.IRON_AXE, "an iron axe");
            oneWeapon(server, Items.AIR, "a bare hand");
        }
    }

    /** One item, one owner kill and one companion kill with it, then the two numbers side by side. */
    private static void oneWeapon(TestServerContext server, Item weapon, String what) {
        // He must not defend the owner's zombie: with combat off during the oracle phase the number
        // read off that zombie is the owner's and nobody else's. Without this the companion charges
        // the zombie the owner just hit, and the "oracle" quietly becomes his own damage again.
        CobbieTestSupport.permit(server, "combat", false);
        Kill player = measure(server, true, weapon);
        // The cooldown is read here, not in the tick the item was handed over: equipment modifiers
        // reach the attribute map on the next tick, and a read before that answers 5 ticks for
        // everything, which is exactly the kind of number that looks plausible and means nothing.
        float playerCooldown = server.computeOnServer(s ->
                CobbieTestSupport.owner(s).getCurrentItemAttackStrengthDelay());
        CobbieTestSupport.permit(server, "combat", true);
        clearZombies(server);
        Kill cobbie = measure(server, false, weapon);

        if (player.hits() == 0) {
            throw new AssertionError(what + ": the owner's own attack damaged nothing, so there is no"
                    + " number to compare against — the oracle itself is broken");
        }
        if (cobbie.hits() == 0) {
            throw new AssertionError(what + ": he landed no hit at all (" + cobbie.describe() + "). "
                    + CobbieTestSupport.lastReport(server));
        }
        float difference = Math.abs(cobbie.firstDrop() - player.firstDrop());
        if (difference > 0.001f) {
            throw new AssertionError(what + ": a hit from the owner costs " + player.firstDrop()
                    + " health and a hit from him costs " + cobbie.firstDrop() + " (" + cobbie.describe()
                    + ", owner: " + player.describe() + "). The game is not the one deciding his damage.");
        }
        if (cobbie.hits() != player.hits()) {
            throw new AssertionError(what + ": the owner needed " + player.hits() + " hit(s) and he needed "
                    + cobbie.hits() + " (" + cobbie.describe() + ", owner: " + player.describe() + ")");
        }
        // The game's cooldown is a floor, not the whole story: a target's own hurt-immunity window can
        // stretch the gap further (a bare hand's five-tick cooldown lands every ten ticks because the
        // zombie cannot be hurt twice inside ten). So the floor is asserted here and the actual cadence
        // is compared against the owner's own landed cadence below.
        for (Integer gap : cobbie.gaps()) {
            if (gap < playerCooldown - 1.5f) {
                throw new AssertionError(what + ": the game's cooldown for this item is " + playerCooldown
                        + " ticks, and his landed hits were only " + gap + " apart (" + cobbie.describe() + ")");
            }
        }
        List<Integer> playerGaps = player.gaps();
        List<Integer> hisGaps = cobbie.gaps();
        for (int i = 0; i < Math.min(playerGaps.size(), hisGaps.size()); i++) {
            if (Math.abs(playerGaps.get(i) - hisGaps.get(i)) > 1) {
                throw new AssertionError(what + ": the owner's landed hits were " + playerGaps
                        + " ticks apart and his were " + hisGaps + " (owner: " + player.describe()
                        + ", he: " + cobbie.describe() + ")");
            }
        }
        System.out.println("[combat] " + what + ": owner " + player.describe() + " / cooldown "
                + playerCooldown + " ticks; he " + cobbie.describe()
                + " at attack strength on the same cooldown");
    }

    /** His kill of an identical zombie with an identical item, sampled once per tick off the world. */

    /**
     * Kill a zombie with one of them and report every health drop with the tick it happened on.
     *
     * <p>The sample is taken inside the wait predicate, which the harness runs once per server tick,
     * so a drop is placed on the tick it was observed — a tick at most after the blow. That is why the
     * comparison allows one tick either side of the game's cooldown.
     */
    private static Kill measure(TestServerContext server, boolean byOwner, Item weapon) {
        // A corpse from the last case stands in exactly this spot: a dead zombie is still an entity,
        // and picking it up would report "one hit, zero damage" for a fight that never happened.
        clearZombies(server);
        BlockPos spot = server.computeOnServer(s -> {
            ServerPlayer owner = CobbieTestSupport.owner(s);
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos anchor = owner.blockPosition();
            if (byOwner) {
                owner.teleportTo(s.overworld(), anchor.getX() + 0.5, anchor.getY(),
                        anchor.getZ() + 0.5, java.util.Set.of(), 0.0f, 0.0f, true);
                owner.setItemInHand(InteractionHand.MAIN_HAND,
                        weapon == Items.AIR ? ItemStack.EMPTY : new ItemStack(weapon));
                owner.resetAttackStrengthTicker();
                // Cobbie out of the way, so a defensive goal of his cannot take the oracle's zombie.
                cobbie.teleportTo(s.overworld(), anchor.getX() - 12.5, anchor.getY(),
                        anchor.getZ() + 0.5, java.util.Set.of(), 0.0f, 0.0f, true);
                cobbie.getNavigation().stop();
                return anchor.offset(0, 0, 3);
            }
            cobbie.teleportTo(s.overworld(), anchor.getX() + 0.5, anchor.getY(),
                    anchor.getZ() + 0.5, java.util.Set.of(), 0.0f, 0.0f, true);
            cobbie.getNavigation().stop();
            cobbie.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            cobbie.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            if (weapon != Items.AIR) cobbie.addItem(new ItemStack(weapon));
            return anchor.offset(0, 0, 3);
        });

        UUID zombie = server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "summon minecraft:zombie " + (spot.getX() + 0.5) + " "
                    + spot.getY() + " " + (spot.getZ() + 0.5)
                    + " {NoAI:1b}");
            return s.overworld().getEntitiesOfClass(LivingEntity.class,
                            new net.minecraft.world.phys.AABB(spot).inflate(6),
                            e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie && e.isAlive())
                    .stream().map(Entity::getUUID).findFirst()
                    .orElseThrow(() -> new AssertionError("the zombie never appeared at " + spot));
        });

        if (!byOwner) {
            String refusal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return cobbie.offerPlan("kill the zombie", List.of(new ActionIntent(
                        ActionVocabulary.ATTACK, "minecraft:zombie", spot.getX(), spot.getY(), spot.getZ(), "")));
            });
            if (refusal != null) throw new AssertionError("the attack was refused before it started: " + refusal);
        }

        List<Integer> ticks = new ArrayList<>();
        List<Float> drops = new ArrayList<>();
        float[] last = {20.0f};
        last[0] = server.computeOnServer(s -> healthOf(s, zombie));
        float start = last[0];
        server.waitFor(s -> {
            Entity target = ((ServerLevel) s.overworld()).getEntity(zombie);
            if (byOwner) {
                ServerPlayer owner = CobbieTestSupport.owner(s);
                if (target != null && target.isAlive() && owner.getAttackStrengthScale(0.5f) >= 1.0f) {
                    owner.attack(target);
                }
            }
            float now = target == null || !target.isAlive() ? 0.0f : healthOf(s, zombie);
            if (now < last[0]) {
                ticks.add(s.getTickCount());
                drops.add(last[0] - now);
            }
            last[0] = now;
            return now <= 0.0f;
        }, 2400);
        return new Kill(ticks, drops, start);
    }

    private static float healthOf(net.minecraft.server.MinecraftServer server, UUID id) {
        Entity found = ((ServerLevel) server.overworld()).getEntity(id);
        return found instanceof LivingEntity living ? living.getHealth() : 0.0f;
    }

    private static void clearZombies(TestServerContext server) {
        server.computeOnServer(s -> {
            for (Entity e : s.overworld().getEntitiesOfClass(Entity.class,
                    new net.minecraft.world.phys.AABB(CobbieTestSupport.owner(s).blockPosition()).inflate(64),
                    e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) {
                e.discard();
            }
            return null;
        });
    }
}
