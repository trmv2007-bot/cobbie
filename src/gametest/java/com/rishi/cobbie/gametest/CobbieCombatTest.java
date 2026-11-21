package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.util.List;

/**
 * Combat: a zombie that attacks the owner must get hurt for it, and a passive animal standing next
 * to him must never be touched. Survival + normal difficulty, otherwise mobs ignore the player.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieCombatTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.NORMAL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "combat", true);

            // Keep the owner from dying mid-test; low enough that hits still register as damage.
            server.computeOnServer(s -> {
                CobbieTestSupport.command(s, "effect give @p minecraft:resistance 120 2 true");
                CobbieTestSupport.command(s, "effect give @p minecraft:regeneration 120 2 true");
                return null;
            });

            BlockPos zombieAt = server.computeOnServer(s -> {
                BlockPos at = CobbieTestSupport.owner(s).blockPosition().offset(0, 0, 6);
                CobbieTestSupport.command(s, "summon minecraft:zombie "
                        + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5));
                return at;
            });

            try {
                server.waitFor(s -> s.overworld().getEntitiesOfClass(Zombie.class,
                                new net.minecraft.world.phys.AABB(zombieAt).inflate(24)).stream()
                                .noneMatch(z -> z.isAlive() && z.getHealth() >= z.getMaxHealth()), 600);
            } catch (Throwable t) {
                float health = server.computeOnServer(s -> s.overworld()
                        .getEntitiesOfClass(Zombie.class, new net.minecraft.world.phys.AABB(zombieAt).inflate(24))
                        .stream().map(LivingEntity::getHealth).findFirst().orElse(-1.0f));
                throw new AssertionError("Zombie was never hit; its health is still " + health, t);
            }

            float zombieHealth = server.computeOnServer(s -> s.overworld()
                    .getEntitiesOfClass(Zombie.class, new net.minecraft.world.phys.AABB(zombieAt).inflate(24))
                    .stream().map(LivingEntity::getHealth).findFirst().orElse(0.0f));

            // A passive animal must never be auto-targeted.
            BlockPos sheepAt = server.computeOnServer(s -> {
                BlockPos at = CobbieTestSupport.owner(s).blockPosition().offset(0, 0, -6);
                CobbieTestSupport.command(s, "summon minecraft:sheep "
                        + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5));
                return at;
            });
            context.waitTicks(100);

            List<Sheep> sheep = server.computeOnServer(s -> s.overworld()
                    .getEntitiesOfClass(Sheep.class, new net.minecraft.world.phys.AABB(sheepAt).inflate(16)));
            if (sheep.isEmpty()) {
                throw new AssertionError("Test setup failed: the sheep is gone, so peaceful behaviour is unproven");
            }
            float sheepHealth = server.computeOnServer(s -> s.overworld()
                    .getEntitiesOfClass(Sheep.class, new net.minecraft.world.phys.AABB(sheepAt).inflate(16))
                    .get(0).getHealth());
            if (sheepHealth < sheep.get(0).getMaxHealth()) {
                throw new AssertionError("Cobbie auto-targeted a passive animal: sheep at " + sheepHealth);
            }

            boolean ownerAlive = server.computeOnServer(s -> CobbieTestSupport.owner(s).isAlive());
            if (!ownerAlive) {
                throw new AssertionError("Owner died during the combat test");
            }

            System.out.println("[gametest] combat ok: zombie health " + zombieHealth
                    + ", sheep untouched at " + sheepHealth + ", owner alive");
        }
    }
}
