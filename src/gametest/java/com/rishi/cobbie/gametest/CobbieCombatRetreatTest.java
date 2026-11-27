package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * A fight that turns bad must stop being a fight.
 *
 * <p>0.82.0 shipped the retreat: below {@code retreat_health_fraction} of his health, with a hostile
 * actually coming for him, he breaks off and falls back to the owner. That was proven on its own. What
 * this class pins is that combat agrees with it — a plan that was mid-swing when the wound landed must
 * not keep swinging, because a retreat he can be shot in the back of is not a retreat.
 *
 * <p>The wound is dealt directly (his health is set, not hit into him), so the moment it exists is
 * known to the tick. The zombie's health is then read at that moment and again sixty ticks later: the
 * claim is not "he said he was leaving", it is "nothing else was hurt after he said it".
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=CombatRetreat}.
 */
public final class CobbieCombatRetreatTest implements FabricClientGameTest {

    /** Under the shipped 0.35 of twenty health: the wound alone is what turns the fight. */
    private static final float WOUND = 6.0f;
    /** How long after the retreat starts nothing may be hit. Three seconds of a retreat he must survive. */
    private static final int QUIET_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            CobbieTestSupport.permit(server, "combat", true);
            CobbieTestSupport.command(server, "gamerule advanceTime false");
            CobbieTestSupport.command(server, "time set midnight");
            CobbieTestSupport.command(server, "effect give @p minecraft:resistance 1200 4 true");
            CobbieTestSupport.command(server, "effect give @p minecraft:regeneration 1200 2 true");
            double fraction = CobbieConfig.getRetreatHealthFraction();
            if (Math.abs(fraction - 0.35) > 0.001) {
                throw new AssertionError("the shipped threshold is 0.35 of his health and this world has "
                        + fraction + "; the wound below is chosen for 0.35");
            }

