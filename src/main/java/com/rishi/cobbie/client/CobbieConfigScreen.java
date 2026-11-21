package com.rishi.cobbie.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.config.CobbieConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-game config screen for Cobbie. Opens with {@code /cobbie config}.
 * Reads/writes config/cobbie.json directly so changes persist across restarts.
 */
public class CobbieConfigScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TITLE_KEY = "cobbie.config.title";

    private final Screen parent;
    private final List<ConfigWidget> widgets = new ArrayList<>();
    private int nextY;
    private Path configFile;
    private JsonObject configJson;

    public CobbieConfigScreen(Screen parent) {
        super(Component.translatable(TITLE_KEY));
        this.parent = parent;
    }

    @Override
    protected void init() {
        configFile = findConfigFile();
        loadConfig();
        buildFields();
        super.init();
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
            String raw = Files.readString(configFile);
            configJson = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            Cobbie.LOGGER.warn("Cobbie config unreadable, using defaults: {}", e.toString());
            configJson = defaultConfig();
        }
    }

    private JsonObject defaultConfig() {
        JsonObject o = new JsonObject();
        o.addProperty("provider", "ollama");
        o.addProperty("base_url", "http://127.0.0.1:11434/");
        o.addProperty("model", "lfm2.5:8b");
        o.addProperty("api_key", "");
        o.addProperty("temperature", 0.7);
        o.addProperty("max_tokens", 300);
        o.addProperty("connect_timeout", 5);
        o.addProperty("read_timeout", 120);
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
        o.addProperty("mining_allowed", false);
        o.addProperty("building_allowed", false);
        o.addProperty("combat_allowed", false);
        o.addProperty("player_combat_allowed", false);
        o.addProperty("exploration_allowed", true);
        o.addProperty("collection_allowed", true);
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

    private void buildFields() {
        widgets.clear();
        nextY = 40;

        // --- LLM section ---
        addSectionHeader("§6LLM Connection");
        addTextField("provider", "Provider", "ollama|openai", 200);
        addTextField("base_url", "Base URL", "http://127.0.0.1:11434/", 300);
        addTextField("model", "Model", "lfm2.5:8b", 250);
        addTextField("api_key", "API Key (blank for none)", "", 250);
        addNumberField("temperature", "Temperature", 0.0, 2.0, 0.1, 80);
        addIntField("max_tokens", "Max Tokens", 32, 4096, 100);
        addIntField("connect_timeout", "Connect Timeout (s)", 1, 60, 80);
        addIntField("read_timeout", "Read Timeout (s)", 5, 600, 80);
        addIntField("max_retries", "Max Retries", 0, 3, 80);

        // --- Behaviour section ---
        addSectionHeader("§6Behaviour");
        addEnumField("autonomy_mode", "Autonomy", new String[]{"OFF", "ASSISTIVE", "CREATIVE"});
        addIntField("plan_step_limit", "Plan Step Limit", 1, 8, 80);
        addIntField("task_retry_limit", "Task Retry Limit", 0, 3, 80);
        addIntField("max_concurrent_requests", "Max Concurrent", 1, 8, 80);
        addIntField("task_timeout_seconds", "Task Timeout (s)", 15, 600, 80);

        // --- Movement section ---
        addSectionHeader("§6Movement");
        addNumberField("follow_distance", "Follow Distance", 1.0, 32.0, 0.5, 80);
        addIntField("navigation_range", "Navigation Range", 4, 64, 80);
        addNumberField("interaction_reach", "Interaction Reach", 1.0, 8.0, 0.5, 80);

        // --- Permissions section ---
        addSectionHeader("§6Permissions");
        addBooleanField("mining_allowed", "Mining Allowed");
        addBooleanField("building_allowed", "Building Allowed");
        addBooleanField("combat_allowed", "Combat Allowed");
        addBooleanField("player_combat_allowed", "Player Combat (PvP)");
        addBooleanField("exploration_allowed", "Exploration Allowed");
        addBooleanField("collection_allowed", "Collection Allowed");

        // --- Chat section ---
        addSectionHeader("§6Chat");
        addEnumField("chat_mode", "Chat Mode", new String[]{"MENTION", "OWNER_MENTION", "ALWAYS_LISTEN", "ALLOWLIST"});
        addTextField("allowed_players", "Allowed Players (comma)", "", 250);
        addIntField("chat_cooldown_ms", "Cooldown (ms)", 200, 10000, 100);

        // --- Memory section ---
        addSectionHeader("§6Memory");
        addBooleanField("memory_enabled", "Memory Enabled");
        addIntField("max_chat_history", "Max History", 0, 40, 80);
        addBooleanField("persistent_memory", "Persistent Memory");

        // --- Entity section ---
        addSectionHeader("§6Entity");
        addIntField("inventory_size", "Inventory Slots", 1, 36, 80);
        addBooleanField("only_allowlisted_players", "Gated to Allowlist");
        addIntField("max_cobbies_per_player", "Max Cobbie Per Player", 1, 4, 80);

        // Save / Cancel buttons at bottom
        int buttonY = nextY + 12;
        addRenderableWidget(Button.builder(Component.literal("§aSave"), btn -> {
            saveConfig();
            close();
        }).bounds(width / 2 - 100, buttonY, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§cCancel"), btn -> close())
                .bounds(width / 2 + 5, buttonY, 95, 20).build());
    }

    private void addSectionHeader(String text) {
        // Just a spacing label, rendered in render()
        widgets.add(new ConfigWidget(null, text, ConfigWidget.Type.SECTION));
        nextY += 20;
    }

    private void addTextField(String key, String label, String placeholder, int fieldWidth) {
        EditBox field = new EditBox(font, width / 2 - fieldWidth / 2, nextY, fieldWidth, 20, Component.literal(label));
        field.setHint(Component.literal(placeholder).withStyle(ChatFormatting.GRAY));
        String val = getString(key);
        if (val != null) field.setValue(val);
        field.setResponder(s -> configJson.addProperty(key, s));
        addRenderableWidget(field);
        widgets.add(new ConfigWidget(field, label, ConfigWidget.Type.TEXT));
        nextY += 26;
    }

    private void addNumberField(String key, String label, double min, double max, double step, int fieldWidth) {
        EditBox field = new EditBox(font, width / 2 - fieldWidth / 2, nextY, fieldWidth, 20, Component.literal(label));
        field.setHint(Component.literal(String.format(Locale.ROOT, "%.1f", getDouble(key))).withStyle(ChatFormatting.GRAY));
        String val = getString(key);
        if (val != null) field.setValue(val);
        field.setResponder(s -> {
            try { configJson.addProperty(key, Double.parseDouble(s)); } catch (Exception ignored) {}
        });
        addRenderableWidget(field);
        widgets.add(new ConfigWidget(field, label, ConfigWidget.Type.NUMBER));
        nextY += 26;
    }

    private void addIntField(String key, String label, int min, int max, int fieldWidth) {
        EditBox field = new EditBox(font, width / 2 - fieldWidth / 2, nextY, fieldWidth, 20, Component.literal(label));
        field.setHint(Component.literal(String.valueOf(getInt(key))).withStyle(ChatFormatting.GRAY));
        String val = getString(key);
        if (val != null) field.setValue(val);
        field.setResponder(s -> {
            try { configJson.addProperty(key, Integer.parseInt(s)); } catch (Exception ignored) {}
        });
        addRenderableWidget(field);
        widgets.add(new ConfigWidget(field, label, ConfigWidget.Type.INT));
        nextY += 26;
    }

    private void addBooleanField(String key, String label) {
        Checkbox btn = Checkbox.builder(Component.literal(label), minecraft.font).pos(width / 2 - 100, nextY).selected(getBoolean(key)).onValueChange((b, v) -> configJson.addProperty(key, v)).build();
        addRenderableWidget(btn);
        widgets.add(new ConfigWidget(btn, label, ConfigWidget.Type.BOOLEAN));
        nextY += 26;
    }

    private void addEnumField(String key, String label, String[] values) {
        // Use a button that cycles through values on click
        String initial = getString(key);
        Button btn = Button.builder(Component.literal(label + ": " + initial), b -> {
            String current = configJson.get(key).getAsString();
            int idx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equalsIgnoreCase(current)) {
                    idx = (i + 1) % values.length;
                    break;
                }
            }
            String nextVal = values[idx];
            configJson.addProperty(key, nextVal);
            b.setMessage(Component.literal(label + ": " + nextVal));
        }).bounds(width / 2 - 120, nextY, 240, 20).build();
        addRenderableWidget(btn);
        widgets.add(new ConfigWidget(btn, label, ConfigWidget.Type.ENUM));
        nextY += 26;
    }

    private String getString(String key) {
        return configJson.has(key) && configJson.get(key).isJsonPrimitive()
                ? configJson.get(key).getAsString() : null;
    }

    private double getDouble(String key) {
        return configJson.has(key) && configJson.get(key).isJsonPrimitive()
                ? configJson.get(key).getAsDouble() : 0;
    }

    private int getInt(String key) {
        return configJson.has(key) && configJson.get(key).isJsonPrimitive()
                ? configJson.get(key).getAsInt() : 0;
    }

    private boolean getBoolean(String key) {
        return configJson.has(key) && configJson.get(key).isJsonPrimitive()
                ? configJson.get(key).getAsBoolean() : false;
    }

    private void saveConfig() {
        if (configFile == null) return;
        try {
            String json = GSON.toJson(configJson);
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomic) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Cobbie.LOGGER.info("Cobbie config saved via GUI");
            // Push new LLM settings into the brain immediately
            CobbieConfig.reload();
        } catch (Exception e) {
            Cobbie.LOGGER.error("Failed to save Cobbie config: {}", e.toString());
        }
    }

    public void close() {
        minecraft.setScreenAndShow(parent);
    }

    
    private static class ConfigWidget {
        enum Type { SECTION, TEXT, NUMBER, INT, BOOLEAN, ENUM }
        final GuiEventListener widget;
        final String label;
        final Type type;
        ConfigWidget(GuiEventListener widget, String label, Type type) {
            this.widget = widget;
            this.label = label;
            this.type = type;
        }
    }
}