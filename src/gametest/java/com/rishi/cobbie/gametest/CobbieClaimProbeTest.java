package com.rishi.cobbie.gametest;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.brain.LlmClient;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * A probe, not a test: why an ask the model plainly understood produced no action.
 *
 * <p>The harness caught a repeatable shape — *"use my worst pickaxe"* answered with "Equipping my worst pickaxe —
 * the worn one — now." and **no action at all**, in two runs. The request is thrown away before the resolver is
 * consulted, so the resolver never even gets the chance to fail informatively.
 *
 * <p>Two readings want different fixes, and this probe is what tells them apart:
 *
 * <ol>
 *   <li>the reply declared {@code doing:true} with no actions — then the 0.39.0 repair should have fired and did
 *       not, which is a regression in the repair path;</li>
 *   <li>the reply declared nothing — then the honesty check is treating an undeclared reply as a claim.</li>
 * </ol>
 *
 * <p>The request count is the tell: one model call means no repair ran, two means it ran and did not produce an
 * action. Prints, never asserts. Run with {@code ./gradlew runClientGameTest -PcobbieTests=ClaimProbe}.
 */
public final class CobbieClaimProbeTest implements FabricClientGameTest {

    private static final String ASK = "cobbie use my worst pickaxe";
    private static final String NOTHING = "cobbie what is your name";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieEntity cobbie = CobbieTestSupport.spawn(context, server);
            String state = server.computeOnServer(s -> CobbieBrain.lastState() + " — "
                    + CobbieBrain.lastStateDetail());
            if (!state.toLowerCase(java.util.Locale.ROOT).contains("connected")) {
                System.out.println("[claim] probe skipped: no live model (" + state + ")");
                return;
            }
            CobbieTestSupport.permit(server, "building", true);
            CobbieTestSupport.permit(server, "crafting", true);
            CobbieTestSupport.command(server, "cobbie stay");
            UUID owner = cobbie.getOwner();
            server.computeOnServer(s -> {
                CobbieEntity body = CobbieTestSupport.cobbie(s).orElseThrow();
                ItemStack worn = new ItemStack(Items.IRON_PICKAXE, 1);
                worn.setDamageValue(worn.getMaxDamage() - 4);
                body.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
                body.addItem(worn);
                return null;
            });

            probe(context, server, owner, ASK);
            // A control: an ask that is honestly conversational must not start a repair, and must not be silent.
            probe(context, server, owner, NOTHING);
            System.out.println("[claim] done");
        }
    }

    private static void probe(ClientGameTestContext context, TestServerContext server, UUID owner, String ask) {
        long plansBefore = server.computeOnServer(CobbieClaimProbeTest::planId);
        int requestsBefore = LlmClient.requestsSent();
        context.runOnClient(mc -> mc.getConnection().sendChat(ask));
        // Give it time to answer, and to run its repair if it takes one.
        try {
            server.waitFor(s -> planId(s) != plansBefore, 600);
        } catch (Throwable ignored) {
            // no plan is exactly what this probe may be looking for
        }
        CobbieTestSupport.elapsed(server, 60);
        int requests = LlmClient.requestsSent() - requestsBefore;
        String replies = server.computeOnServer(s -> {
            StringBuilder sb = new StringBuilder();
            for (CobbieMemory.Message message : CobbieMemory.history(owner, 6)) {
                if (!"assistant".equals(message.role()) || message.content().isBlank()) continue;
                if (sb.length() > 0) sb.append(" | ");
                String content = message.content().replace('\n', ' ');
                sb.append(content.length() > 160 ? content.substring(0, 160) + "…" : content);
            }
            return sb.toString();
        });
        String plan = server.computeOnServer(s -> {
            CobbieEntity body = CobbieTestSupport.cobbie(s).orElseThrow();
            var last = body.lastPlan();
            return last == null ? "no plan" : "#" + last.id() + " " + last.request() + " -> " + last.report();
        });
        System.out.println("[claim] ask \"" + ask + "\": model calls " + requests
                + " (" + (requests > 1 ? "a repair ran" : "NO repair call") + ")");
        System.out.println("[claim]   replies: " + replies);
        System.out.println("[claim]   plan:    " + plan);
    }

    private static long planId(net.minecraft.server.MinecraftServer server) {
        return CobbieTestSupport.cobbie(server).map(c -> c.latestPlanId()).orElse(-1L);
    }
}
