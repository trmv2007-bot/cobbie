package com.rishi.cobbie.brain;

import com.rishi.cobbie.Cobbie;
import com.google.gson.*;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Minimal BYOK LLM client supporting Ollama-native (/api/chat) and OpenAI-compatible
 * (/v1/chat/completions) providers.
 *
 * Uses Gson for request/response. Async via CompletableFuture so a slow LLM never
 * blocks the server thread.
 */
public final class LlmClient {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final HttpClient http;
	private final String baseUrl;
	private final String model;
	private final String apiKey;
	private final String provider;   // "ollama" or "openai"
	private final double temperature;
	private final int maxTokens;
	private final Duration connectTimeout;
	private final Duration readTimeout;

	public LlmClient(String baseUrl, String model, String apiKey,
			String provider, double temperature, int maxTokens,
			int connectTimeoutSeconds, int readTimeoutSeconds) {
		String cleaned = baseUrl == null || baseUrl.isBlank()
				? "http://127.0.0.1:11434"
				: baseUrl.replaceAll("/+$", "");
		this.baseUrl = cleaned + "/";
		this.model = model == null || model.isBlank() ? "SparkLLM/Spark-X2.6-4B:q4_k_m" : model;
		this.apiKey = apiKey == null ? "" : apiKey;
		this.provider = provider == null || provider.isBlank() ? "ollama" : provider;
		this.temperature = temperature;
		this.maxTokens = maxTokens;
		this.connectTimeout = Duration.ofSeconds(Math.max(1, connectTimeoutSeconds));
		this.readTimeout = Duration.ofSeconds(Math.max(5, readTimeoutSeconds));
		this.http = HttpClient.newBuilder()
				.connectTimeout(this.connectTimeout)
				.build();
	}

	public record Result(String content, boolean success, String error) {
		public static Result success(String content) {
			return new Result(content, true, null);
		}
		public static Result failure(String error) {
			return new Result(null, false, error);
		}
	}

	public CompletableFuture<Result> complete(String systemPrompt, String userMessage) {
		String body = buildBody(systemPrompt, userMessage);
		String endpoint = "ollama".equalsIgnoreCase(provider) ? "api/chat" : "v1/chat/completions";

		return attempt(body, endpoint);
	}

	private CompletableFuture<Result> attempt(String body, String endpoint) {
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + endpoint))
				.timeout(readTimeout)
				.header("Content-Type", "application/json");
		if (apiKey != null && !apiKey.isBlank()) {
			req.header("Authorization", "Bearer " + apiKey);
		}

		return http.sendAsync(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
						HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					if (resp.statusCode() == 200) {
						String content = extractContent(resp.body());
						if (content == null || content.isBlank()) {
							return Result.failure("LLM returned empty content");
						}
						return Result.success(content);
					}
					return Result.failure("HTTP " + resp.statusCode());
				})
				.exceptionally(ex -> {
					Cobbie.LOGGER.warn("LLM request failed: {}", ex.getMessage());
					return Result.failure(ex.getMessage());
				});
	}

	private String buildBody(String systemPrompt, String userMessage) {
		JsonObject msgSys = new JsonObject();
		msgSys.addProperty("role", "system");
		msgSys.addProperty("content", systemPrompt);

		JsonObject msgUsr = new JsonObject();
		msgUsr.addProperty("role", "user");
		msgUsr.addProperty("content", userMessage);

		JsonArray messages = new JsonArray();
		messages.add(msgSys);
		messages.add(msgUsr);

		JsonObject root = new JsonObject();
		root.addProperty("model", model);
		root.add("messages", messages);
		root.addProperty("stream", false);

		if ("ollama".equalsIgnoreCase(provider)) {
			JsonObject options = new JsonObject();
			options.addProperty("temperature", temperature);
			options.addProperty("num_predict", maxTokens);
			root.add("options", options);
		} else {
			root.addProperty("temperature", temperature);
			root.addProperty("max_tokens", maxTokens);
		}

		return GSON.toJson(root);
	}

	private String extractContent(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

			if (root.has("message")) {
				JsonObject message = root.getAsJsonObject("message");
				if (message != null && message.has("content")) {
					return message.get("content").getAsString();
				}
			}

			if (root.has("choices")) {
				JsonArray choices = root.getAsJsonArray("choices");
				if (choices != null && !choices.isEmpty()) {
					JsonObject first = choices.get(0).getAsJsonObject();
					if (first.has("message")) {
						JsonObject message = first.getAsJsonObject("message");
						if (message.has("content")) {
							return message.get("content").getAsString();
						}
					}
				}
			}

			return null;
		} catch (Exception e) {
			return null;
		}
	}
}
