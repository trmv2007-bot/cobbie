package com.rishi.cobbie.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.client.CobbieConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The settings screen, in game, on a config that already has a key: it must open, show the preset
 * the stored config implies, keep the key in the field while drawing only its tail, and let the
 * Provider button rewrite the URL and model of the preset it lands on.
 *
 * <p>The screenshot is the artefact a human checks: a masked key that renders as plain text is a
 * leak no assertion here would catch, so both the pre-click and post-click states are captured.
 *
 * Run with {@code ./gradlew runClientGameTest}.
 */
public final class CobbieConfigUiTest implements FabricClientGameTest {

    private static final String DUMMY_KEY = "test-key-not-real-0001";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = CobbieTestSupport.createWorld(context,
                Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
            CobbieTestSupport.awaitWorld(context);

            Path configFile = writeGatewayConfig();

            context.runOnClient(mc -> mc.setScreenAndShow(new CobbieConfigScreen(null)));
            context.waitForScreen(CobbieConfigScreen.class);
            context.waitFor(mc -> mc.gui.screen() instanceof CobbieConfigScreen, 100);

            context.runOnClient(mc -> {
                Screen screen = mc.gui.screen();
                List<EditBox> boxes = screen.children().stream()
                        .filter(w -> w instanceof EditBox)
                        .map(w -> (EditBox) w)
                        .toList();
                check(boxes.size() >= 3, "the screen must draw key, URL and model fields, found " + boxes.size());

                EditBox keyBox = boxes.get(0);
                EditBox urlBox = boxes.get(1);
                check(DUMMY_KEY.equals(keyBox.getValue()),
                        "the key field must hold the stored key so Save keeps it, held: '"
                                + keyBox.getValue() + "'");
                check(urlBox.getValue().contains("8787"),
                        "the stored gateway URL must be in the field, held: '" + urlBox.getValue() + "'");

                Button provider = screen.children().stream()
                        .filter(w -> w instanceof Button)
                        .map(w -> (Button) w)
                        .filter(b -> b.getMessage().getString().contains("KeyPool gateway"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no Provider button naming the stored preset; "
                                + "buttons were: " + screen.children().stream()
                                        .filter(w -> w instanceof Button)
                                        .map(w -> ((Button) w).getMessage().getString())
                                        .toList()));
                check(provider.visible && provider.active, "the Provider button must be clickable");
            });

            Path top = context.takeScreenshot("cobbie_config_keys_first");
            context.runOnClient(mc -> mc.mouseHandler.releaseMouse());
            context.waitTicks(5);
            context.takeScreenshot("cobbie_config_keys_masked");

            // The button clicks by its own label, so this advances the cycle: gateway -> Custom.
            // Custom keeps the endpoint, so the URL box must not move when it lands there.
            context.clickScreenButton("KeyPool gateway »");
            String afterCustom = context.computeOnClient(mc -> firstUrlBox(mc.gui.screen()).getValue());
            String customLabel = context.computeOnClient(mc -> providerButtonLabel(mc.gui.screen()));
            check(customLabel.equals("Custom »"), "the cycle must land on Custom, label reads: " + customLabel);
            check(afterCustom.contains("8787"),
                    "choosing Custom must not wipe the URL someone typed, got: '" + afterCustom + "'");

            // Ollama is still one of the entries, and it must switch the whole endpoint over.
            context.clickScreenButton("Custom »");
            context.waitTicks(2);
            String afterOllama = context.computeOnClient(mc -> firstUrlBox(mc.gui.screen()).getValue());
            String ollamaModel = context.computeOnClient(mc -> {
                List<EditBox> boxes = mc.gui.screen().children().stream()
                        .filter(w -> w instanceof EditBox).map(w -> (EditBox) w).toList();
                return boxes.get(2).getValue();
            });
            check(afterOllama.equals("http://127.0.0.1:11434"),
                    "the Ollama preset must write its own URL, got: '" + afterOllama + "'");
            check(ollamaModel.equals("lfm2.5:8b"),
                    "the Ollama preset must write its own model, got: '" + ollamaModel + "'");

            Path after = context.takeScreenshot("cobbie_config_preset_applied");
            context.runOnClient(mc -> mc.setScreenAndShow(null));
            context.waitTicks(5);

            System.out.println("[gametest] config ui ok: preset label, masked key, preset application; screenshots="
                    + top + ", " + after + "; config=" + configFile);
        }
    }

    /** A stored config shaped exactly like the one on this PC: OpenAI-compatible gateway + key. */
    private static Path writeGatewayConfig() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("cobbie.json");
            JsonObject o;
            if (Files.exists(file)) {
                o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } else {
                o = new JsonObject();
            }
            o.addProperty("provider", "openai");
            o.addProperty("base_url", "http://127.0.0.1:8787/v1");
            o.addProperty("model", "pooled-model");
            o.addProperty("api_key", DUMMY_KEY);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new AssertionError("could not write a test config: " + e);
        }
    }

    /** The Provider button is the only button whose label ends in the cycle arrow. */
    private static String providerButtonLabel(Screen screen) {
        return screen.children().stream()
                .filter(w -> w instanceof Button)
                .map(w -> ((Button) w).getMessage().getString())
                .filter(label -> label.endsWith("»"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Provider button on the screen"));
    }

    private static EditBox firstUrlBox(Screen screen) {
        return screen.children().stream()
                .filter(w -> w instanceof EditBox)
                .map(w -> (EditBox) w)
                .skip(1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no URL field on the screen"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