            // The owner steps away, so "fell back to you" is a walk and not a nudge.
            BlockPos cobbieAt = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setHealth(cobbie.getMaxHealth());
                cobbie.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD, 1));
                ServerPlayer owner = CobbieTestSupport.owner(s);
                // Far enough that falling back is a walk: 0.82.0 ends a retreat once he is beside the
                // owner, and an owner standing eight blocks away ends it in ten ticks — which is what
                // the first version of this test measured and mistook for him not moving at all.
                owner.teleportTo(s.overworld(), owner.getX() + 22, owner.getY(), owner.getZ(),
                        java.util.Set.of(), owner.getYRot(), owner.getXRot(), true);
                return cobbie.blockPosition();
            });
            UUID zombie = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                BlockPos at = cobbieAt.offset(0, 0, 3);
                CobbieTestSupport.command(s, "summon minecraft:zombie " + (at.getX() + 0.5) + " " + at.getY()
                        + " " + (at.getZ() + 0.5)
                        + " {Health:200f,Attributes:[{id:\"minecraft:max_health\",base:200}]}");
                Zombie found = s.overworld().getEntitiesOfClass(Zombie.class, new AABB(at).inflate(2)).stream()
                        .findFirst().orElseThrow(() -> new AssertionError("the zombie never appeared"));
                found.setTarget(cobbie);
                return found.getUUID();
            });
            String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                    .offerPlan("kill the zombie", List.of(new ActionIntent(
                            ActionVocabulary.ATTACK, "minecraft:zombie", 0, 0, 0, ""))));
            if (refusal != null) throw new AssertionError("the attack was refused: " + refusal);

            // The fight has to be real before the wound means anything: he must have hurt it already.
            float full = server.computeOnServer(s -> healthOf(s, zombie));
            try {
                server.waitFor(s -> healthOf(s, zombie) < full, 400);
            } catch (Throwable t) {
                throw new AssertionError("he never landed a blow, so there is no fight to break off", t);
            }

            float zombieAtWound = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.setHealth(WOUND);
                return healthOf(s, zombie);
            });
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.saidRecently("falling back") || c.isRetreating()).orElse(false), 100);
            } catch (Throwable t) {
                String state = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> "health " + c.getHealth() + ", retreating " + c.isRetreating()
                                + ", plan " + (c.lastPlan() == null ? "none" : c.lastPlan().status().toString()))
                        .orElse("no companion"));
                throw new AssertionError("he was wounded to " + WOUND + " mid-fight and never broke off (" + state
                        + ")", t);
            }

            double distanceAtWound = distanceTo(server, zombie);
            // The window is walked in ten-tick steps and stops the moment the retreat ends: 0.82.0 ends
            // it once he is beside the owner, and after that the zombie is free to close the distance
            // again. Reading the distance at a fixed end point would call a finished retreat "not
            // moving", which is exactly what the first version of this test did.
            double distanceAfter = distanceAtWound;
            double ownerAfter = ownerDistance(server);
            boolean stillRetreating = true;
            int measured = 0;
            for (int step = 0; step < QUIET_TICKS; step += 10) {
                measured += CobbieTestSupport.elapsed(server, 10);
                stillRetreating = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                        .map(CobbieEntity::isRetreating).orElse(false));
                distanceAfter = distanceTo(server, zombie);
                ownerAfter = ownerDistance(server);
                if (!stillRetreating) break;
            }
            float zombieAfter = server.computeOnServer(s -> healthOf(s, zombie));
            boolean planSettled = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.lastPlan() == null || c.lastPlan().isFinished()).orElse(false));
            String line = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(CobbieEntity::lastSpokenLine).orElse("nothing"));

            if (zombieAfter < zombieAtWound) {
                throw new AssertionError("the retreat started at " + WOUND + " health and he kept swinging: the"
                        + " zombie went from " + zombieAtWound + " to " + zombieAfter + " during the "
                        + measured + " ticks after he broke off");
            }
            // Either he was still walking away — the distance to the zombie must have grown — or the
            // retreat had already ended because he was back at his owner's side, which is the shipped
            // rule for when a retreat is over. Both are "he left the fight"; standing in it is neither.
            boolean walkedAway = distanceAfter > distanceAtWound;
            boolean reachedOwner = !stillRetreating
                    && ownerAfter <= CobbieConfig.getFollowDistance() + 1.0;
            if (!walkedAway && !reachedOwner) {
                throw new AssertionError("he broke off and neither fell back nor reached his owner: "
                        + distanceAtWound + " blocks from the zombie when it started, " + distanceAfter
                        + " after " + measured + " ticks, " + oneDecimal(ownerAfter) + " from the owner"
                        + " (retreating " + stillRetreating + ")");
            }
            if (!planSettled) {
                throw new AssertionError("the attack plan is still running " + measured + " ticks after the retreat"
                        + " began; a fight he has left must not be a plan he is still executing");
            }
            System.out.println("[retreat] wounded to " + WOUND + " mid-fight: zombie stayed at " + zombieAfter
                    + ", distance " + oneDecimal(distanceAtWound) + " -> " + oneDecimal(distanceAfter)
                    + " blocks over " + measured + " ticks (retreating " + stillRetreating + ", "
                    + oneDecimal(ownerAfter) + " from the owner), plan settled " + planSettled
                    + ", said \"" + line + "\"");
        }
    }

    private static float healthOf(net.minecraft.server.MinecraftServer server, UUID id) {
        Entity found = ((ServerLevel) server.overworld()).getEntity(id);
        return found instanceof Zombie zombie ? zombie.getHealth() : 0.0f;
    }

    private static double distanceTo(TestServerContext server, UUID id) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            Entity zombie = ((ServerLevel) s.overworld()).getEntity(id);
            return zombie == null ? -1.0 : Math.sqrt(cobbie.distanceToSqr(zombie));
        });
    }

    private static double ownerDistance(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            return Math.sqrt(cobbie.distanceToSqr(CobbieTestSupport.owner(s)));
        });
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
