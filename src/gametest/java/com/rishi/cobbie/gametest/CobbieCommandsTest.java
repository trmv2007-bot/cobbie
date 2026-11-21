package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;

/**
 * The model-free command path: {@code cobbie stay} must actually stop him, and {@code cobbie follow}
 * must actually start him again. Asserts movement, not chat text.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieCommandsTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // Wandering off on his own would look like a failure to stay put.
            CobbieTestSupport.command(server, "cobbie autonomy off");
            CobbieTestSupport.command(server, "cobbie stay");
            context.waitTicks(20);

            Vec3 held = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().position());

            CobbieTestSupport.teleportOwner(server, 8, 0, 8);
            // Following only repaths inside loaded chunks; measuring before the world catches up has
            // made this test lie about him standing still.
            try {
                server.waitFor(s -> s.overworld().isLoaded(CobbieTestSupport.owner(s).blockPosition()), 200);
            } catch (Throwable t) {
                throw new AssertionError("Owner's chunk never loaded after the teleport", t);
            }
            context.waitTicks(100);

            double drift = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().position().distanceTo(held));
            if (drift > 1.5) {
                throw new AssertionError("cobbie stay did not hold him: he moved " + drift + " blocks");
            }

            CobbieTestSupport.command(server, "cobbie follow");
            double start = server.computeOnServer(s -> {
                var owner = CobbieTestSupport.owner(s);
                return owner.distanceTo(CobbieTestSupport.cobbie(s).orElseThrow());
            });

            try {
                server.waitFor(s -> {
                    var cobbie = CobbieTestSupport.cobbie(s);
                    return cobbie.isPresent() && CobbieTestSupport.owner(s).distanceTo(cobbie.get()) < start - 2.0;
                }, 400);
            } catch (Throwable t) {
                double now = server.computeOnServer(s -> CobbieTestSupport.owner(s)
                        .distanceTo(CobbieTestSupport.cobbie(s).orElseThrow()));
                throw new AssertionError("cobbie follow did not move him: start=" + start + " now=" + now, t);
            }

            double end = server.computeOnServer(s -> CobbieTestSupport.owner(s)
                    .distanceTo(CobbieTestSupport.cobbie(s).orElseThrow()));
            System.out.println("[gametest] commands ok: stay drift=" + String.format("%.2f", drift)
                    + " follow distance " + String.format("%.2f", start) + " -> " + String.format("%.2f", end));
        }
    }
}
