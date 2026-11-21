package com.rishi.cobbie.gametest;

import com.rishi.cobbie.client.CobbieConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;

import java.nio.file.Path;

/**
 * The settings screen: it must open without throwing, render its rows, and be closable back to the
 * game. The screenshot is the artefact a human checks for layout and labels — a screen that builds
 * but draws nothing still "works" as far as code is concerned.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieConfigUiTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);

            context.runOnClient(mc -> mc.setScreenAndShow(new CobbieConfigScreen(null)));
            context.waitForScreen(CobbieConfigScreen.class);
            context.waitFor(mc -> mc.gui.screen() instanceof CobbieConfigScreen, 100);

            Path top = context.takeScreenshot("cobbie_config_top");
            context.runOnClient(mc -> mc.mouseHandler.releaseMouse());
            context.waitTicks(5);
            context.takeScreenshot("cobbie_config_labelled");

            context.runOnClient(mc -> mc.setScreenAndShow(null));
            context.waitTicks(5);

            System.out.println("[gametest] config ui ok: screen opened and closed, screenshot=" + top);
        }
    }
}
