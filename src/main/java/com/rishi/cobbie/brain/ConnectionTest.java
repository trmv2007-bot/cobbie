package com.rishi.cobbie.brain;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The "test connection" button's brain: one real chat request, classified into the four outcomes a
 * player can act on.
 *
 * <p>A real completion rather than a model listing, because that is the thing that actually has to
 * work when he speaks. It distinguishes:
 * <ul>
 *   <li>{@link Outcome#READY} — the endpoint answered and the configured model produced text.</li>
 *   <li>{@link Outcome#KEY_REJECTED} — HTTP 401/403: wrong, missing or expired key.</li>
 *   <li>{@link Outcome#MODEL_NOT_FOUND} — HTTP 404: the model name or the endpoint path is wrong.</li>
 *   <li>{@link Outcome#UNREACHABLE} — connection refused, unknown host, timeout: nothing is
 *       listening. For a gateway on this PC that is a normal state, not an error to panic over.</li>
 * </ul>
 * Any other answer (400/429/500) is reported as {@link Outcome#SERVER_ERROR} with the provider's own
 * words, because guessing would be worse than saying "it answered 429".
 *
 * <p>Wording reuses {@link LlmClient.Failure}, so the button and the in-game fallback reply never
 * disagree about what happened.
 */
public final class ConnectionTest {

	public enum Outcome {
		READY, KEY_REJECTED, MODEL_NOT_FOUND, UNREACHABLE, SERVER_ERROR
	}

	/** {@code reasoningTokens} is what the model spent thinking before answering (0 if unreported). */
	public record Report(Outcome outcome, String line, long millis, int reasoningTokens) {
		public boolean healthy() {
			return outcome == Outcome.READY;
		}
	}

	private static final String PROBE_SYSTEM =
			"You are the connection check for a Minecraft companion mod. Answer in one word.";
	private static final String PROBE_USER = "Reply with the single word: ready";

	private ConnectionTest() {}

	/**
	 * Run the probe off the game thread. {@code presetLabel} is only used to word the advice
	 * ("check the key for Groq"), never sent anywhere.
	 */
	public static CompletableFuture<Report> run(LlmClient client, String presetLabel) {
		if (client == null) {
			return CompletableFuture.completedFuture(
					new Report(Outcome.SERVER_ERROR, "no AI client yet — press Save, then Test again", 0, 0));
		}
		long start = System.nanoTime();
		return client.completeOnce(PROBE_SYSTEM, PROBE_USER).handle((result, thrown) -> {
			long millis = (System.nanoTime() - start) / 1_000_000L;
			LlmClient.Result r = thrown != null
					? LlmClient.Result.failure(thrown.getClass().getSimpleName() + ": " + thrown.getMessage())
					: result;
			Outcome outcome = classify(r);
			return new Report(outcome, line(outcome, r, client.model(), presetLabel, millis), millis,
					r == null ? 0 : r.reasoningTokens());
		});
	}

	/** Pure classification, unit-tested against the exact error strings the client produces. */
	public static Outcome classify(LlmClient.Result result) {
		if (result == null) return Outcome.SERVER_ERROR;
		if (result.success()) return Outcome.READY;
		return switch (result.kind()) {
			case KEY_REJECTED -> Outcome.KEY_REJECTED;
			case MODEL_NOT_FOUND -> Outcome.MODEL_NOT_FOUND;
			case UNREACHABLE, TIMEOUT -> Outcome.UNREACHABLE;
			case SERVER, NONE -> Outcome.SERVER_ERROR;
		};
	}

	private static String line(Outcome outcome, LlmClient.Result result, String model,
			String presetLabel, long millis) {
		String preset = presetLabel == null || presetLabel.isBlank() ? "this provider" : presetLabel;
		return switch (outcome) {
			case READY -> "ready — " + model + " answered in " + millis + " ms"
					+ (result.reasoningTokens() > 0
							? " (" + result.reasoningTokens() + " reasoning tokens spent first)" : "");
			case KEY_REJECTED -> LlmClient.Failure.KEY_REJECTED.playerLine()
					+ " — check the API key for " + preset;
			case MODEL_NOT_FOUND -> LlmClient.Failure.MODEL_NOT_FOUND.playerLine()
					+ " — \"" + model + "\" is not served at this endpoint";
			case UNREACHABLE -> LlmClient.Failure.UNREACHABLE.playerLine()
					+ " — check base_url, and that the server" + hintFor(preset) + " is running";
			case SERVER_ERROR -> LlmClient.Failure.SERVER.playerLine()
					+ budgetHint(result) + " — " + providerWords(result);
		};
	}

	private static String hintFor(String preset) {
		return preset.toLowerCase(Locale.ROOT).contains("gateway") ? " or gateway" : "";
	}

	/** The reasoning-model trap: the budget was spent thinking, so no visible text came back. */
	private static String budgetHint(LlmClient.Result result) {
		if (result != null && result.error() != null
				&& result.error().toLowerCase(Locale.ROOT).contains("reasoning tokens")) {
			return " — it spent its whole budget thinking, raise Max Tokens";
		}
		return "";
	}

	/** The provider's own message, trimmed to something that fits on the status line. */
	private static String providerWords(LlmClient.Result result) {
		if (result == null || result.error() == null || result.error().isBlank()) return "no answer";
		String e = result.error().replaceAll("\\s+", " ").trim();
		return e.length() > 90 ? e.substring(0, 90) + "…" : e;
	}
}
