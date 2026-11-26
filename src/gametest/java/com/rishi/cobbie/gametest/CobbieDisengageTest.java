package com.rishi.cobbie.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Badly hurt, with something hostile coming for him: he falls back instead of trading blows until he dies.
 *
 * <p>The same wound and the same zombie twice, and the only difference is the setting. With the instinct
 * switched off (<code>retreat_health_fraction: 0</code>) he stands there and loses health, which is the
 * control — without it "he was still alive at the end" proves nothing, because a companion who does nothing
 * is also alive at the end of a short window. With it on (0.5) he says he is falling back, ends up beside his
 * owner, and finishes with the health he started the retreat with. Between the two runs the config file is
 * rewritten and reloaded, so what the test proves is the setting and not a constant.
 *
 * <p>Run with {@code ./gradlew runClientGameTest -PcobbieTests=Disengage}.
 */
public final class CobbieDisengageTest implements FabricClientGameTest {

    /** Under half his bar: with the threshold at 0.5 the wound alone is enough to make him break off. */
    private static final float WOUNDED = 9.0f;
    /** Under the cautious default too, so the control phase loses health rather than standing in a truce. */
    private static final float CONTROL_WOUND = 6.0f;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.command(server, "cobbie stay");
            // Keep the owner out of the arithmetic: a zombie that kills the player ends the test.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "effect give @p minecraft:resistance 600 4 true");
                return null;
            });

            // The setting this test rewrites goes back to what it was however the run ends: a test that
            // leaves the owner's threshold at 0.5 has changed his installation, not just the world.
            double wasFraction = CobbieConfig.getRetreatHealthFraction();
            try {
                runPhases(server);
            } finally {
                setRetreatFraction(wasFraction);
            }
        }
    }

    private static void runPhases(TestServerContext server) {
        // ---- control: the instinct switched off ----
        setRetreatFraction(0.0);
        float controlStart = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHealth(CONTROL_WOUND);
            return cobbie.getHealth();
        });
        UUID controlZombie = engage(server);
        try {
            server.waitFor(s -> {
                keepEngaged(s, controlZombie);
                return CobbieTestSupport.cobbie(s)
                        .map(c -> c.getHealth() < CONTROL_WOUND || !c.isAlive()).orElse(false);
            }, 240);
        } catch (Throwable t) {
            throw new AssertionError("The control phase never took a hit, so the retreat has nothing to be"
                    + " compared against", t);
        }
        float controlHealth = health(server);
        double controlDistance = distanceTo(server, controlZombie);
        boolean alive = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(CobbieEntity::isAlive).orElse(false));
        remove(server, controlZombie);
        if (!alive) {
            throw new AssertionError("The control phase killed him at " + CONTROL_WOUND + " health, so there is"
                    + " nothing left to watch retreat");
        }
        if (controlHealth >= controlStart) {
            throw new AssertionError("He was engaged and lost no health (" + controlStart + " -> " + controlHealth
                    + "), so the control proves nothing");
        }

        // ---- the instinct: rewritten setting, reloaded, same wound, same zombie ----
        setRetreatFraction(0.5);
        double ownerDistance = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            cobbie.setHealth(WOUNDED);
            // The owner steps away, so "toward the owner" is a walk and not a nudge.
            net.minecraft.server.level.ServerPlayer owner = CobbieTestSupport.owner(s);
            owner.teleportTo(s.overworld(), owner.getX() + 10, owner.getY(), owner.getZ(),
                    Set.of(), owner.getYRot(), owner.getXRot(), true);
            return Math.sqrt(cobbie.distanceToSqr(owner));
        });
        UUID zombie = engage(server);
        try {
            server.waitFor(s -> {
                keepEngaged(s, zombie);
                return CobbieTestSupport.cobbie(s)
                        .map(c -> c.saidRecently("falling back")).orElse(false);
            }, 240);
        } catch (Throwable t) {
            String lines = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                    .map(c -> c.recentLines().toString()).orElse("no companion"));
            throw new AssertionError("He never said he was falling back; he said " + lines, t);
        }
        server.waitFor(s -> CobbieTestSupport.cobbie(s)
                .map(c -> !c.isRetreating()).orElse(false), 400);

        float afterHealth = health(server);
        String state = server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
                .map(c -> c.getHealth() + "/" + c.getMaxHealth() + " at " + c.blockPosition().toShortString()
                        + " retreating=" + c.isRetreating() + " target=" + c.getTarget())
                .orElse("MISSING"));
        double ownerNow = server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            return Math.sqrt(cobbie.distanceToSqr(CobbieTestSupport.owner(s)));
        });
        double zombieNow = distanceTo(server, zombie);

        if (state.equals("MISSING") || afterHealth <= 0) {
            throw new AssertionError("He died instead of falling back: the run ended with no companion");
        }
        if (afterHealth <= controlHealth) {
            throw new AssertionError("Falling back left him no better off than standing still: " + afterHealth
                    + " with the instinct against " + controlHealth + " without it (" + state + ")");
        }
        if (afterHealth < CONTROL_WOUND) {
            throw new AssertionError("He lost health on the way out of a fight he was leaving: " + state);
        }
        if (ownerNow > Math.max(4.0, CobbieConfig.getFollowDistance()) + 3.0) {
            throw new AssertionError("He did not fall back to his owner: he ended " + ownerNow
                    + " blocks away from them, having started " + ownerDistance + " (" + state + ")");
        }
        // The zombie chases him, so "distance from the zombie" is not the claim. The claim is that a live
        // hostile was still in the room when he read as safe, and that he was not the one trading blows.
        if (zombieNow < 0) {
            throw new AssertionError("The zombie is gone from the world, so this window proves nothing (" + state
                    + ")");
        }
        System.out.println("[gametest] disengage ok: control " + controlStart + " -> " + controlHealth
                + " standing still " + oneDecimal(controlDistance) + " blocks from the zombie; instinct "
                + WOUNDED + " -> " + afterHealth + ", from " + oneDecimal(ownerDistance) + " to "
                + oneDecimal(ownerNow) + " blocks from the owner and " + oneDecimal(zombieNow)
                + " from the zombie (" + state + ")");
    }

    /**
     * Rewrite one setting through the file the mod actually reads, then reload.
     *
     * <p>Not a setter on the config class: the claim being tested is that the threshold is a setting, so the
     * path under test is the config file itself.
     */
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

    /** Put a hostile next to him and make it come for him, which is what "engaging" means. */
    private static UUID engage(TestServerContext server) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            BlockPos at = cobbie.blockPosition().offset(4, 0, 4);
            CobbieTestSupport.command(s, "summon minecraft:zombie " + (at.getX() + 0.5) + " " + at.getY()
                    + " " + (at.getZ() + 0.5));
            Zombie zombie = s.overworld().getEntitiesOfClass(Zombie.class, new AABB(at).inflate(2.0)).stream()
                    .filter(Zombie::isAlive)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("The zombie never arrived at " + at.toShortString()));
            zombie.setTarget(cobbie);
            return zombie.getUUID();
        });
    }

    /**
     * Hold the zombie on him.
     *
     * <p>A zombie's own targeting goal prefers players, and the owner is standing right there, so the
     * engagement the test set up is replaced a few ticks later and the "fight" becomes a zombie walking at a
     * resident player. This re-points it at the companion every tick: the walking, the swinging and the damage
     * are the zombie's, which is what makes it an engagement rather than a staged hit.
     */
    private static void keepEngaged(net.minecraft.server.MinecraftServer server, UUID id) {
        Entity entity = server.overworld().getEntity(id);
        CobbieEntity cobbie = CobbieTestSupport.cobbie(server).orElse(null);
        if (!(entity instanceof Zombie zombie) || cobbie == null || !zombie.isAlive()) return;
        if (zombie.getTarget() != cobbie) zombie.setTarget(cobbie);
    }

    private static double distanceTo(TestServerContext server, UUID id) {
        return server.computeOnServer(s -> {
            CobbieEntity cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
            Entity zombie = s.overworld().getEntity(id);
            return zombie == null ? -1.0 : Math.sqrt(cobbie.distanceToSqr(zombie));
        });
    }

    private static float health(TestServerContext server) {
        return server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(CobbieEntity::getHealth).orElse(-1.0f));
    }

    private static void remove(TestServerContext server, UUID id) {
        server.computeOnServer(s -> {
            Entity e = s.overworld().getEntity(id);
            if (e != null) e.discard();
            return null;
        });
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
