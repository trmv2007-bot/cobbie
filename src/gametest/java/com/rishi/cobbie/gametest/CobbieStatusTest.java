package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * "It is hard to tell what he is doing": the tag above his head must say it, and shift-right-click
 * must hand back the job and its steps.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieStatusTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);
            TestServerContext server = world.getServer();
            CobbieTestSupport.spawn(context, server);
            CobbieTestSupport.permit(server, "mining", true);

            // "Thinking" is honest while a model call is genuinely out, and the counter behind it is
            // process-wide: a class that ran earlier can still have a slow reply in flight when this
            // one starts. Establish the precondition instead of assuming it — and if the brain never
            // goes quiet, that is a stuck request and this fails naming it.
            CobbieTestSupport.awaitQuietBrain(server);

            String idle = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().statusWord());
            if (idle.startsWith("thinking")) {
                throw new AssertionError("He should not be thinking before anyone talks to him: " + idle);
            }

            // Start work and watch the tag change.
            BlockPos at = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition().offset(0, 0, 10));
            server.computeOnServer(s -> {
                var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
                cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
                CobbieTestSupport.command(s, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " minecraft:stone");
                cobbie.offerPlan("mine that stone", List.of(new ActionIntent(ActionVocabulary.MINE,
                        "minecraft:stone", at.getX(), at.getY(), at.getZ(), "")));
                return null;
            });

            try {
                server.waitFor(s -> {
                    String word = CobbieTestSupport.cobbie(s).map(c -> c.statusWord()).orElse("gone");
                    return word.equals("walking") || word.equals("mining") || word.equals("working");
                }, 200);
            } catch (Throwable t) {
                throw new AssertionError("The status tag never showed him working; it said "
                        + server.computeOnServer(s -> CobbieTestSupport.cobbie(s).map(c -> c.statusWord())
                        .orElse("gone")), t);
            }

            String busy = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().statusWord());

            // The nameplate carries it above his head.
            try {
                server.waitFor(s -> CobbieTestSupport.cobbie(s)
                        .map(c -> c.getCustomName() != null && c.getCustomName().getString().contains("§8·"))
                        .orElse(false), 100);
            } catch (Throwable t) {
                throw new AssertionError("The nameplate never picked up the status suffix; it reads "
                        + server.computeOnServer(s -> String.valueOf(
                        CobbieTestSupport.cobbie(s).map(c -> c.getCustomName()).orElse(null))), t);
            }

            // Shift-right-click's content: the job, its steps, and what he carries.
            String report = server.computeOnServer(s -> CobbieTestSupport.cobbie(s).orElseThrow().describeWork());
            if (!report.contains("Job:") || !report.contains("mine") || !report.contains("Carrying:")) {
                throw new AssertionError("The work report is missing pieces: " + report);
            }
            // Coordinates always; the step's state is one of the four, never asserted at a fixed value,
            // because which of them you catch depends on when in the step you look.
            boolean hasStepState = java.util.List.of("pending", "running", "done", "failed").stream()
                    .anyMatch(word -> report.contains(" - " + word));
            if (!report.contains(" @") || !hasStepState) {
                throw new AssertionError("The work report has no step coordinates or step state: " + report);
            }

            // And the gesture itself: shifting is what turns a right-click into that readout.
            var sneaking = CobbieTestSupport.rightClick(server, true);
            if (sneaking != net.minecraft.world.InteractionResult.SUCCESS) {
                throw new AssertionError("Shift-right-click on him returned " + sneaking + ", not SUCCESS");
            }
            var standing = CobbieTestSupport.rightClick(server, false);
            if (standing == net.minecraft.world.InteractionResult.SUCCESS) {
                throw new AssertionError("A plain right-click produced the work report too — the shift gate "
                        + "is not gating anything (" + standing + ")");
            }

            // 0.18.0: the gesture opens his panel on a client that can receive it. This test is about
            // the tag, so put the screen away before the screenshot; CobbiePanelTest covers the panel.
            context.computeOnClient(mc -> {
                mc.gui.setScreen(null);
                return null;
            });

            // A frame with the tag on screen, kept as the artefact for this claim.
            java.nio.file.Path shot = context.takeScreenshot("cobbie-status");
            System.out.println("[gametest] screenshot: " + shot);

            System.out.println("[gametest] status ok: idle=\"" + idle + "\" working=\"" + busy
                    + "\" shift=" + sneaking + " plain=" + standing
                    + " report=[" + report.replace("\n", " | ") + "]");
        }
    }
}
