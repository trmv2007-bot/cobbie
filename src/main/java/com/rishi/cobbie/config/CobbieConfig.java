package com.rishi.cobbie.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.brain.BrainPresets;
import com.rishi.cobbie.brain.CobbieBrain;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cobbie's configuration, stored as JSON at {@code config/cobbie.json}.
 *
 * <p>Every key here is honoured somewhere in the running application — a key that stops being
 * wired gets deleted rather than left decorative. Values are clamped on load: an out-of-range or
 * unparseable entry falls back to its default with a warning instead of taking the mod down, and a
 * corrupt file is set aside as {@code cobbie.json.bad} rather than thrown at.
 *
 * <p>Loading is lazy ({@link #ensureLoaded()}) so the pure-Java unit tests can call getters without
 * a Fabric runtime.
 */
public final class CobbieConfig {

	/** How much initiative Cobbie is allowed to take on his own. */
	public enum AutonomyMode {
		OFF("only does what the owner explicitly asks"),
		ASSISTIVE("follows, helps, picks up items and completes requested plans, but starts no projects of his own"),
		CREATIVE("explores, gathers, practises known skills and proposes his own projects");

		private final String blurb;

		AutonomyMode(String blurb) {
			this.blurb = blurb;
		}

		public String blurb() {
			return blurb;
		}

		/** @return the match, or {@code fallback} (which may itself be null) when unrecognised. */
		public static AutonomyMode parse(String raw, AutonomyMode fallback) {
			if (raw == null || raw.isBlank()) return fallback;
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return fallback;
			}
		}
	}

	/** Who Cobbie is willing to listen to. */
	public enum ChatMode {
		/** Reply when the message contains the whole word "cobbie" — anyone may talk to a stray Cobbie. */
		MENTION,
		/** Reply to the owner and allow-listed players without needing a mention. */
		ALWAYS_LISTEN,
		/** Reply to the owner only, and only when mentioned. */
		OWNER_MENTION,
		/** Reply to owner + allow-list only, no mention needed. */
		ALLOWLIST;

		public static ChatMode parse(String raw, ChatMode fallback) {
			if (raw == null || raw.isBlank()) return fallback;
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return fallback;
			}
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ---- LLM ----
	// A fresh install is keys-first: a hosted provider, an empty key, and nothing listening on
	// localhost to probe. Ollama is still one of the presets, just not the assumption.
	private static String llmBaseUrl = BrainPresets.DEFAULT_BASE_URL;
	private static String llmModel = BrainPresets.DEFAULT_MODEL;
	private static String llmApiKey = "";
	/** The key as the config file has it. An environment-provided key is never written back. */
	private static String persistedApiKey = "";
	private static String llmProvider = BrainPresets.DEFAULT_PROVIDER;
	private static double temperature = 0.7;
	// Hosted reasoners spend 100-250 tokens thinking before they answer. A 300-token budget was
	// cutting the answer off and coming back empty; 800 leaves room for the thinking and the reply.
	private static int maxTokens = 800;
	private static int connectTimeoutSeconds = 5;
	// Fail fast enough that the never-silent fallback lands while the player is still looking: a
	// hosted call that hangs for two minutes reads as a crashed companion. Slow local setups can
	// raise this in the config screen.
	private static int readTimeoutSeconds = 30;
	private static int maxRetries = 1;

	// ---- AI behaviour ----
	private static String personality = "warm, curious, a little playful; a good friend who is never scary";
	private static AutonomyMode autonomyMode = AutonomyMode.ASSISTIVE;
	private static int planStepLimit = 6;
	private static int taskRetryLimit = 1;
	private static int maxConcurrentRequests = 2;
	private static int taskTimeoutSeconds = 180;

	// ---- Movement ----
	private static double followDistance = 4.0;
	private static int navigationRange = 24;
	private static double interactionReach = 4.0;
	/** How far from his owner a wander may aim. Separate from navigation_range: that is how far he
	 *  will walk for a request, this is how far he will go looking around. */
	private static int explorationRadius = 48;
	/** An explicit last resort, off by default: teleport to the owner when this far away and making
	 *  no progress. 0 disables it. Never the normal way he travels. */
	private static int recallDistance = 0;

	// ---- Permissions (re-checked at execution time, never only at parse time) ----
	private static boolean miningAllowed = false;
	private static boolean buildingAllowed = false;
	private static boolean combatAllowed = false;
	private static boolean playerCombatAllowed = false;
	private static boolean explorationAllowed = true;
	private static boolean collectionAllowed = true;
	private static boolean craftingAllowed = false;
	private static boolean containersAllowed = false;
	/** The owner's ender chest is his private storage; it needs its own switch, separate from chests. */
	private static boolean enderChestAllowed = false;

	// ---- Chat ----
	private static ChatMode chatMode = ChatMode.MENTION;
	private static final List<String> allowedPlayers = new ArrayList<>();
	private static int chatCooldownMs = 900;
	/** How long a burst of chat is collected before one model call is made. */
	private static int chatBatchMs = 350;

	// ---- Memory ----
	private static boolean memoryEnabled = true;
	private static int maxChatHistory = 12;
	private static boolean persistentMemory = true;

	// ---- Entity / server ----
	// 36 slots plus a 9-slot hotbar's worth: the same carrying capacity a player's inventory rows give,
	// which is what stops "empty that chest into your bag" landing on the floor after nine stacks.
	private static int inventorySize = 45;
	private static boolean gatedToAllowlist = false;
	private static int maxCobbiesPerPlayer = 1;

	private static boolean loaded = false;

	private CobbieConfig() {}

	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cobbie.json");
	}

	/** Load from disk once, then push the LLM settings into the brain. Never throws. */
	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			Path file = configFile();
			if (Files.exists(file)) {
				readFrom(file);
			} else {
				Files.createDirectories(file.getParent());
				save();
			}
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Cobbie config unavailable ({}), running on defaults", t.toString());
		}
		applyEnvironmentOverrides();
		applyToBrain();
		logSummary();
	}

	private static void readFrom(Path file) {
		String raw;
		try {
			raw = Files.readString(file);
		} catch (Exception e) {
			Cobbie.LOGGER.error("Cobbie config unreadable at {}: {}", file, e.toString());
			return;
		}
		JsonObject root;
		try {
			root = JsonParser.parseString(raw).getAsJsonObject();
		} catch (Exception e) {
			// Corrupt JSON: put the evidence out of the way and carry on with defaults.
			try {
				Files.move(file, file.resolveSibling("cobbie.json.bad"), StandardCopyOption.REPLACE_EXISTING);
				Cobbie.LOGGER.error("Cobbie config is not valid JSON; moved aside to cobbie.json.bad, using defaults");
			} catch (Exception moveFailed) {
				Cobbie.LOGGER.error("Cobbie config is not valid JSON and could not be moved aside: {}",
						moveFailed.toString());
			}
			return;
		}

		llmBaseUrl = str(root, "base_url", llmBaseUrl);
		llmModel = str(root, "model", llmModel);
		llmApiKey = str(root, "api_key", llmApiKey);
		persistedApiKey = llmApiKey;
		llmProvider = normProvider(str(root, "provider", llmProvider));
		temperature = dbl(root, "temperature", temperature, 0.0, 2.0);
		maxTokens = integer(root, "max_tokens", maxTokens, 32, 8192);
		connectTimeoutSeconds = integer(root, "connect_timeout", connectTimeoutSeconds, 1, 120);
		readTimeoutSeconds = integer(root, "read_timeout", readTimeoutSeconds, 5, 600);
		maxRetries = integer(root, "max_retries", maxRetries, 0, 8);

		personality = str(root, "personality", personality);
		autonomyMode = AutonomyMode.parse(str(root, "autonomy_mode", autonomyMode.name()), autonomyMode);
		planStepLimit = integer(root, "plan_step_limit", planStepLimit, 1, 24);
		taskRetryLimit = integer(root, "task_retry_limit", taskRetryLimit, 0, 8);
		maxConcurrentRequests = integer(root, "max_concurrent_requests", maxConcurrentRequests, 1, 16);
		taskTimeoutSeconds = integer(root, "task_timeout_seconds", taskTimeoutSeconds, 15, 3600);

		followDistance = dbl(root, "follow_distance", followDistance, 1.0, 64.0);
		navigationRange = integer(root, "navigation_range", navigationRange, 4, 256);
		interactionReach = dbl(root, "interaction_reach", interactionReach, 1.0, 16.0);
		explorationRadius = integer(root, "exploration_radius", explorationRadius, 8, 256);
		recallDistance = integer(root, "recall_distance", recallDistance, 0, 4096);

		miningAllowed = bool(root, "mining_allowed", miningAllowed);
		buildingAllowed = bool(root, "building_allowed", buildingAllowed);
		combatAllowed = bool(root, "combat_allowed", combatAllowed);
		playerCombatAllowed = bool(root, "player_combat_allowed", playerCombatAllowed);
		explorationAllowed = bool(root, "exploration_allowed", explorationAllowed);
		collectionAllowed = bool(root, "collection_allowed", collectionAllowed);
		craftingAllowed = bool(root, "crafting_allowed", craftingAllowed);
		containersAllowed = bool(root, "containers_allowed", containersAllowed);
		enderChestAllowed = bool(root, "ender_chest_allowed", enderChestAllowed);

		chatMode = ChatMode.parse(str(root, "chat_mode", chatMode.name()), chatMode);
		chatCooldownMs = integer(root, "chat_cooldown_ms", chatCooldownMs, 0, 10000);
		chatBatchMs = integer(root, "chat_batch_ms", chatBatchMs, 0, 5000);
		allowedPlayers.clear();
		if (root.has("allowed_players") && root.get("allowed_players").isJsonArray()) {
			for (var el : root.getAsJsonArray("allowed_players")) {
				if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
					allowedPlayers.add(el.getAsString().trim().toLowerCase(Locale.ROOT));
				}
			}
		}

		memoryEnabled = bool(root, "memory_enabled", memoryEnabled);
		maxChatHistory = integer(root, "max_chat_history", maxChatHistory, 0, 200);
		persistentMemory = bool(root, "persistent_memory", persistentMemory);

		inventorySize = integer(root, "inventory_size", inventorySize, 1, 54);
		gatedToAllowlist = bool(root, "only_allowlisted_players", gatedToAllowlist);
		maxCobbiesPerPlayer = integer(root, "max_cobbies_per_player", maxCobbiesPerPlayer, 1, 16);
	}

	// ---- tolerant readers, each one clamps and warns ----

	private static String str(JsonObject o, String key, String fallback) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
		try {
			String v = o.get(key).getAsString().trim();
			return v.isEmpty() && !key.equals("api_key") ? fallback : v;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
		try {
			return o.get(key).getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int integer(JsonObject o, String key, int fallback, int lo, int hi) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
		int v;
		try {
			v = o.get(key).getAsInt();
		} catch (Exception e) {
			Cobbie.LOGGER.warn("Cobbie config '{}' is not a whole number, using {}", key, fallback);
			return fallback;
		}
		if (v < lo || v > hi) {
			Cobbie.LOGGER.warn("Cobbie config '{}' = {} out of range [{}, {}], clamped", key, v, lo, hi);
			return Math.max(lo, Math.min(hi, v));
		}
		return v;
	}

	private static double dbl(JsonObject o, String key, double fallback, double lo, double hi) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
		double v;
		try {
			v = o.get(key).getAsDouble();
		} catch (Exception e) {
			Cobbie.LOGGER.warn("Cobbie config '{}' is not a number, using {}", key, fallback);
			return fallback;
		}
		if (Double.isNaN(v) || v < lo || v > hi) {
			Cobbie.LOGGER.warn("Cobbie config '{}' = {} out of range [{}, {}], clamped", key, v, lo, hi);
			return Math.max(lo, Math.min(hi, v));
		}
		return v;
	}

	private static String normProvider(String p) {
		String v = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
		if (v.isEmpty()) return BrainPresets.DEFAULT_PROVIDER;
		if (v.startsWith("openai")) return "openai";
		// A preset id written by hand ("groq", "openrouter") means the protocol of that preset.
		BrainPresets.Preset preset = BrainPresets.byId(v);
		if (preset != null) return preset.provider();
		return "ollama";
	}

	/**
	 * Optional environment overrides, applied after the file so a dedicated server or a test run can
	 * point Cobbie at a provider without a key on disk: {@code COBBIE_PROVIDER},
	 * {@code COBBIE_BASE_URL}, {@code COBBIE_MODEL}, {@code COBBIE_API_KEY}.
	 *
	 * <p>An environment key is held in memory only — {@link #save()} keeps writing whatever the file
	 * had, so toggling a permission can never copy the environment's secret into the config file, and
	 * the summary logs say "environment", never the value.
	 */
	private static void applyEnvironmentOverrides() {
		String provider = env("COBBIE_PROVIDER");
		String url = env("COBBIE_BASE_URL");
		String model = env("COBBIE_MODEL");
		String key = env("COBBIE_API_KEY");
		if (provider == null && url == null && model == null && key == null) return;

		if (provider != null) llmProvider = normProvider(provider);
		if (url != null) llmBaseUrl = url;
		if (model != null) llmModel = model;
		if (key != null) llmApiKey = key;
		Cobbie.LOGGER.info("Cobbie brain overridden by environment: provider={} url={} model={} key={}",
				llmProvider, llmBaseUrl, llmModel, key == null ? "from file" : "from environment");
	}

	private static String env(String name) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? null : v.trim();
	}

	private static void applyToBrain() {
		CobbieBrain.configure(llmBaseUrl, llmModel, llmApiKey, llmProvider, temperature, maxTokens,
				connectTimeoutSeconds, readTimeoutSeconds, maxRetries);
	}

	/** Never prints the key itself, only whether one is present. */
	private static void logSummary() {
		Cobbie.LOGGER.info("Cobbie config: url={} model={} provider={} key={} temp={} tokens={} autonomy={} chat={}",
				llmBaseUrl, llmModel, llmProvider, llmApiKey.isBlank() ? "absent" : "present",
				temperature, maxTokens, autonomyMode, chatMode);
	}

	// ---- public getters (each validates through ensureLoaded) ----

	public static String getBaseUrl() { ensureLoaded(); return llmBaseUrl; }
	public static String getModel() { ensureLoaded(); return llmModel; }
	public static String getApiKey() { ensureLoaded(); return llmApiKey; }
	public static String getProvider() { ensureLoaded(); return llmProvider; }
	public static double getTemperature() { ensureLoaded(); return temperature; }
	public static int getMaxTokens() { ensureLoaded(); return maxTokens; }
	public static int getConnectTimeoutSeconds() { ensureLoaded(); return connectTimeoutSeconds; }
	public static int getReadTimeoutSeconds() { ensureLoaded(); return readTimeoutSeconds; }
	public static int getMaxRetries() { ensureLoaded(); return maxRetries; }

	public static String getPersonality() { ensureLoaded(); return personality; }
	public static AutonomyMode getAutonomyMode() { ensureLoaded(); return autonomyMode; }
	public static int getPlanStepLimit() { ensureLoaded(); return planStepLimit; }
	public static int getTaskRetryLimit() { ensureLoaded(); return taskRetryLimit; }
	public static int getMaxConcurrentRequests() { ensureLoaded(); return maxConcurrentRequests; }
	public static int getTaskTimeoutSeconds() { ensureLoaded(); return taskTimeoutSeconds; }

	public static double getFollowDistance() { ensureLoaded(); return followDistance; }
	public static int getNavigationRange() { ensureLoaded(); return navigationRange; }
	public static double getInteractionReach() { ensureLoaded(); return interactionReach; }
	public static int getExplorationRadius() { ensureLoaded(); return explorationRadius; }
	public static int getRecallDistance() { ensureLoaded(); return recallDistance; }

	public static boolean isMiningAllowed() { ensureLoaded(); return miningAllowed; }
	public static boolean isBuildingAllowed() { ensureLoaded(); return buildingAllowed; }
	public static boolean isCombatAllowed() { ensureLoaded(); return combatAllowed; }
	public static boolean isPlayerCombatAllowed() { ensureLoaded(); return playerCombatAllowed; }
	public static boolean isExplorationAllowed() { ensureLoaded(); return explorationAllowed; }
	public static boolean isCollectionAllowed() { ensureLoaded(); return collectionAllowed; }
	public static boolean isCraftingAllowed() { ensureLoaded(); return craftingAllowed; }
	public static boolean isContainersAllowed() { ensureLoaded(); return containersAllowed; }
	public static boolean isEnderChestAllowed() { ensureLoaded(); return enderChestAllowed; }

	public static ChatMode getChatMode() { ensureLoaded(); return chatMode; }
	public static List<String> getAllowedPlayers() { ensureLoaded(); return List.copyOf(allowedPlayers); }

	public static boolean isAllowedPlayer(String name) {
		ensureLoaded();
		return name != null && allowedPlayers.contains(name.toLowerCase(Locale.ROOT));
	}

	public static int getChatCooldownMs() { ensureLoaded(); return chatCooldownMs; }

	/** How long to collect a burst of chat before one model call. */
	public static int getChatBatchMs() {
		ensureLoaded();
		return chatBatchMs;
	}

	public static boolean isMemoryEnabled() { ensureLoaded(); return memoryEnabled; }
	public static int getMaxChatHistory() { ensureLoaded(); return maxChatHistory; }
	public static boolean isPersistentMemory() { ensureLoaded(); return persistentMemory; }

	public static int getInventorySize() { ensureLoaded(); return inventorySize; }
	public static boolean isGatedToAllowlist() { ensureLoaded(); return gatedToAllowlist; }
	public static int getMaxCobbiesPerPlayer() { ensureLoaded(); return maxCobbiesPerPlayer; }

	/**
	 * May this player talk to / command a companion? The allow-list is the only gate this mod
	 * invents; Minecraft's own OP levels are left to the server. When the list is empty and gating
	 * is on, only a player who already owns a companion passes (checked by the caller).
	 */
	public static boolean mayUse(String playerName) {
		ensureLoaded();
		return !gatedToAllowlist || isAllowedPlayer(playerName);
	}

	// ---- permission helpers, used at execution time ----

	/** True when Cobbie may act on his own initiative at all. */
	public static boolean isAutonomous() { ensureLoaded(); return autonomyMode != AutonomyMode.OFF; }

	/** True when Cobbie may start projects nobody asked for. */
	public static boolean isCreative() { ensureLoaded(); return autonomyMode == AutonomyMode.CREATIVE; }

	/** The single place that maps an action verb to the permission that gates it. */
	public static boolean permits(String action) {
		ensureLoaded();
		return switch (action == null ? "" : action) {
			case "mine" -> miningAllowed;
			// Block use - planting, bone meal, levers, buttons - sits under building rather than
			// inventing a switch per verb: it is all "change the world with your hands".
			case "place", "light", "use" -> buildingAllowed;
			// Farming is block work with his hands, so it sits under the same switch as planting.
			case "farm" -> buildingAllowed;
			case "attack" -> combatAllowed;
			case "explore" -> explorationAllowed && autonomyMode != AutonomyMode.OFF;
			case "collect" -> collectionAllowed;
			case "craft", "smelt", "take", "put" -> craftingAllowed || containersAllowed;
			// Mending and dyeing are crafting: they are a grid of his own things, not a station.
			case "repair", "dye", "enchant", "anvil", "smith", "grind", "weave", "map" -> craftingAllowed;
			// Brewing is a station job like smelting: a stand in the world he puts things into.
			case "brew" -> containersAllowed;
			case "follow", "stay", "wave", "chat", "answer", "wait", "walk-to", "give", "drop", "equip", "lookup" -> true;
			default -> false;
		};
	}

	/** Explicitly load config at mod init. */
	public static void loadAndApply() {
		ensureLoaded();
	}

	/** Re-read the file from disk and reconfigure. Used by {@code /cobbie reload}. */
	public static synchronized void reload() {
		loaded = false;
		ensureLoaded();
	}

	/** Rewrite the file from current in-memory values. Doubles as the generated config example. */
	public static void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("provider", llmProvider);
			root.addProperty("base_url", llmBaseUrl);
			root.addProperty("model", llmModel);
			root.addProperty("api_key", persistedApiKey);
			root.addProperty("temperature", temperature);
			root.addProperty("max_tokens", maxTokens);
			root.addProperty("connect_timeout", connectTimeoutSeconds);
			root.addProperty("read_timeout", readTimeoutSeconds);
			root.addProperty("max_retries", maxRetries);

			root.addProperty("personality", personality);
			root.addProperty("autonomy_mode", autonomyMode.name());
			root.addProperty("plan_step_limit", planStepLimit);
			root.addProperty("task_retry_limit", taskRetryLimit);
			root.addProperty("max_concurrent_requests", maxConcurrentRequests);
			root.addProperty("task_timeout_seconds", taskTimeoutSeconds);

			root.addProperty("follow_distance", followDistance);
			root.addProperty("navigation_range", navigationRange);
			root.addProperty("interaction_reach", interactionReach);
			root.addProperty("exploration_radius", explorationRadius);
			root.addProperty("recall_distance", recallDistance);

			root.addProperty("mining_allowed", miningAllowed);
			root.addProperty("building_allowed", buildingAllowed);
			root.addProperty("combat_allowed", combatAllowed);
			root.addProperty("player_combat_allowed", playerCombatAllowed);
			root.addProperty("exploration_allowed", explorationAllowed);
			root.addProperty("collection_allowed", collectionAllowed);
			root.addProperty("crafting_allowed", craftingAllowed);
			root.addProperty("containers_allowed", containersAllowed);
			root.addProperty("ender_chest_allowed", enderChestAllowed);

			root.addProperty("chat_mode", chatMode.name());
			JsonArray players = new JsonArray();
			allowedPlayers.forEach(players::add);
			root.add("allowed_players", players);
			root.addProperty("chat_cooldown_ms", chatCooldownMs);
		root.addProperty("chat_batch_ms", chatBatchMs);

			root.addProperty("memory_enabled", memoryEnabled);
			root.addProperty("max_chat_history", maxChatHistory);
			root.addProperty("persistent_memory", persistentMemory);

			root.addProperty("inventory_size", inventorySize);
			root.addProperty("only_allowlisted_players", gatedToAllowlist);
			root.addProperty("max_cobbies_per_player", maxCobbiesPerPlayer);

			Path file = configFile();
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root));
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicUnsupported) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable t) {
			Cobbie.LOGGER.warn("Cobbie could not save its config: {}", t.toString());
		}
	}

	/**
	 * Flip one permission switch at runtime and persist it. Used by {@code /cobbie permit}.
	 * @return false when the switch name is unknown or the value is not on/off.
	 */
	public static boolean setPermission(String switchName, String onOrOff) {
		ensureLoaded();
		Boolean value = switch (onOrOff == null ? "" : onOrOff.trim().toLowerCase(Locale.ROOT)) {
			case "on", "true", "yes", "1" -> Boolean.TRUE;
			case "off", "false", "no", "0" -> Boolean.FALSE;
			default -> null;
		};
		if (value == null) return false;
		switch (switchName == null ? "" : switchName.trim().toLowerCase(Locale.ROOT)) {
			case "mining", "mine" -> miningAllowed = value;
			case "building", "build", "place" -> buildingAllowed = value;
			case "combat", "attack" -> combatAllowed = value;
			case "player_combat", "playercombat", "pvp" -> playerCombatAllowed = value;
			case "exploration", "explore" -> explorationAllowed = value;
			case "collection", "collect", "pickup" -> collectionAllowed = value;
			case "crafting", "craft" -> craftingAllowed = value;
			case "containers", "container", "storage", "chests" -> containersAllowed = value;
			case "ender_chest", "enderchest" -> enderChestAllowed = value;
			default -> {
				return false;
			}
		}
		save();
		Cobbie.LOGGER.info("Cobbie permission '{}' set to {}", switchName, value);
		return true;
	}

	/** Set the autonomy mode at runtime (used by {@code /cobbie autonomy <mode>}). */
	public static boolean setAutonomyMode(String raw) {
		ensureLoaded();
		AutonomyMode parsed = AutonomyMode.parse(raw, null);
		if (parsed == null) return false;
		autonomyMode = parsed;
		save();
		return true;
	}

	/** Human-readable status block for {@code /cobbie status}. */
	public static String describe() {
		ensureLoaded();
		return "provider=" + llmProvider + " model=" + llmModel + " url=" + llmBaseUrl
				+ "\nautonomy=" + autonomyMode + " chat=" + chatMode
				+ "\nmining=" + miningAllowed + " building=" + buildingAllowed + " combat=" + combatAllowed
				+ " (players=" + playerCombatAllowed + ") explore=" + explorationAllowed
				+ " collect=" + collectionAllowed + " craft=" + craftingAllowed
				+ " containers=" + containersAllowed + " enderChest=" + enderChestAllowed
				+ "\nfollow=" + followDistance + " nav=" + navigationRange + " reach=" + interactionReach
				+ " exploreRadius=" + explorationRadius + " recall=" + (recallDistance <= 0 ? "off" : recallDistance)
				+ "\nsteps=" + planStepLimit + " retries=" + taskRetryLimit
				+ " history=" + maxChatHistory + " slots=" + inventorySize;
	}
}
