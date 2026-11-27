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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Which hostile he goes for, and the three things he must never go for.
 *
 * <p>The rule under test, in the order it is applied: one that is coming for the owner outranks one
 * that is coming for him; then the nearer one; then the harder hitter by the game's own attack-damage
 * figure; then the more wounded, so a fight ends instead of spreading thin. A hostile he cannot see
 * is only chosen when there is nothing visible to choose, and an owner who names a target is walked
 * to — the swing itself refuses through a wall in every case.
 *
 * <p>Every case is set up so the two hostiles differ on exactly one axis, so a pass cannot come from
 * the wrong half of the rule: the same pair of zombies at two distances, at one distance with
 * different health, and at two distances where only the farther one is coming for the owner. The
 * negatives (never the owner, never a tamed pet) are measured rather than asserted in prose: the
 * owner's health and the wolf's are read off the world after the fight that was meant for the zombie.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Threat}.
 */
public final class CobbieThreatTest implements FabricClientGameTest {

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

            nearestWins(server);
            ownerThreatOutranksOwn(server);
            woundedWinsTheTie(server);
            visibleBeatsHidden(server);
            neverTheOwner(server);
            neverAPet(server);
        }
    }

    // ---- the rule ----

    /** Two identical zombies, one nearer: the nearer one is the one whose next blow lands soonest. */
    private static void nearestWins(TestServerContext server) {
        BlockPos cobbieAt = reset(server);
        UUID near = summonZombie(server, cobbieAt.offset(0, 0, 3));
        UUID far = summonZombie(server, cobbieAt.offset(0, 0, 7));
        UUID picked = attackAndPicked(server, "zombie");
        if (!near.equals(picked)) {
            throw new AssertionError("two zombies at 3 and 7 blocks: he went for "
                    + name(server, picked) + " instead of the nearer one at 3 blocks");
        }
        System.out.println("[threat] 3 vs 7 blocks: picked the one at 3 (" + name(server, near) + ")");
        clean(server, near, far);
    }

    /**
     * The owner's attacker outranks his own, even from further away, and even though the nearer one is
     * coming for him: the owner is who he is there for.
     */
    private static void ownerThreatOutranksOwn(TestServerContext server) {
        BlockPos cobbieAt = reset(server);
        UUID own = summonZombie(server, cobbieAt.offset(0, 0, 2));
        UUID owner = summonZombie(server, cobbieAt.offset(0, 0, 9));
        server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            ((Zombie) entity(s, own)).setTarget(cobbie);
            ((Zombie) entity(s, owner)).setTarget(CobbieTestSupport.owner(s));
            return null;
        });
        // No attack is asked for: this is the defensive goal's own choice.
        UUID picked;
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getTarget() != null).orElse(false), 200);
            picked = UUID.fromString(server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getTarget().getUUID().toString()).orElse("00000000-0000-0000-0000-000000000000")));
        } catch (Throwable t) {
            throw new AssertionError("The defensive goal never chose a target while one zombie was coming"
                    + " for him at 2 blocks and one for his owner at 9", t);
        }
        if (!owner.equals(picked)) {
            throw new AssertionError("with a zombie on him at 2 blocks and one on his owner at 9, he chose "
                    + name(server, picked) + " — the owner's attacker must outrank his own");
        }
        System.out.println("[threat] owner's attacker at 9 blocks beat his own at 2");
        clean(server, own, owner);
    }

    /** The same distance and the same kind: the wounded one, so the fight ends instead of spreading. */
    private static void woundedWinsTheTie(TestServerContext server) {
        BlockPos cobbieAt = reset(server);
        UUID healthy = summonZombie(server, cobbieAt.offset(0, 0, 3));
        UUID wounded = summonZombie(server, cobbieAt.offset(0, 0, -3));
        server.computeOnServer(s -> {
            ((Zombie) entity(s, wounded)).setHealth(4.0f);
            return null;
        });
        UUID picked = attackAndPicked(server, "zombie");
        if (!wounded.equals(picked)) {
            throw new AssertionError("two zombies at the same distance, one at 4 health and one at 20:"
                    + " he went for " + name(server, picked) + " instead of the wounded one");
        }
        System.out.println("[threat] equal distance: picked the one at 4 health");
        clean(server, healthy, wounded);
    }

    /** Between a hidden hostile and a visible one, the visible one is the threat. */
    private static void visibleBeatsHidden(TestServerContext server) {
        BlockPos cobbieAt = reset(server);
        UUID hidden = summonZombie(server, cobbieAt.offset(0, 0, 2));
        // On the other side of him, so the wall is between him and exactly one of them. The first
        // version put both on the same line and the wall hid both, which read as "he ignored the
        // wall" when the truth was "there was nothing visible to choose".
        UUID visible = summonZombie(server, cobbieAt.offset(0, 0, -6));
        // Three courses of stone in the middle: one course is under a standing eye line and would let
        // him see straight over it, which is exactly the test that would pass for the wrong reason.
        for (int y = 0; y < 3; y++) {
            CobbieTestSupport.stoneAt(server, cobbieAt.offset(0, y, 1));
        }
        boolean hiddenVisible = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            return cobbie.hasLineOfSight(entity(s, hidden));
        });
        if (hiddenVisible) {
            throw new AssertionError("the wall does not block sight in this world, so this case cannot be"
                    + " measured");
        }
        UUID picked = attackAndPicked(server, "zombie");
        if (!visible.equals(picked)) {
            String seen = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                StringBuilder sb = new StringBuilder();
                for (UUID id : new UUID[] {hidden, visible}) {
                    Entity e = entity(s, id);
                    sb.append(e == null ? "gone" : e.getName().getString() + " at "
                            + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(cobbie.distanceToSqr(e)))
                            + " blocks, sight " + cobbie.hasLineOfSight(e));
                    sb.append("; ");
                }
                return sb.toString();
            });
            throw new AssertionError("with a zombie behind a wall at 2 blocks and one in the open at 6, he"
                    + " chose " + name(server, picked) + " — a wall is not a threat (" + seen + ")");
        }
        System.out.println("[threat] chose the visible zombie at 6 over the hidden one at 2");
        for (int y = 0; y < 3; y++) {
            CobbieTestSupport.command(server, "setblock " + cobbieAt.offset(0, y, 1).getX() + " "
                    + cobbieAt.offset(0, y, 1).getY() + " " + cobbieAt.offset(0, y, 1).getZ()
                    + " minecraft:air");
        }
        clean(server, hidden, visible);
    }

    // ---- the negatives ----

    /** Never the owner, even with player combat switched on: he is not something to be fought. */
    private static void neverTheOwner(TestServerContext server) {
        reset(server);
        CobbieTestSupport.permit(server, "players", true);
        try {
            float before = server.computeOnServer(s -> CobbieTestSupport.owner(s).getHealth());
            String refusal = server.computeOnServer(s -> {
                CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                return cobbie.offerPlan("kill the player", List.of(new ActionIntent(
                        ActionVocabulary.ATTACK, "minecraft:player", 0, 0, 0, "")));
            });
            if (refusal == null) {
                CobbieTestSupport.awaitPlanSettled(server, 200);
                refusal = CobbieTestSupport.lastReport(server);
                if (!refusal.contains("nothing I am allowed to fight")) {
                    throw new AssertionError("he accepted a fight with his owner (player combat is on)"
                            + " and reported: " + refusal);
                }
            }
            CobbieTestSupport.elapsed(server, 40);
            float after = server.computeOnServer(s -> CobbieTestSupport.owner(s).getHealth());
            if (after < before) {
                throw new AssertionError("the owner lost " + (before - after) + " health to his own companion");
            }
            System.out.println("[threat] an attack on players, with player combat on, was refused: " + refusal);
        } finally {
            CobbieTestSupport.permit(server, "players", false);
        }
    }

    /** Never a tamed pet: the owner's wolf stands beside the zombie the whole time and keeps its health. */
    private static void neverAPet(TestServerContext server) {
        BlockPos cobbieAt = reset(server);
        UUID zombie = summonZombie(server, cobbieAt.offset(0, 0, 4));
        UUID wolf = server.computeOnServer(s -> {
            ServerPlayer owner = CobbieTestSupport.owner(s);
            BlockPos at = cobbieAt.offset(1, 0, 4);
            CobbieTestSupport.command(s, "summon minecraft:wolf " + (at.getX() + 0.5) + " " + at.getY()
                    + " " + (at.getZ() + 0.5));
            Wolf found = s.overworld().getEntitiesOfClass(Wolf.class, new AABB(at).inflate(3)).stream()
                    .findFirst().orElseThrow(() -> new AssertionError("the wolf never appeared"));
            found.setTame(true, true);
            found.setOwner(owner);
            found.setOrderedToSit(true);
            return found.getUUID();
        });
        if (!server.computeOnServer(s -> ((Wolf) entity(s, wolf)).getOwner() instanceof ServerPlayer)) {
            throw new AssertionError("the wolf is not tamed to the owner, so 'never a pet' cannot be measured");
        }
        attackAndPicked(server, "zombie");
        try {
            server.waitFor(s -> {
                Entity z = entity(s, zombie);
                return z == null || !z.isAlive();
            }, 600);
        } catch (Throwable t) {
            throw new AssertionError("the zombie was never killed, so the pet's safety is unproven", t);
        }
        float wolfHealth = server.computeOnServer(s -> ((Wolf) entity(s, wolf)).getHealth());
        float wolfMax = server.computeOnServer(s -> ((Wolf) entity(s, wolf)).getMaxHealth());
        if (wolfHealth < wolfMax) {
            throw new AssertionError("the owner's tamed wolf lost health (" + wolfHealth + " of " + wolfMax
                    + ") while he fought a zombie beside it");
        }
        System.out.println("[threat] the tamed wolf kept " + wolfHealth + " of " + wolfMax);
        clean(server, zombie, wolf);
    }

    // ---- harness ----

    /** Clear the arena, put him two blocks behind the owner, and hand back where he stands. */
    private static BlockPos reset(TestServerContext server) {
        BlockPos anchor = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.cancelTask("the next case");
            cobbie.interrupt();
            for (Entity e : s.overworld().getEntitiesOfClass(Entity.class,
                    new AABB(CobbieTestSupport.owner(s).blockPosition()).inflate(40),
                    e -> e instanceof Zombie || e instanceof Wolf)) {
                e.discard();
            }
            ServerPlayer owner = CobbieTestSupport.owner(s);
            cobbie.setHealth(cobbie.getMaxHealth());
            BlockPos at = owner.blockPosition().offset(-2, 0, 0);
            cobbie.teleportTo(s.overworld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                    java.util.Set.of(), 0.0f, 0.0f, true);
            cobbie.getNavigation().stop();
            cobbie.setTarget(null);
            return at;
        });
        CobbieTestSupport.elapsed(server, 10);
        return anchor;
    }

    private static UUID summonZombie(TestServerContext server, BlockPos at) {
        return server.computeOnServer(s -> {
            CobbieTestSupport.command(s, "summon minecraft:zombie " + (at.getX() + 0.5) + " " + at.getY()
                    + " " + (at.getZ() + 0.5) + " {NoAI:1b}");
            return s.overworld().getEntitiesOfClass(Zombie.class, new AABB(at).inflate(2)).stream()
                    .findFirst().map(Entity::getUUID)
                    .orElseThrow(() -> new AssertionError("no zombie appeared at " + at));
        });
    }

    /** Ask for a fight and report which hostile the plan itself chose. */
    private static UUID attackAndPicked(TestServerContext server, String hint) {
        String refusal = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .offerPlan("attack the " + hint, List.of(new ActionIntent(
                        ActionVocabulary.ATTACK, "minecraft:" + hint, 0, 0, 0, ""))));
        if (refusal != null) throw new AssertionError("the attack was refused: " + refusal);
        // The choice is made on the step's first tick, not when it is queued: reading it in the same
        // call as offerPlan answers "no target yet" for a plan that is about to work.
        try {
            server.waitFor(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.getTarget() != null).orElse(false), 200);
        } catch (Throwable t) {
            throw new AssertionError("the plan never chose a target; it reports "
                    + CobbieTestSupport.lastReport(server), t);
        }
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                .getTarget().getUUID());
    }

    private static Entity entity(net.minecraft.server.MinecraftServer server, UUID id) {
        return ((ServerLevel) server.overworld()).getEntity(id);
    }

    private static String name(TestServerContext server, UUID id) {
        return server.computeOnServer(s -> {
            Entity e = entity(s, id);
            return e == null ? "nothing" : e.getName().getString() + " at " + e.blockPosition();
        });
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
}
