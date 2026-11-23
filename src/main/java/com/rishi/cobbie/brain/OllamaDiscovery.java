package com.rishi.cobbie.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.config.CobbieConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The ONE connection-probe implementation in the mod.
 *
 * <p>Everything that needs to know "is the AI reachable, and is my model installed" calls
 * {@link #probeConfiguredAsync()} — the spawn command, {@code /cobbie status} and the brain's
 * health tracking all share it, so they can never disagree. Provider-aware: Ollama lists models at
 * {@code GET /api/tags} ({@code models[].name}), OpenAI-compatible servers at
 * {@code GET /v1/models} ({@code data[].id}).
 *
 * <p>A probe must never run on the server thread — use the async form from game code.
 */
public final class OllamaDiscovery {

	/** Outcome of one probe. */
	public record Probe(ConnectionState state, List<String> models, String matchedModel, String error) {

		public boolean healthy() {
			return state.healthy();
		}

		public boolean modelInstalled() {
			return state == ConnectionState.MODEL_AVAILABLE;
		}

		/** Short, honest line for chat. Never contains a key. */
		public String describe() {
			boolean local = CobbieConfig.getProvider().startsWith("ollama");
			return switch (state) {
				case MODEL_AVAILABLE -> "AI ready — model " + matchedModel + " is loaded on " + models.size() + " installed model(s)";
				case CONNECTED -> "AI reachable, but I could not confirm my model is installed";
				case MODEL_MISSING -> "AI reachable but my model is not installed" + (models.isEmpty()
						? (local ? " (no models found — try: ollama pull " + CobbieConfig.getModel() + ")"
								: " (the provider listed no models — check the model name in /cobbie config)")
						: " — installed: " + String.join(", ", models));
				case DISCONNECTED -> NO_KEY.equals(error)
						? "no API key yet — paste one in /cobbie config"
						: "AI server not configured";
				case CONNECTING -> "checking the AI server...";
				case REQUEST_FAILED -> "AI request failed: " + (error == null ? "unknown reason" : error);
			};
		}
	}

	private static final Probe NOT_CONFIGURED =
			new Probe(ConnectionState.DISCONNECTED, List.of(), null, "no base URL configured");

	/** Sentinel detail for "hosted provider, empty key" — describe() turns it into advice. */
	static final String NO_KEY = "no API key configured";

	private OllamaDiscovery() {}

	/** Probe using whatever is currently in {@link CobbieConfig}. Off the game thread. */
	public static CompletableFuture<Probe> probeConfiguredAsync() {
		return CompletableFuture.supplyAsync(() -> probeConfigured());
	}

	/** Blocking probe against the live config. Call only from a worker thread. */
	public static Probe probeConfigured() {
		String provider = CobbieConfig.getProvider();
		// A hosted provider with no key cannot answer, and hammering its endpoint to prove it would
		// only put a 401 in the log. Say what is actually missing, without touching the network.
		if (!provider.startsWith("ollama") && CobbieConfig.getApiKey().isBlank()) {
			return new Probe(ConnectionState.DISCONNECTED, List.of(), null, NO_KEY);
		}
		return probe(CobbieConfig.getBaseUrl(), provider, CobbieConfig.getModel(),
				CobbieConfig.getApiKey(), CobbieConfig.getConnectTimeoutSeconds(),
				CobbieConfig.getReadTimeoutSeconds());
	}

	/**
	 * Blocking probe against explicit settings.
	 *
	 * <p>{@link ConnectionState#CONNECTED} means "server answered"; only
	 * {@link ConnectionState#MODEL_AVAILABLE} means "and my model is there". Never substitutes a
	 * different model just because one exists.
	 */
	public static Probe probe(String baseUrl, String provider, String model, String apiKey,
			int connectTimeoutSeconds, int readTimeoutSeconds) {
		if (baseUrl == null || baseUrl.isBlank()) return NOT_CONFIGURED;

		String base = LlmClient.origin(baseUrl) + "/";   // same paste-safe normaliser the client uses
		String prov = provider == null || provider.isBlank() ? "ollama" : provider.trim().toLowerCase(Locale.ROOT);
		String url = base + (prov.startsWith("openai") ? "v1/models" : "api/tags");

		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
					.build();
			HttpRequest.Builder req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofSeconds(Math.max(2, readTimeoutSeconds)))
					.GET();
			if (apiKey != null && !apiKey.isBlank()) {
				req.header("Authorization", "Bearer " + apiKey);
			}
			HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				return new Probe(ConnectionState.REQUEST_FAILED, List.of(), null,
						"HTTP " + resp.statusCode() + " from " + url);
			}

			List<String> models = parseModelNames(resp.body(), prov);
			if (models.isEmpty()) {
				// Reachable server, nothing listed (or an unparsable body): say so, don't guess.
				return new Probe(ConnectionState.CONNECTED, List.of(), null,
						"server answered but listed no models");
			}
			String match = ConnectionState.findModel(model, models);
			if (match == null) {
				return new Probe(ConnectionState.MODEL_MISSING, models, null,
						"configured model '" + model + "' is not installed");
			}
			return new Probe(ConnectionState.MODEL_AVAILABLE, models, match, null);
		} catch (Exception e) {
			String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
			Cobbie.LOGGER.warn("Cobbie AI probe failed against {}: {}", url, msg);
			return new Probe(ConnectionState.DISCONNECTED, List.of(), null, msg);
		}
	}

	/** {@code models[].name} for Ollama, {@code data[].id} for OpenAI-compatible servers. */
	static List<String> parseModelNames(String body, String provider) {
		List<String> out = new ArrayList<>();
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			JsonArray arr = null;
			String field = "name";
			if (root.has("models") && root.get("models").isJsonArray()) {
				arr = root.getAsJsonArray("models");
			} else if (root.has("data") && root.get("data").isJsonArray()) {
				arr = root.getAsJsonArray("data");
				field = "id";
			}
			if (arr == null) return out;
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				if (o.has(field) && o.get(field).isJsonPrimitive()) {
					String name = o.get(field).getAsString().trim();
					if (!name.isEmpty() && !out.contains(name)) out.add(name);
				}
			}
		} catch (Exception malformed) {
			Cobbie.LOGGER.warn("Cobbie could not parse the model list: {}", malformed.toString());
		}
		return out;
	}
}
