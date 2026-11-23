package com.rishi.cobbie.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.brain.BrainPresets;
import com.rishi.cobbie.brain.ConnectionTest;
import com.rishi.cobbie.brain.LlmClient;
import com.rishi.cobbie.brain.SecretMask;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-game settings screen: everything in {@code config/cobbie.json} that changes behaviour, with
 * labels, scrolling, and a live connection test.
 *
 * <p>Brain settings are keys-first: the Provider button cycles the presets in {@link BrainPresets}
 * (picking one writes {@code provider}/{@code base_url}/{@code model}), the API key is typed into a
 * field that renders only its last four characters, and "Test Connection" sends one real completion
 * and reports which of the four things happened. Both the URL and the model stay editable, so a
 * server the presets have never heard of still works.
 *
 * <p>Opened with {@code /cobbie config} or the config keybind. Saving writes the file atomically and
 * calls {@link com.rishi.cobbie.config.CobbieConfig#reload()}, which reconfigures the brain with the
 * new endpoint immediately — no restart, no editing JSON by hand.
 */
public class CobbieConfigScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TITLE_KEY = "cobbie.config.title";

    private static final int ROW_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 44;
    private static final int LABEL_COLOR = 0xFFA0A0A0;
    private static final int HEADER_COLOR = 0xFFFFAA00;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    private int contentHeight = ROW_HEIGHT;
    private int scroll;
    private Path configFile;
    private JsonObject configJson;
    private String status = "§7Not tested yet.";
    private BrainPresets.Preset preset = BrainPresets.DEFAULT;

    public CobbieConfigScreen(Screen parent) {
        super(Component.translatable(TITLE_KEY));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        boxes.clear();
        configFile = findConfigFile();
        loadConfig();
        buildRows();
        super.init();

        for (Row row : rows) {
            if (row.widget != null) addRenderableWidget(row.widget);
        }

        int footerY = height - FOOTER_HEIGHT + 20;
        addRenderableWidget(Button.builder(Component.literal("§aSave"), b -> {
            saveConfig();
            onClose();
        }).bounds(width / 2 - 160, footerY, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§eTest Connection"), b -> testConnection())
                .bounds(width / 2 - 55, footerY, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§cCancel"), b -> onClose())
                .bounds(width / 2 + 65, footerY, 95, 20).build());

        applyScroll();
    }

    private Path findConfigFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("cobbie.json");
        } catch (Exception e) {
            return null;
        }
    }

    private void loadConfig() {
        if (configFile == null || !Files.exists(configFile)) {
            configJson = defaultConfig();
            return;
        }
        try {
            configJson = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        } catch (Exception e) {
            Cobbie.LOGGER.warn("Cobbie config unreadable, using defaults: {}", e.toString());
            configJson = defaultConfig();
        }
    }

    private JsonObject defaultConfig() {
        JsonObject o = new JsonObject();
        o.addProperty("provider", BrainPresets.DEFAULT_PROVIDER);
        o.addProperty("base_url", BrainPresets.DEFAULT_BASE_URL);
        o.addProperty("model", BrainPresets.DEFAULT_MODEL);
        o.addProperty("api_key", "");
        o.addProperty("temperature", 0.7);
        o.addProperty("max_tokens", 800);
        o.addProperty("connect_timeout", 5);
        o.addProperty("read_timeout", 30);
        o.addProperty("max_retries", 1);
        o.addProperty("personality", "warm, curious, a little playful; a good friend who is never scary");
        o.addProperty("autonomy_mode", "ASSISTIVE");
        o.addProperty("plan_step_limit", 6);
        o.addProperty("task_retry_limit", 1);
        o.addProperty("max_concurrent_requests", 2);
        o.addProperty("task_timeout_seconds", 180);
        o.addProperty("follow_distance", 4.0);
        o.addProperty("navigation_range", 24);
        o.addProperty("interaction_reach", 4.0);
        o.addProperty("exploration_radius", 48);
        o.addProperty("recall_distance", 0);
        o.addProperty("mining_allowed", false);
        o.addProperty("building_allowed", false);
        o.addProperty("combat_allowed", false);
        o.addProperty("player_combat_allowed", false);
        o.addProperty("exploration_allowed", true);
        o.addProperty("collection_allowed", true);
        o.addProperty("crafting_allowed", false);
        o.addProperty("chat_mode", "MENTION");
        o.addProperty("allowed_players", "");
        o.addProperty("chat_cooldown_ms", 900);
        o.addProperty("memory_enabled", true);
        o.addProperty("max_chat_history", 12);
        o.addProperty("persistent_memory", true);
        o.addProperty("inventory_size", 9);
        o.addProperty("only_allowlisted_players", false);
        o.addProperty("max_cobbies_per_player", 1);
        return o;
    }

    private void buildRows() {
        header("AI Provider (bring your own key)");
        providerField();
        secretField("api_key", "API Key", "paste your key — it stays on this PC");
        textField("base_url", "Base URL", "https://api.groq.com/openai/v1");
        textField("model", "Model", "llama-3.3-70b-versatile");
        numberField("temperature", "Temperature", 0.0, 2.0, 0.1);
        intField("max_tokens", "Max Tokens", 32, 8192, 100);
        intField("connect_timeout", "Connect Timeout (s)", 1, 120, 1);
        intField("read_timeout", "Read Timeout (s)", 5, 600, 10);
        intField("max_retries", "Max Retries", 0, 8, 1);

        header("Personality");
        textField("personality", "Personality", "warm, curious, a little playful");
        enumField("autonomy_mode", "Autonomy", new String[]{"OFF", "ASSISTIVE", "CREATIVE"});

        header("Behaviour");
        intField("plan_step_limit", "Plan Step Limit", 1, 24, 1);
        intField("task_retry_limit", "Task Retry Limit", 0, 8, 1);
        intField("max_concurrent_requests", "Max Concurrent Requests", 1, 16, 1);
        intField("task_timeout_seconds", "Task Timeout (s)", 15, 3600, 60);

        header("Movement");
        numberField("follow_distance", "Follow Distance", 1.0, 64.0, 0.5);
        intField("navigation_range", "Navigation Range", 4, 256, 8);
        numberField("interaction_reach", "Interaction Reach", 1.0, 16.0, 0.5);
        intField("exploration_radius", "Exploration Radius", 8, 256, 8);
        intField("recall_distance", "Recall When Stuck Beyond (0 = off)", 0, 4096, 16);

        header("Permissions");
        boolField("mining_allowed", "Mining");
        boolField("building_allowed", "Building");
        boolField("combat_allowed", "Combat");
        boolField("player_combat_allowed", "Player Combat (PvP)");
        boolField("exploration_allowed", "Exploration");
        boolField("collection_allowed", "Collection");
        boolField("crafting_allowed", "Crafting");

        header("Chat");
        enumField("chat_mode", "Chat Mode", new String[]{"MENTION", "OWNER_MENTION", "ALWAYS_LISTEN", "ALLOWLIST"});
        textField("allowed_players", "Allowed Players (comma separated)", "");
        intField("chat_cooldown_ms", "Reply Cooldown (ms)", 0, 10000, 100);

        header("Memory");
        boolField("memory_enabled", "Memory Enabled");
        intField("max_chat_history", "Chat History Kept", 0, 200, 4);
        boolField("persistent_memory", "Persistent Memory");

        header("Entity");
        intField("inventory_size", "Inventory Slots", 1, 54, 1);
        boolField("only_allowlisted_players", "Gated To Allowlist");
        intField("max_cobbies_per_player", "Max Cobbies Per Player", 1, 16, 1);
    }

    // ---- row builders ----

    private void header(String text) {
        rows.add(new Row(null, text, Kind.HEADER));
    }

    private void textField(String key, String label, String placeholder) {
        EditBox box = new EditBox(font, 0, 0, 200, 18, Component.literal(label));
        box.setHint(Component.literal(placeholder).withStyle(s -> s.withColor(0x808080)));
        String value = getString(key);
        if (value != null) box.setValue(value);
        box.setResponder(v -> configJson.addProperty(key, v));
        boxes.put(key, box);
        rows.add(new Row(box, label, Kind.FIELD));
    }

    /** The Provider button: cycles the presets and writes their provider, URL and model in. */
    private void providerField() {
        preset = BrainPresets.infer(getString("provider"), getString("base_url"));
        Button btn = Button.builder(providerLabel(), b -> {
            preset = BrainPresets.next(preset);
            applyPreset(preset);
            b.setMessage(providerLabel());
        }).bounds(0, 0, 200, 18).build();
        rows.add(new Row(btn, "Provider", Kind.FIELD));
    }

    private Component providerLabel() {
        // Plain text, styled — so the label a player clicks is exactly the label a test clicks.
        return Component.literal(preset.label() + " »").withStyle(ChatFormatting.AQUA);
    }

    /**
     * Write one preset into the fields. The URL and model stay editable afterwards, and the Custom
     * entry leaves both alone on purpose: it exists for servers the presets do not know.
     */
    private void applyPreset(BrainPresets.Preset p) {
        configJson.addProperty("provider", p.provider());
        EditBox base = boxes.get("base_url");
        EditBox model = boxes.get("model");
        if (!p.keepsExistingEndpoint()) {
            if (base != null) base.setValue(p.baseUrl());
            else configJson.addProperty("base_url", p.baseUrl());
            if (model != null) model.setValue(p.defaultModel());
            else configJson.addProperty("model", p.defaultModel());
        }
        EditBox key = boxes.get("api_key");
        if (key != null) {
            key.setHint(Component.literal(p.keyHint()).withStyle(s -> s.withColor(0x808080)));
        }
        status = "§7" + p.label() + ": " + (p.needsKey() ? p.keyHint() : "no key needed")
                + ". Press Test Connection.";
    }

    /**
     * A field that holds a secret and shows only its tail. The real value lives in the widget and
     * reaches {@code config/cobbie.json} on save; a screenshot of this screen shows bullets.
     */
    private void secretField(String key, String label, String placeholder) {
        EditBox box = new EditBox(font, 0, 0, 200, 18, Component.literal(label));
        box.setMaxLength(400);
        box.setHint(Component.literal(placeholder).withStyle(s -> s.withColor(0x808080)));
        box.addFormatter((text, offset) ->
                FormattedCharSequence.forward(SecretMask.display(text), Style.EMPTY));
        String value = getString(key);
        if (value != null) box.setValue(value);
        box.setResponder(v -> configJson.addProperty(key, v));
        boxes.put(key, box);
        rows.add(new Row(box, label, Kind.FIELD));
    }

    private void numberField(String key, String label, double min, double max, double step) {
        String current = getString(key) == null ? "0" : getString(key);
        Button btn = Button.builder(Component.literal("- " + current + " +"), b -> {
            double value = Math.min(max, Math.max(min, getDouble(key) + step));
            configJson.addProperty(key, round(value));
            b.setMessage(Component.literal("- " + round(value) + " +"));
        }).bounds(0, 0, 90, 18).build();
        rows.add(new Row(btn, label, Kind.FIELD));
    }

    private void intField(String key, String label, int min, int max, int step) {
        String current = getString(key) == null ? "0" : getString(key);
        Button btn = Button.builder(Component.literal("- " + current + " +"), b -> {
            int value = Math.min(max, Math.max(min, getInt(key) + step));
            configJson.addProperty(key, value);
            b.setMessage(Component.literal("- " + value + " +"));
        }).bounds(0, 0, 90, 18).build();
        rows.add(new Row(btn, label, Kind.FIELD));
    }

    private void boolField(String key, String label) {
        Button btn = Button.builder(Component.literal(label + ": " + (getBoolean(key) ? "§aON" : "§cOFF")), b -> {
            boolean next = !getBoolean(key);
            configJson.addProperty(key, next);
            b.setMessage(Component.literal(label + ": " + (next ? "§aON" : "§cOFF")));
        }).bounds(0, 0, 150, 18).build();
        rows.add(new Row(btn, "", Kind.FIELD));
    }

    private void enumField(String key, String label, String[] values) {
        String current = getString(key) == null ? values[0] : getString(key);
        Button btn = Button.builder(Component.literal(current), b -> {
            int idx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equalsIgnoreCase(getString(key))) {
                    idx = (i + 1) % values.length;
                    break;
                }
            }
            String next = values[idx];
            configJson.addProperty(key, next);
            b.setMessage(Component.literal(next));
        }).bounds(0, 0, 150, 18).build();
        rows.add(new Row(btn, label, Kind.FIELD));
    }

    // ---- layout and scrolling ----

    private void applyScroll() {
        int footerTop = height - FOOTER_HEIGHT;
        int y = 32 + scroll;
        for (Row row : rows) {
            int rowHeight = row.kind == Kind.HEADER ? 18 : ROW_HEIGHT;
            row.y = y;
            if (row.widget != null) {
                row.widget.setX(width / 2 + 10);
                row.widget.setY(y);
                boolean onScreen = y > 24 && y < footerTop - rowHeight;
                row.widget.visible = onScreen;
                row.widget.active = onScreen;
            }
            y += rowHeight;
        }
        contentHeight = y - scroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int footerTop = height - FOOTER_HEIGHT;
        int view = footerTop - 32;
        int maxScroll = Math.min(0, view - contentHeight + 8);
        scroll = Math.max(maxScroll, Math.min(0, scroll + (int) (scrollY * 18)));
        applyScroll();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        for (Row row : rows) {
            if (row.y < 24 || row.y > height - FOOTER_HEIGHT) continue;
            if (row.kind == Kind.HEADER) {
                graphics.text(font, row.label, width / 2 - 150, row.y, HEADER_COLOR);
            } else if (!row.label.isEmpty()) {
                graphics.text(font, row.label, width / 2 - 150, row.y + 5, LABEL_COLOR);
            }
        }

        graphics.text(font, "§7" + status, width / 2 - 150, height - FOOTER_HEIGHT + 4, 0xFFAAAAAA);

        if (contentHeight > height - 32 - FOOTER_HEIGHT) {
            graphics.text(font, "§8scroll", width - 52, height - FOOTER_HEIGHT + 4, 0xFF606060);
        }
    }

    // ---- save ----

    private String getStringOr(String key, String fallback) {
        String v = getString(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private int getIntOr(String key, int fallback) {
        return configJson.has(key) ? getInt(key) : fallback;
    }

    private double getDoubleOr(String key, double fallback) {
        return configJson.has(key) ? getDouble(key) : fallback;
    }

    private String getString(String key) {
        return configJson.has(key) && configJson.get(key).isJsonPrimitive()
                ? configJson.get(key).getAsString() : null;
    }

    private double getDouble(String key) {
        try {
            return configJson.has(key) ? configJson.get(key).getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int getInt(String key) {
        try {
            return configJson.has(key) ? configJson.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean getBoolean(String key) {
        try {
            return configJson.has(key) && configJson.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void saveConfig() {
        if (configFile == null) return;
        try {
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(configJson));
            try {
                Files.move(tmp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomic) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Cobbie.LOGGER.info("Cobbie config saved via GUI");
            com.rishi.cobbie.config.CobbieConfig.reload();
        } catch (Exception e) {
            Cobbie.LOGGER.error("Failed to save Cobbie config: {}", e.toString());
            status = "Save failed: " + e.getClass().getSimpleName();
        }
    }

    /**
     * One real completion against exactly what is on screen — unsaved edits included, so a key can
     * be tried before it is written to disk. Zero retries: a test that waits out three timeouts is
     * not a test the player will sit through.
     */
    private void testConnection() {
        status = "§7Testing…";
        String provider = getStringOr("provider", BrainPresets.DEFAULT_PROVIDER);
        String base = getStringOr("base_url", BrainPresets.DEFAULT_BASE_URL);
        String model = getStringOr("model", BrainPresets.DEFAULT_MODEL);
        String key = getStringOr("api_key", "");
        if (!provider.startsWith("ollama") && key.isBlank()) {
            status = "§c" + BrainPresets.infer(provider, base).label()
                    + ": no API key yet — paste one above.";
            return;
        }
        // Capped at a minute: a test button that sits for five minutes on a dead endpoint is not a
        // test, it is a hang. The saved read_timeout is untouched and still governs real replies.
        int testTimeout = Math.min(60, Math.max(5, getIntOr("read_timeout", 30)));
        LlmClient client = new LlmClient(base, model, key, provider,
                getDoubleOr("temperature", 0.7), getIntOr("max_tokens", 800),
                getIntOr("connect_timeout", 5), testTimeout, 0);
        String label = BrainPresets.infer(provider, base).label();
        ConnectionTest.run(client, label).whenComplete((report, thrown) -> {
            String line = thrown != null
                    ? "§cTest failed: " + thrown.getClass().getSimpleName()
                    : (report.healthy() ? "§a" : "§c") + report.line();
            minecraft.execute(() -> status = line);
        });
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private enum Kind { HEADER, FIELD }

    private static final class Row {
        final AbstractWidget widget;
        final String label;
        final Kind kind;
        int y;

        Row(AbstractWidget widget, String label, Kind kind) {
            this.widget = widget;
            this.label = label;
            this.kind = kind;
        }
    }
}
