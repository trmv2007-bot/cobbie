package com.rishi.cobbie.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rishi.cobbie.Cobbie;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Talks to one LLM endpoint: Ollama-native ({@code POST /api/chat}) or OpenAI-compatible
 * ({@code POST /v1/chat/completions}).
 *
 * <p>Contract worth knowing before editing:
 * <ul>
 *   <li>Nothing here ever blocks the game thread — {@link #complete} returns a future immediately.</li>
 *   <li>Failures come back as {@link Result#failure}, never null and never a fake answer, so a
 *       dead server can never be mistaken for a conversation.</li>
 *   <li>The {@code Authorization} header is only attached when an API key is configured. Ollama
 *       rejects a null/empty bearer token, which is what made 0.1.0 "work but never answer".</li>
 *   <li>Retries are bounded ({@code max_retries}) and only for transport errors and 5xx/429 —
 *       a 404 (wrong model/endpoint) is reported immediately instead of hammered.</li>
 * </ul>
 */
public final class LlmClient {

	private static final Gson GSON = new Gson();

	/** One turn in the conversation. Role is {@code system}, {@code user} or {@code assistant}. */
	public record Msg(String role, String content) {}

	/**
	 * Structured outcome of a request. {@code retryable} tells the caller (and the retry loop)
	 * whether asking again could plausibly help.
	 */
	public record Result(String content, boolean success, String error, boolean retryable) {
		public static Result success(String content) {
			return new Result(content, true, null, false);
		}

		public static Result failure(String error) {
			return failure(error, false);
		}

		public static Result failure(String error, boolean retryable) {
			return new Result(null, false, error, retryable);
		}

		/** Player-safe wording: never leaks a URL, key or stack trace. */
		public String friendlyError() {
			if (error == null) return "the AI did not answer";
			String e = error.toLowerCase(Locale.ROOT);
			if (e.contains("unauthorized") || e.contains("401")) return "the AI rejected my key";
			if (e.contains("404")) return "the AI could not find my model";
			if (e.contains("timeout") || e.contains("timed out")) return "the AI took too long to answer";
			if (e.contains("refused") || e.contains("unknown host") || e.contains("connect")) {
				return "the AI server is unreachable";
			}
			return "the AI is having trouble";
		}
	}

	private final HttpClient http;
	private final URI endpoint;
	private final String model;
	private final String apiKey;
	private final String provider;
	private final double temperature;
	private final int maxTokens;
	private final int maxRetries;
	private final Duration readTimeout;
	private final AtomicInteger inFlight = new AtomicInteger();

	public LlmClient(String baseUrl, String model, String apiKey, String provider,
			double temperature, int maxTokens, int connectTimeoutSeconds, int readTimeoutSeconds,
			int maxRetries) {
		// Slash-safe AND paste-safe: people write ".../api/" or the full ".../v1/chat/completions"
		// in base_url, and appending an endpoint to that produced "/api/api/chat" 404s.
		String cleaned = origin(baseUrl);
		this.provider = (provider == null || provider.isBlank() ? "ollama" : provider.trim().toLowerCase(Locale.ROOT));
		String path = this.provider.startsWith("openai") ? "v1/chat/completions" : "api/chat";
		URI resolved;
		try {
			resolved = URI.create(cleaned + "/" + path);
		} catch (Exception badUrl) {
			Cobbie.LOGGER.error("Cobbie has an invalid base_url '{}', falling back to local Ollama", baseUrl);
			resolved = URI.create("http://127.0.0.1:11434/api/chat");
		}
		this.endpoint = resolved;
		this.model = (model == null || model.isBlank()) ? "latest" : model.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.temperature = temperature;
		this.maxTokens = maxTokens;
		this.maxRetries = Math.max(0, Math.min(3, maxRetries));
		this.readTimeout = Duration.ofSeconds(Math.max(5, readTimeoutSeconds));
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
				.build();
	}

	public String model() {
		return model;
	}

	/**
	 * Reduce a user-supplied base URL to scheme://host[:port][/context] with no trailing slash and
	 * no API path on the end, so callers can safely append their own endpoint.
	 */
	public static String origin(String baseUrl) {
		String v = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:11434" : baseUrl.trim();
		v = v.replaceAll("/+$", "");
		// Strip a pasted endpoint (…, /api, /v1, /api/chat, /v1/chat/completions, /api/tags…).
		v = v.replaceFirst("(?i)/(api|v1)(/(chat/completions|chat|tags|models))?$", "");
		return v.isEmpty() ? "http://127.0.0.1:11434" : v;
	}

	public String endpoint() {
		return endpoint.toString();
	}

	/** How many requests are outstanding right now (diagnostics / status command). */
	public int inFlight() {
		return inFlight.get();
	}

	/**
	 * Ask the model, with conversation history. Never blocks; never returns null.
	 *
	 * @param system  the personality + rules for this turn
	 * @param history previous turns, oldest first, roles {@code user}/{@code assistant}
	 * @param user    what the player just said
	 */
	public CompletableFuture<Result> complete(String system, List<Msg> history, String user) {
		JsonObject payload;
		try {
			payload = buildPayload(system, history, user);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.failure("could not build request: " + e));
		}
		return attempt(payload, 0);
	}

	/** Convenience overload for single-turn calls (connection smoke tests). */
	public CompletableFuture<Result> completeOnce(String system, String user) {
		return complete(system, List.of(), user);
	}

	// ---- retry loop ----

	private CompletableFuture<Result> attempt(JsonObject payload, int attemptNo) {
		final String body = GSON.toJson(payload);
		CompletableFuture<Result> first = sendOnce(body);
		// Anonymous class: an explicit return type here dodges the javac inference failure that
		// a lambda (.thenCompose(res -> ...)) hits when both branches are CompletableFuture<Result>.
		return first.thenCompose(new Function<Result, CompletableFuture<Result>>() {
			@Override
			public CompletableFuture<Result> apply(Result res) {
				if (res.success()) return CompletableFuture.completedFuture(res);

				// Some Ollama builds reject the "think" field outright. Drop it once and retry —
				// that is a protocol correction, not a flaky failure, so it does not eat the budget.
				if (payload.has("think") && thinksFieldWasRejected(res.error())) {
					payload.remove("think");
					Cobbie.LOGGER.info("Cobbie: this server rejects \"think\", reasoning tokens are back on");
					return attempt(payload, attemptNo);
				}
				if (!res.retryable() || attemptNo >= maxRetries) {
					return CompletableFuture.completedFuture(res);
				}
				long backoffMs = 400L * (attemptNo + 1L);
				Cobbie.LOGGER.warn("Cobbie LLM retry {}/{} in {}ms ({})", attemptNo + 1, maxRetries, backoffMs, res.error());
				return CompletableFuture
						.supplyAsync(() -> Boolean.TRUE,
								CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS))
						.thenCompose(ignored -> attempt(payload, attemptNo + 1));
			}
		});
	}

	private static boolean thinksFieldWasRejected(String error) {
		if (error == null) return false;
		String e = error.toLowerCase(Locale.ROOT);
		return e.contains("400") && e.contains("think");
	}

	private CompletableFuture<Result> sendOnce(String body) {
		HttpRequest.Builder req = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(readTimeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json");
		if (!apiKey.isEmpty()) {
			req.header("Authorization", "Bearer " + apiKey);
		}

		inFlight.incrementAndGet();
		return http.sendAsync(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
						HttpResponse.BodyHandlers.ofString())
				.<Result>handle((resp, ex) -> {
					if (ex != null) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						String msg = cause.getClass().getSimpleName() + ": " + cause.getMessage();
						return Result.failure(msg, true); // transport-level: worth one retry
					}
					int code = resp.statusCode();
					if (code == 200) {
						String content = extractContent(resp.body());
						if (content == null || content.isBlank()) {
							return Result.failure("model returned no text", false);
						}
						return Result.success(content);
					}
					if (code == 429 || code >= 500) {
						return Result.failure("HTTP " + code, true);
					}
					return Result.failure("HTTP " + code + " " + snip(resp.body()), false);
				})
				.whenComplete((res, ignored) -> inFlight.decrementAndGet());
	}

	private static String snip(String body) {
		if (body == null) return "";
		String s = body.replaceAll("\\s+", " ").trim();
		return s.length() > 120 ? s.substring(0, 120) + "..." : s;
	}

	// ---- wire format ----

	private JsonObject buildPayload(String system, List<Msg> history, String user) {
		JsonArray messages = new JsonArray();
		if (system != null && !system.isBlank()) {
			messages.add(message("system", system));
		}
		if (history != null) {
			for (Msg m : history) {
				if (m == null || m.content() == null || m.content().isBlank()) continue;
				String role = "assistant".equalsIgnoreCase(m.role()) ? "assistant" : "user";
				messages.add(message(role, m.content()));
			}
		}
		messages.add(message("user", user == null ? "" : user));

		JsonObject root = new JsonObject();
		root.addProperty("model", model);
		root.add("messages", messages);
		root.addProperty("stream", false);

		if (provider.startsWith("openai")) {
			root.addProperty("temperature", temperature);
			root.addProperty("max_tokens", maxTokens);
		} else {
			// Ollama wants its options object; a top-level temperature is silently ignored.
			JsonObject options = new JsonObject();
			options.addProperty("temperature", temperature);
			options.addProperty("num_predict", maxTokens);
			root.add("options", options);
			// Reasoning-capable models (this Spark build advertises "thinking") otherwise spend
			// hundreds of hidden tokens before answering, which on a laptop GPU is the difference
			// between 2s and 20s. Servers that reject the field are handled in attempt().
			root.addProperty("think", false);
		}
		return root;
	}

	private static JsonObject message(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	/** Reads {@code message.content} (Ollama) or {@code choices[0].message.content} (OpenAI). */
	private static String extractContent(String responseBody) {
		try {
			JsonElement parsed = JsonParser.parseString(responseBody);
			if (!parsed.isJsonObject()) return null;
			JsonObject root = parsed.getAsJsonObject();

			if (root.has("message") && root.get("message").isJsonObject()) {
				JsonObject message = root.getAsJsonObject("message");
				if (message.has("content") && message.get("content").isJsonPrimitive()) {
					return stripThinking(message.get("content").getAsString());
				}
			}
			if (root.has("choices") && root.get("choices").isJsonArray()) {
				JsonArray choices = root.getAsJsonArray("choices");
				for (JsonElement el : choices) {
					if (!el.isJsonObject()) continue;
					JsonObject choice = el.getAsJsonObject();
					if (choice.has("message") && choice.get("message").isJsonObject()) {
						JsonObject msg = choice.getAsJsonObject("message");
						if (msg.has("content") && msg.get("content").isJsonPrimitive()) {
							return stripThinking(msg.get("content").getAsString());
						}
					}
					if (choice.has("text") && choice.get("text").isJsonPrimitive()) {
						return stripThinking(choice.get("text").getAsString());
					}
				}
			}
			if (root.has("error")) {
				// 200-with-error-body: treat as no content, the caller reports a failed request.
				return null;
			}
			return null;
		} catch (Exception malformed) {
			return null;
		}
	}

	/** Remove any <think>...</think> or <thinking>...</thinking> blocks from model output. */
	private static String stripThinking(String text) {
		if (text == null) return null;
		// Remove <think>...</think> (case-insensitive, handles nested newlines)
		String out = text.replaceAll("(?is)<think>.*?</think>", "");
		// Remove <thinking>...</thinking>
		out = out.replaceAll("(?is)<thinking>.*?</thinking>", "");
		// Trim leading/trailing whitespace left behind
		return out.trim();
	}

	/** Build the history window from raw role/content pairs. */
	public static List<Msg> toMessages(List<String> roles, List<String> contents) {
		List<Msg> out = new ArrayList<>();
		int n = Math.min(roles == null ? 0 : roles.size(), contents == null ? 0 : contents.size());
		for (int i = 0; i < n; i++) {
			out.add(new Msg(roles.get(i), contents.get(i)));
		}
		return out;
	}
}
