package com.rishi.cobbie.brain;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.world.WorldPerception;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Wires Cobbie's brain to server chat with configurable activation, owner-aware
 * routing, per-player throttling, duplicate prevention, and structured LLM
 * result handling.
 *
 * Never-silent: if the LLM is down/slow/errors, Cobbie still says something
 * friendly.
 */
public final class CobbieBrain {

	private static volatile LlmClient client;
	private static final long ACTION_COOLDOWN_MS = 500;
	private static final int MAX_CONCURRENT_REQUESTS = 3;
	private static final ConcurrentHashMap<UUID, Long> lastActionTime = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Long> lastChatTime = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, CompletableFuture<?>> inFlightRequests = new ConcurrentHashMap<>();
	private static final int CHAT_COOLDOWN_MS = 1000; // per-player chat throttle

	private CobbieBrain() {}

	/** Configure the brain from mod config (called once at init). Does NOT block on network. */
	public static void configure(String baseUrl, String model, String apiKey,
			String provider, double temperature, int maxTokens,
			int connectTimeoutSeconds, int readTimeoutSeconds) {
		client = new LlmClient(baseUrl, model, apiKey, provider, temperature, maxTokens,
				connectTimeoutSeconds, readTimeoutSeconds);
		Cobbie.LOGGER.info("Cobbie brain configured: {} model={} provider={}", baseUrl, model, provider);
	}

	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();
			if (text == null || text.isBlank()) return;

			if (client == null) {
				sender.sendSystemMessage(cobbieSays("Hey! My brain isn't configured yet, but I'm here! (Set my LLM in the config.)"));
				return;
			}

			UUID playerUuid = sender.getUUID();
			String playerName = sender.getGameProfile().name();

			// --- Activation mode: default = explicit mention ---
			String lower = text.toLowerCase(Locale.ROOT);
			boolean addressed = lower.contains("cobbie") || lower.contains("@cobbie");
			if (!CobbieConfig.isAlwaysListenEnabled() && !addressed) {
				return; // silently ignore — not addressed to Cobbie
			}

			// Find the Cobbie entity nearest to the player, if any.
			CobbieEntity target = findNearestCobbie(sender);
			if (target == null) {
				sender.sendSystemMessage(cobbieSays("I can't see you right now, but I'm listening!"));
				return;
			}

			// Per-player throttling
			Long lastChat = lastChatTime.get(playerUuid);
			long now = System.currentTimeMillis();
			if (lastChat != null && now - lastChat < CHAT_COOLDOWN_MS) {
				sender.sendSystemMessage(cobbieSays("Hold on, I'm still thinking about what you said before."));
				return;
			}
			lastChatTime.put(playerUuid, now);

			// Max concurrent requests
			if (inFlightRequests.size() >= MAX_CONCURRENT_REQUESTS) {
				sender.sendSystemMessage(cobbieSays("I'm a bit busy right now — please wait a moment."));
				return;
			}

			// Duplicate request prevention: same player + same message within cooldown
			String dedupKey = playerUuid + ":" + text.hashCode();
			if (inFlightRequests.containsKey(dedupKey)) {
				sender.sendSystemMessage(cobbieSays("I already heard you — give me a second to respond."));
				return;
			}

			// Perceive the world + load memory for this player.
			var snapshot = com.rishi.cobbie.world.WorldPerception.snapshot(target);
			var memory = CobbieMemory.get(playerUuid);
			CobbieMemory.setNickname(playerUuid, playerName);

			// Build prompt with world state + memory.
			String prompt = CobbiePrompt.systemPrompt(snapshot,
					CobbieMemory.recentAnswers(playerUuid, CobbieConfig.getMaxChatHistory()));
			String userMsg = CobbiePrompt.userMessage(playerName, text);

			// Capture all needed variables as final for the lambda chain
			final String pName = playerName;
			final ServerPlayer s = sender;
			final CobbieEntity t = target;
			CompletableFuture<?> future = client.complete(prompt, userMsg).thenAccept(result -> {
				// Remove from in-flight
				inFlightRequests.remove(dedupKey);

				if (!result.success()) {
					Cobbie.LOGGER.warn("LLM failed for player {}: {}", pName, result.error());
					final String errorMsg = "Sorry, I'm having trouble thinking right now. Try again in a moment.";
					((net.minecraft.server.level.ServerLevel) s.level()).getServer().execute(() ->
							s.sendSystemMessage(cobbieSays(errorMsg)));
					return; // Do NOT save failed responses as conversation turns
				}

				String answer = result.content();
				if (answer == null || answer.isBlank()) {
					answer = "...";
				}

				final String finalAnswer = answer;

				// Speak the answer on the server thread — only to the player who spoke, plus owner.
				((net.minecraft.server.level.ServerLevel) s.level()).getServer().execute(() -> {
					s.sendSystemMessage(cobbieSays(finalAnswer));
					if (t.getOwner() != null && t.getOwner() != s.getUUID()) {
						Player owner = ((net.minecraft.server.level.ServerLevel) s.level()).getPlayerByUUID(t.getOwner());
						if (owner != null && owner != s) {
							owner.sendSystemMessage(cobbieSays("(to " + pName + ") " + finalAnswer));
						}
					}
					queueActions(t, ActionParser.parse(finalAnswer).actions());
				});

				// Persist only successful conversation turns.
				CobbieMemory.addTurn(playerUuid, text, finalAnswer);
			});

			inFlightRequests.put(dedupKey, future);
		});
	}

	/** Queue parsed intents onto the entity's action queue (server thread). */
	private static void queueActions(CobbieEntity entity, java.util.List<ActionIntent> actions) {
		UUID uuid = entity.getUUID();
		long now = System.currentTimeMillis();
		Long last = lastActionTime.get(uuid);

		int queued = 0;
		for (ActionIntent intent : actions) {
			boolean allowed = true;

			switch (intent.action()) {
				case "mine" -> {
					if (!CobbieConfig.isMiningAllowed()) allowed = false;
				}
				case "place" -> {
					if (!CobbieConfig.isBuildingAllowed()) allowed = false;
				}
				case "attack" -> {
					if (!CobbieConfig.isCombatAllowed()) allowed = false;
				}
				case "explore", "wander" -> {
					if (!CobbieConfig.isExplorationAllowed()) allowed = false;
				}
			}

			if (!allowed) {
				Cobbie.LOGGER.warn("Cobbie action '{}' blocked by config", intent.action());
				continue;
			}

			// Cooldown: prevent rapid-fire duplicate actions
			if (last != null && now - last < ACTION_COOLDOWN_MS && !intent.action().equals("answer")) {
				continue;
			}

			entity.enqueue(intent);
			queued++;
		}

		if (queued > 0) {
			lastActionTime.put(uuid, now);
		}
	}

	/** Find the Cobbie entity closest to the player. */
	private static CobbieEntity findNearestCobbie(ServerPlayer player) {
		net.minecraft.world.level.Level level = player.level();
		List<CobbieEntity> cobbies = new java.util.ArrayList<>();
		level.getEntitiesOfClass(CobbieEntity.class,
				new AABB(player.blockPosition()).inflate(48.0), cobbies::add);
		if (cobbies.isEmpty()) return null;
		return cobbies.stream()
				.min(Comparator.comparingDouble(c -> c.distanceToSqr(player)))
				.orElse(null);
	}

	private static Component cobbieSays(String text) {
		return Component.literal("<Cobbie> " + text);
	}
}
