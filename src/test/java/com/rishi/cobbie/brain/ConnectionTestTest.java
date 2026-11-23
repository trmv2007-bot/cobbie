package com.rishi.cobbie.brain;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Test Connection" button, against a local server that plays each failure: 200, 401, 404 and
 * nothing listening at all. The point is that the button never guesses — a player reads the outcome
 * and knows which field to fix.
 */
class ConnectionTestTest {

	private static HttpServer server;
	private static String base;
	private static volatile int status = 200;
	private static volatile String body = "{\"choices\":[{\"message\":{\"content\":\"ready\"}}]}";
	private static final AtomicInteger calls = new AtomicInteger();

	@BeforeAll
	static void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			calls.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	@AfterAll
	static void stop() {
		if (server != null) server.stop(0);
	}

	private static LlmClient client(String base, String model, String key, int readTimeoutSeconds) {
		return new LlmClient(base, model, key, "openai", 0.7, 800, 2, readTimeoutSeconds, 0);
	}

	private static ConnectionTest.Report await(CompletableFuture<ConnectionTest.Report> future) throws Exception {
		return future.get(30, TimeUnit.SECONDS);
	}

	@Test
	void aWorkingModelIsReady() throws Exception {
		status = 200;
		body = "{\"choices\":[{\"message\":{\"content\":\"ready\"}}],"
				+ "\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":120}}}";

		ConnectionTest.Report r = await(ConnectionTest.run(client(base, "pooled-model", "k", 10), "KeyPool gateway"));

		assertEquals(ConnectionTest.Outcome.READY, r.outcome(), r.line());
		assertTrue(r.healthy());
		assertTrue(r.line().contains("pooled-model"), r.line());
		assertTrue(r.line().contains("120 reasoning tokens"), "a reasoner should say what it spent: " + r.line());
		assertEquals(120, r.reasoningTokens());
	}

	@Test
	void aRejectedKeySaysTheKeyIsTheProblem() throws Exception {
		status = 401;
		body = "{\"detail\":\"Invalid local gateway API key\"}";

		ConnectionTest.Report r = await(ConnectionTest.run(client(base, "pooled-model", "bad", 10), "Groq"));

		assertEquals(ConnectionTest.Outcome.KEY_REJECTED, r.outcome(), r.line());
		assertTrue(r.line().contains("rejected my key"), r.line());
		assertTrue(r.line().contains("Groq"), "the advice must name the provider: " + r.line());
	}

	@Test
	void anUnknownModelSaysTheModelIsTheProblem() throws Exception {
		status = 404;
		body = "{\"detail\":\"Unknown model alias: nope. Configured: pooled-model\"}";

		ConnectionTest.Report r = await(ConnectionTest.run(client(base, "nope", "k", 10), "KeyPool gateway"));

		assertEquals(ConnectionTest.Outcome.MODEL_NOT_FOUND, r.outcome(), r.line());
		assertTrue(r.line().contains("could not find my model"), r.line());
		assertTrue(r.line().contains("nope"), r.line());
	}

	@Test
	void nothingListeningIsUnreachableAndSaysWhichServer() throws Exception {
		String dead = "http://127.0.0.1:" + (server.getAddress().getPort() + 1) + "/v1";

		ConnectionTest.Report r = await(ConnectionTest.run(client(dead, "pooled-model", "k", 5), "KeyPool gateway"));

		assertEquals(ConnectionTest.Outcome.UNREACHABLE, r.outcome(), r.line());
		assertTrue(r.line().contains("unreachable"), r.line());
		assertTrue(r.line().contains("gateway"), "it must point at the gateway, not at the GPU: " + r.line());
	}

	@Test
	void aBudgetStarvedReasonerIsDiagnosedRatherThanCalledBroken() throws Exception {
		status = 200;
		body = "{\"choices\":[{\"message\":{\"content\":null}}],"
				+ "\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":16}}}";

		ConnectionTest.Report r = await(ConnectionTest.run(client(base, "pooled-model", "k", 10), "KeyPool gateway"));

		assertEquals(ConnectionTest.Outcome.SERVER_ERROR, r.outcome(), r.line());
		assertTrue(r.line().toLowerCase().contains("raise max tokens"), r.line());
	}

	@Test
	void classificationIsTheClosedVocabularyAndNothingElse() {
		assertEquals(ConnectionTest.Outcome.READY, ConnectionTest.classify(LlmClient.Result.success("hi")));
		assertEquals(ConnectionTest.Outcome.KEY_REJECTED,
				ConnectionTest.classify(LlmClient.Result.failure("HTTP 403 forbidden")));
		assertEquals(ConnectionTest.Outcome.MODEL_NOT_FOUND,
				ConnectionTest.classify(LlmClient.Result.failure("HTTP 404")));
		assertEquals(ConnectionTest.Outcome.UNREACHABLE,
				ConnectionTest.classify(LlmClient.Result.failure("ConnectException: Connection refused")));
		assertEquals(ConnectionTest.Outcome.UNREACHABLE,
				ConnectionTest.classify(LlmClient.Result.failure("HttpTimeoutException: request timed out")));
		assertEquals(ConnectionTest.Outcome.SERVER_ERROR,
				ConnectionTest.classify(LlmClient.Result.failure("HTTP 429")));
		assertEquals(ConnectionTest.Outcome.SERVER_ERROR, ConnectionTest.classify(null));
	}

	@Test
	void aMissingClientIsReportedInsteadOfThrowing() throws Exception {
		ConnectionTest.Report r = await(ConnectionTest.run(null, "Groq"));
		assertNotNull(r.line());
		assertFalse(r.healthy());
	}
}
