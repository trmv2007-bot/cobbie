package com.rishi.cobbie.brain;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.config.CobbieConfig;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ollama connectivity and model discovery through GET /api/tags.
 * Verifies the configured model is installed before reporting the AI as ready.
 */
public final class OllamaDiscovery {

	private static final Gson GSON = new Gson();

	private OllamaDiscovery() {}

	/** Result of a discovery probe. */
	public record DiscoveryResult(boolean connected, List<String> models, boolean modelFound, String error) {}

	/** Probe the Ollama server for connectivity and model list. */
	public static DiscoveryResult discover() {
		String baseUrl = CobbieConfig.getBaseUrl();
		if (baseUrl == null || baseUrl.isBlank()) {
			return new DiscoveryResult(false, List.of(), false, "no base URL configured");
		}
		if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";

		String model = CobbieConfig.getModel();

		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(CobbieConfig.getConnectTimeoutSeconds()))
				.build();

		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(baseUrl + "api/tags"))
					.timeout(Duration.ofSeconds(Math.max(5, CobbieConfig.getConnectTimeoutSeconds())))
					.GET()
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				return new DiscoveryResult(false, List.of(), false,
						"HTTP " + response.statusCode());
			}

			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray modelsArray = root.getAsJsonArray("models");
			List<String> models = new ArrayList<>();
			if (modelsArray != null) {
				for (JsonElement el : modelsArray) {
					JsonObject obj = el.getAsJsonObject();
					String name = obj.has("name") ? obj.get("name").getAsString() : "";
					if (!name.isEmpty()) models.add(name);
				}
			}

			boolean modelFound = models.contains(model);
			return new DiscoveryResult(true, models, modelFound, null);

		} catch (Exception e) {
			Cobbie.LOGGER.warn("Ollama discovery failed: {}", e.getMessage());
			return new DiscoveryResult(false, List.of(), false, e.getMessage());
		}
	}
}
