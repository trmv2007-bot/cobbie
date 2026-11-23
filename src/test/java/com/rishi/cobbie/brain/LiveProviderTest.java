package com.rishi.cobbie.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live provider path: one real chat turn and the real connection test, against whichever
 * OpenAI-compatible endpoint this machine is configured for.
 *
 * <p>Deliberately NOT part of the hermetic suite — a unit run must never depend on a key or on a
 * server being up. Opt in with:
 * <pre>
 *   ./gradlew test --tests '*LiveProviderTest*' -Dcobbie.live=1
 * </pre>
 * Settings come from {@code COBBIE_LIVE_BASE}/{@code COBBIE_LIVE_KEY}/{@code COBBIE_LIVE_MODEL},
 * or from the gitignored {@code config/cobbie.json} on this machine. The key is only ever handed to
 * the {@code Authorization} header: no assertion message, test name or log line prints it.
 */
class LiveProviderTest {

	private static String base;
	private static String model;
	private static String key;

	@BeforeAll
	static void resolveProvider() {
		assumeTrue("1".equals(System.getProperty("cobbie.live", "0")),
				"live provider checks need -Dcobbie.live=1");
		base = env("COBBIE_LIVE_BASE");
		model = env("COBBIE_LIVE_MODEL");
		key = env("COBBIE_LIVE_KEY");
		if (base == null || key == null || model == null) readMinecraftConfig();
		assumeTrue(key != null && !key.isBlank(), "no key available for a live check");
		assumeTrue(base != null && !base.isBlank(), "no base URL available for a live check");
		assumeTrue(model != null && !model.isBlank(), "no model available for a live check");
	}

	private static String env(String name) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? null : v.trim();
	}

	/** The mod's own config on this PC — the same file the game reads. Never printed. */
	private static void readMinecraftConfig() {
		String appData = System.getenv("APPDATA");
		if (appData == null || appData.isBlank()) return;
		Path file = Path.of(appData, ".minecraft", "config", "cobbie.json");
		if (!Files.exists(file)) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (base == null && o.has("base_url")) base = o.get("base_url").getAsString().trim();
			if (model == null && o.has("model")) model = o.get("model").getAsString().trim();
			if (key == null && o.has("api_key")) key = o.get("api_key").getAsString().trim();
		} catch (Exception ignored) {
			// A malformed local config simply means no live check.
		}
	}

	private static LlmClient client(int readTimeoutSeconds) {
		return new LlmClient(base, model, key, "openai", 0.7, 800, 5,
				readTimeoutSeconds, 0);
	}

	@Test
	void oneRealChatTurnComesBackAsText() throws Exception {
		LlmClient.Result r = client(60)
				.completeOnce("You are Cobbie, a friendly Minecraft companion. Answer in one short sentence.",
						"Say hello to your friend.")
				.get(90, TimeUnit.SECONDS);

		assertTrue(r.success(), "live chat failed: " + r.error());
		assertNotNull(r.content());
		assertFalse(r.content().isBlank());
		System.out.println("[live] chat ok: model=" + model + " chars=" + r.content().length()
				+ " reasoningTokens=" + r.reasoningTokens() + " reply=" + firstLine(r.content()));
	}

	@Test
	void aJsonOnlyAnswerIsStillParsedAsContent() throws Exception {
		// The shape the brain actually asks for; proves the reply is text we can parse downstream.
		LlmClient.Result r = client(60)
				.completeOnce("Reply with compact JSON only: {\"say\":\"...\"}.",
						"Greet me, JSON only.")
				.get(90, TimeUnit.SECONDS);

		assertTrue(r.success(), "live JSON turn failed: " + r.error());
		assertFalse(r.content().isBlank());
		System.out.println("[live] json turn ok: " + firstLine(r.content()));
	}

	@Test
	void theConnectionTestSaysReadyOnAWorkingEndpoint() throws Exception {
		ConnectionTest.Report report = ConnectionTest.run(client(60), "live").get(90, TimeUnit.SECONDS);

		assertEquals(ConnectionTest.Outcome.READY, report.outcome(), report.line());
		assertTrue(report.millis() > 0);
		System.out.println("[live] connection test: " + report.line());
	}

	@Test
	void aBadKeyIsReportedAsRejectedNotAsBroken() throws Exception {
		LlmClient wrong = new LlmClient(base, model, "sk-definitely-not-the-key-000", "openai",
				0.7, 64, 5, 20, 0);
		ConnectionTest.Report report = ConnectionTest.run(wrong, "live").get(60, TimeUnit.SECONDS);

		assertEquals(ConnectionTest.Outcome.KEY_REJECTED, report.outcome(), report.line());
		System.out.println("[live] bad key: " + report.line());
	}

	@Test
	void anUnknownModelIsReportedAsMissing() throws Exception {
		LlmClient wrong = new LlmClient(base, "definitely-not-a-model-000", key, "openai",
				0.7, 64, 5, 20, 0);
		ConnectionTest.Report report = ConnectionTest.run(wrong, "live").get(60, TimeUnit.SECONDS);

		assertEquals(ConnectionTest.Outcome.MODEL_NOT_FOUND, report.outcome(), report.line());
		System.out.println("[live] bad model: " + report.line());
	}

	@Test
	void aDeadPortIsReportedAsUnreachable() throws Exception {
		LlmClient dead = new LlmClient("http://127.0.0.1:1/v1", model, key, "openai",
				0.7, 64, 2, 5, 0);
		ConnectionTest.Report report = ConnectionTest.run(dead, "live").get(60, TimeUnit.SECONDS);

		assertEquals(ConnectionTest.Outcome.UNREACHABLE, report.outcome(), report.line());
		System.out.println("[live] dead port: " + report.line());
	}

	private static String firstLine(String text) {
		String s = text.replaceAll("\\s+", " ").trim();
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
