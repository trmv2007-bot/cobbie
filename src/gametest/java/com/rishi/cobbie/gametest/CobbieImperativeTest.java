package com.rishi.cobbie.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "He does what we say": short typed commands must produce a real action, not a sentence promising
 * one. In the session that prompted this, "wave bruh" answered "Waving hello!" and nothing happened,
 * and "come here" answered "Coming right over!" without moving.
 *
 * <p>Survival world, real chat channel, no model in the loop for these verbs.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieImperativeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.SURVIVAL)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);

            // ---- wave ----
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().interrupt();
                return null;
            });
            context.runOnClient(mc -> mc.getConnection().sendChat("cobbie wave bruh"));
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).map(c -> c.isWaving()).orElse(false), 100);
            } catch (Throwable t) {
                throw new AssertionError("'wave bruh' did not make him wave", t);
            }

            // ---- come here ----
            CobbieTestSupport.command(server, "cobbie stay");
            context.waitTicks(10);
            CobbieTestSupport.teleportOwner(server, 10, 0, 10);
            context.waitTicks(10);
            double before = server.computeOnServer(s -> CobbieTestSupport.owner(s)
                    .distanceTo(CobbieTestSupport.cobbie(s).orElseThrow()));

            context.runOnClient(mc -> mc.getConnection().sendChat("cobbie come here"));
            try {
                server.waitFor(s -> CobbieTestSupport.owner(s).distanceTo(
                        CobbieTestSupport.cobbie(s).orElseThrow()) < before - 2.0, 600);
            } catch (Throwable t) {
                throw new AssertionError("'come here' did not move him: " + before + " -> "
                        + server.computeOnServer(s -> CobbieTestSupport.owner(s)
                        .distanceTo(CobbieTestSupport.cobbie(s).orElseThrow())), t);
            }

            // ---- drop your stone ----
            server.computeOnServer(s -> {
                CobbieTestSupport.cobbie(s).orElseThrow().addItem(new ItemStack(Items.STONE, 4));
                return null;
            });
            context.runOnClient(mc -> mc.getConnection().sendChat("cobbie drop your stone"));
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s).orElseThrow().countItem("minecraft:stone") == 0, 300);
            } catch (Throwable t) {
                throw new AssertionError("'drop your stone' left him holding "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow()
                        .countItem("minecraft:stone")), t);
            }

            // ---- look up (real network, real wiki) ----
            // The wiki is the internet: it can be slow. What must be deterministic is the plumbing —
            // the command is understood and a fresh answer comes back. Whether that answer is wiki
            // text or "I could not reach the wiki" is reported, not asserted.
            java.util.UUID ownerId = server.computeOnServer(s -> CobbieTestSupport.owner(s).getUUID());
            int repliesBefore = server.computeOnServer(s -> com.rishi.cobbie.memory.CobbieMemory
                    .history(ownerId, 40).stream().filter(m -> "assistant".equals(m.role())).toList().size());
            context.runOnClient(mc -> mc.getConnection().sendChat("cobbie look up obsidian"));
            try {
                server.waitFor(s -> com.rishi.cobbie.memory.CobbieMemory.history(ownerId, 40).stream()
                        .filter(m -> "assistant".equals(m.role())).toList().size() > repliesBefore, 600);
            } catch (Throwable t) {
                throw new AssertionError("'look up obsidian' produced no answer at all within 30s", t);
            }
            String wikiAnswer = server.computeOnServer(s -> com.rishi.cobbie.memory.CobbieMemory
                    .history(ownerId, 2).stream()
                    .filter(m -> "assistant".equals(m.role()))
                    .map(m -> m.content()).findFirst().orElse(""));

            // ---- what do you have ----
            String bag = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().describeInventory());
            System.out.println("[gametest] imperatives ok: waved, walked to the owner, dropped his stone; "
                    + "wiki said: " + wikiAnswer.substring(0, Math.min(90, wikiAnswer.length())));
        }
    }
}
