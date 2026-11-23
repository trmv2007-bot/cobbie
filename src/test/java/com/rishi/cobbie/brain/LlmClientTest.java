package com.rishi.cobbie.brain;

import com.rishi.cobbie.action.ActionVocabulary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests against a real HTTP server on localhost — no Ollama needed, no mocks.
 *
 * <p>This is what actually broke in 0.1.0 (404 on {@code /v1/chat/completions}, a null
 * {@code Authorization} header Ollama rejects) and what the audit demanded proof of in 0.4.0.
 */
class LlmClientTest {

	private static HttpServer server;
	private static String base;
	private static final List<String> paths = new CopyOnWriteArrayList<>();
	private static final List<String> authHeaders = new CopyOnWriteArrayList<>();
	private static final List<String> bodies = new CopyOnWriteArrayList<>();
	private static volatile int status = 200;
	private static volatile String response = "{\"message\":{\"content\":\"hello\"}}";
	private static final java.util.Deque<String[]> script = new java.util.concurrent.ConcurrentLinkedDeque<>();
	private static volatile int calls;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", LlmClientTest::handle);
		server.setExecutor(null);
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	@AfterAll
	static void stopServer() {
		if (server != null) server.stop(0);
	}

	private static void handle(HttpExchange exchange) throws IOException {
		calls++;
		paths.add(exchange.getRequestURI().getPath());
		authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
		bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		int code = status;
		String body = response;
		String[] scripted = script.poll();
		if (scripted != null) {
			code = Integer.parseInt(scripted[0]);
			body = scripted[1];
		}
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(code, out.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(out);
		}
	}

	private static void reset(int code, String body) {
		paths.clear();
		authHeaders.clear();
		bodies.clear();
		script.clear();
		calls = 0;
		status = code;
		response = body;
	}

	private static LlmClient ollama(String key, int retries) {
		return new LlmClient(base, "testmodel", key, "ollama", 0.4, 128, 2, 5, retries);
	}

	private static LlmClient openai(String key, int retries) {
		return new LlmClient(base, "pooled-model", key, "openai", 0.4, 128, 2, 5, retries);
	}

	private static LlmClient.Result await(java.util.concurrent.CompletableFuture<LlmClient.Result> future)
			throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}

	// ---- reasoning leakage ----

	@Test
	void unterminatedThinkingBlockNeverReachesTheAnswer() throws Exception {
		// Seen live on lfm2.5:8b once it had real world knowledge to reason about: the token limit
		// cut the reply mid-thought, leaving "<​think>..." with no closing tag around the JSON.
		reset(200, "{\"message\":{\"content\":\"<​think> We need to answer as Cobbie. "
				+ "{\\\"answer\\\":\\\"Hello there!\\\",\\\"actions\\\":[]}\"}}");
		LlmClient.Result r = await(ollama("", 1).completeOnce("system", "hi"));

		assertTrue(r.success(), r.error());
		assertFalse(r.content().contains("think"), "reasoning leaked: " + r.content());
		assertFalse(r.content().contains("We need to answer"), "reasoning leaked: " + r.content());
		assertTrue(r.content().startsWith("{"), "the JSON payload must survive: " + r.content());
	}

	@Test
	void closedThinkingBlockIsRemovedEntirely() throws Exception {
		reset(200, "{\"message\":{\"content\":\"<​think>private​ musings<​/think>"
				+ "{\\\"answer\\\":\\\"Hi\\\",\\\"actions\\\":[]}\"}}");
		LlmClient.Result r = await(ollama("", 1).completeOnce("system", "hi"));

		assertTrue(r.success(), r.error());
		assertFalse(r.content().contains("private"), "reasoning leaked: " + r.content());
		assertTrue(r.content().contains("\"answer\""), r.content());
	}

	// ---- endpoint selection ----

	@Test
	void ollamaUsesNativeApiChatEndpoint() throws Exception {
		reset(200, "{\"message\":{\"content\":\"Sure, boss\"}}");
		LlmClient.Result r = await(
				ollama("", 0).completeOnce("system", "hi"));

		assertTrue(r.success(), r.error());
		assertEquals("Sure, boss", r.content());
		assertEquals(List.of("/api/chat"), paths);
	}

	@Test
	void openAiProviderUsesCompatibleEndpointAndChoicesParsing() throws Exception {
		reset(200, "{\"choices\":[{\"message\":{\"content\":\"from openai\"}}]}");
		LlmClient client = new LlmClient(base, "gpt-ish", "secret", "openai", 0.5, 64, 2, 5, 0);
		LlmClient.Result r = await(client.completeOnce("s", "u"));

		assertTrue(r.success(), r.error());
		assertEquals("from openai", r.content());
		assertEquals(List.of("/v1/chat/completions"), paths);
		assertEquals("Bearer secret", authHeaders.get(0));
	}

	@Test
	void openAiPayloadUsesTopLevelBudgetAndNoOllamaFields() throws Exception {
		// Every hosted preset (Groq, OpenAI, OpenRouter, a local gateway) shares this one code path.
		reset(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
		await(openai("k", 0).completeOnce("be nice", "hello"));
		String body = bodies.get(0);

		assertTrue(body.contains("\"model\":\"pooled-model\""), body);
		assertTrue(body.contains("\"max_tokens\":128"), body);
		assertTrue(body.contains("\"temperature\":0.4"), body);
		assertFalse(body.contains("\"options\""), "options is Ollama-only: " + body);
		assertFalse(body.contains("\"think\""), "think is Ollama-only: " + body);
	}

	@Test
	void aReasoningModelThatWantsMaxCompletionTokensIsRetriedWithIt() throws Exception {
		// OpenAI's reasoning models answer 400 to max_tokens and name the field they want. That is a
		// protocol correction like the Ollama "think" case, so it must be retried, once.
		reset(200, "{\"choices\":[{\"message\":{\"content\":\"answered\"}}]}");
		script.add(new String[]{"400", "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not "
				+ "supported with this model. Use 'max_completion_tokens' instead.\"}}"});
		LlmClient.Result r = await(openai("secret", 0).completeOnce("s", "u"));

		assertTrue(r.success(), r.error());
		assertEquals(2, calls);
		assertTrue(bodies.get(0).contains("\"max_tokens\""), bodies.get(0));
		assertTrue(bodies.get(1).contains("\"max_completion_tokens\":128"), bodies.get(1));
		assertFalse(bodies.get(1).contains("\"max_tokens\""), "the rejected key must be gone: " + bodies.get(1));
	}

	// ---- usage / reasoning budget ----

	@Test
	void reasoningTokensAreCarriedFromTheUsageBlock() throws Exception {
		// Measured live on the KeyPool gateway: 238 of 292 completion tokens were reasoning. At a
		// small budget that is the whole reply, so the number has to survive into the Result.
		reset(200, "{\"choices\":[{\"message\":{\"content\":\"ready\"}}],"
				+ "\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":238}}}");
		LlmClient.Result r = await(openai("k", 0).completeOnce("s", "u"));

		assertTrue(r.success(), r.error());
		assertEquals(238, r.reasoningTokens());
	}

	@Test
	void emptyReplyAfterThinkingSaysHowManyTokensWentToThinking() throws Exception {
		reset(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}],"
				+ "\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":16}}}");
		LlmClient.Result r = await(openai("k", 0).completeOnce("s", "u"));

		assertFalse(r.success());
		assertEquals(16, r.reasoningTokens());
		assertTrue(r.error().contains("reasoning tokens"), "the empty reply must be diagnosable: " + r.error());
		assertFalse(r.retryable(),
				"a budget-starved reply must be named, not retried into the same wall: " + r.error());
	}

	// ---- the key never travels ----

	@Test
	void aKeyEchoedInAProviderErrorBodyIsRedacted() throws Exception {
		// Gateways do print the presented credential in a 401 body. That text reaches chat and the
		// log, so it must be scrubbed before it leaves the client.
		reset(401, "{\"error\":\"Invalid API key sk-live-SUPERSECRET-123\"}");
		LlmClient.Result r = await(openai("sk-live-SUPERSECRET-123", 0).completeOnce("s", "u"));

		assertFalse(r.success());
		assertFalse(r.error().contains("SUPERSECRET"), r.error());
		assertTrue(r.error().contains("401"), r.error());
		assertEquals(LlmClient.Failure.KEY_REJECTED, r.kind());
	}

	@Test
	void failureKindsUseOneClosedVocabulary() {
		assertEquals(LlmClient.Failure.KEY_REJECTED,
				LlmClient.Result.failure("HTTP 401 {\"detail\":\"bad key\"}").kind());
		assertEquals(LlmClient.Failure.MODEL_NOT_FOUND,
				LlmClient.Result.failure("HTTP 404 Unknown model alias").kind());
		assertEquals(LlmClient.Failure.UNREACHABLE,
				LlmClient.Result.failure("ConnectException: Connection refused").kind());
		assertEquals(LlmClient.Failure.TIMEOUT,
				LlmClient.Result.failure("HttpTimeoutException: request timed out").kind());
		assertEquals(LlmClient.Failure.SERVER, LlmClient.Result.failure("HTTP 429").kind());
		assertEquals(LlmClient.Failure.NONE, LlmClient.Result.success("hi").kind());
	}

	@Test
	void gatewayBaseUrlKeepsExactlyOneV1Segment() {
		// Rishi's KeyPool gateway is mounted at :8787/v1, and pooled-model is served from it.
		String origin = LlmClient.origin("http://127.0.0.1:8787/v1");
		assertEquals("http://127.0.0.1:8787", origin);
		String endpoint = origin + "/v1/chat/completions";
		assertEquals("http://127.0.0.1:8787/v1/chat/completions", endpoint);
		assertEquals(1, countOccurrences(endpoint, "/v1/"), endpoint);

		assertEquals("http://127.0.0.1:8787/v1/chat/completions",
				new LlmClient("http://127.0.0.1:8787/v1", "pooled-model", "k", "openai", 0.7, 800, 5, 30, 0)
						.endpoint());
	}

	@Test
	void baseUrlSlashesNeverProduceDoubleSlashes() throws Exception {
		reset(200, "{\"message\":{\"content\":\"x\"}}");
		LlmClient noSlash = new LlmClient(base.substring(0, base.length() - 1), "m", "", "ollama", 0.5, 64, 2, 5, 0);
		LlmClient manySlash = new LlmClient(base + "//", "m", "", "ollama", 0.5, 64, 2, 5, 0);
		await(noSlash.completeOnce("s", "u"));
		await(manySlash.completeOnce("s", "u"));

		assertTrue(paths.stream().allMatch(p -> p.equals("/api/chat")), paths.toString());
	}

	// ---- authentication ----

	@Test
	void emptyApiKeySendsNoAuthorizationHeaderAtAll() throws Exception {
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		await(ollama("", 0).completeOnce("s", "u"));

		assertNull(authHeaders.get(0), "Ollama rejects an empty bearer token; the header must be absent");
	}

	@Test
	void whitespaceKeyIsTreatedAsAbsent() throws Exception {
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		await(ollama("   ", 0).completeOnce("s", "u"));
		assertNull(authHeaders.get(0));
	}

	// ---- request body shape ----

	@Test
	void ollamaOptionsCarryTemperatureAndNumPredict() throws Exception {
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		await(ollama("", 0).completeOnce("be nice", "hello"));
		String body = bodies.get(0);

		assertTrue(body.contains("\"model\":\"testmodel\""), body);
		assertTrue(body.contains("\"stream\":false"), body);
		assertTrue(body.contains("\"options\""), body);
		assertTrue(body.contains("\"num_predict\":128"), body);
		assertTrue(body.contains("\"temperature\":0.4"), body);
		assertTrue(body.contains("\"role\":\"system\""), body);
		assertTrue(body.contains("\"role\":\"user\""), body);
		assertFalse(body.contains("max_tokens"), "Ollama ignores top-level max_tokens; it belongs in options");
	}

	@Test
	void reasoningTokensAreSwitchedOffForOllama() throws Exception {
		// The live Spark model advertises "thinking" and burns ~600 hidden tokens per reply
		// (~18s on a laptop GPU). Cobbie asks for the answer only.
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		await(ollama("", 0).completeOnce("s", "u"));
		assertTrue(bodies.get(0).contains("\"think\":false"), bodies.get(0));
	}

	@Test
	void aServerThatRejectsThinkRetriesOnceWithoutIt() throws Exception {
		reset(200, "{\"message\":{\"content\":\"recovered\"}}");
		script.add(new String[]{"400", "{\"error\":\"this model does not support thinking\"}"});
		LlmClient.Result r = await(ollama("", 0).completeOnce("s", "u"));

		assertTrue(r.success(), "the think field must be dropped and the request retried: " + r.error());
		assertEquals("recovered", r.content());
		assertEquals(2, calls);
		assertTrue(bodies.get(0).contains("\"think\""), bodies.get(0));
		assertFalse(bodies.get(1).contains("\"think\""), "second attempt must not send the rejected field");
	}

	@Test
	void conversationHistoryIsSentWithItsRoles() throws Exception {
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		List<LlmClient.Msg> history = List.of(
				new LlmClient.Msg("user", "who are you"),
				new LlmClient.Msg("assistant", "I am Cobbie"),
				new LlmClient.Msg("user", "follow me"));
		await(ollama("", 0).complete("sys", history, "and now?"));
		String body = bodies.get(0);

		assertEquals(5, countOccurrences(body, "\"role\":"),
				"system + 3 history turns + the new user message: " + body);
		assertTrue(body.indexOf("I am Cobbie") > body.indexOf("who are you"),
				"history must stay chronological so the model can follow it");
		assertTrue(body.lastIndexOf("and now?") > body.lastIndexOf("follow me"));
	}

	private static int countOccurrences(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
		return n;
	}

	// ---- failure handling ----

	@Test
	void serverErrorIsRetriedUpToTheBound() throws Exception {
		reset(500, "boom");
		await(ollama("", 2).completeOnce("s", "u"));

		assertEquals(3, calls, "one attempt plus two retries for a 5xx");
	}

	@Test
	void notFoundIsReportedImmediatelyWithoutHammering() throws Exception {
		reset(404, "{\"error\":\"model not found\"}");
		LlmClient.Result r = await(ollama("", 3).completeOnce("s", "u"));

		assertFalse(r.success());
		assertTrue(r.error().contains("404"), r.error());
		assertFalse(r.retryable(), "a wrong model name will not fix itself by asking again");
		assertEquals(1, calls);
	}

	@Test
	void malformedJsonIsAFailureNotAnEmptySuccess() throws Exception {
		reset(200, "{this is not json");
		LlmClient.Result r = await(ollama("", 0).completeOnce("s", "u"));

		assertFalse(r.success());
		assertNotNull(r.error());
		assertTrue(r.friendlyError().toLowerCase().contains("ai"), r.friendlyError());
	}

	@Test
	void emptyContentIsNotTreatedAsASuccessfulAnswer() throws Exception {
		reset(200, "{\"message\":{\"content\":\"\"}}");
		LlmClient.Result r = await(ollama("", 0).completeOnce("s", "u"));
		assertFalse(r.success(), "an empty answer must never be stored as a conversation turn");
	}

	@Test
	void unreachableServerFailsWithARetryableTransportError() throws Exception {
		int port = server.getAddress().getPort();
		String dead = "http://127.0.0.1:" + (port + 1) + "/";
		LlmClient client = new LlmClient(dead, "m", "", "ollama", 0.5, 64, 1, 2, 0);
		LlmClient.Result r = client.completeOnce("s", "u").get(15, TimeUnit.SECONDS);

		assertFalse(r.success());
		assertTrue(r.retryable());
		assertTrue(r.friendlyError().toLowerCase().contains("unreachable"), r.friendlyError());
	}

	@Test
	void inFlightCounterReturnsToZero() throws Exception {
		reset(200, "{\"message\":{\"content\":\"ok\"}}");
		LlmClient client = ollama("", 0);
		await(client.completeOnce("s", "u"));
		assertEquals(0, client.inFlight());
	}

	@Test
	void discoveryParsesOllamaAndOpenAiModelLists() {
		List<String> ollama = OllamaDiscovery.parseModelNames(
				"{\"models\":[{\"name\":\"llama3:8b\"},{\"name\":\"qwq:32b\"}]}", "ollama");
		assertEquals(List.of("llama3:8b", "qwq:32b"), ollama);

		List<String> openai = OllamaDiscovery.parseModelNames(
				"{\"data\":[{\"id\":\"gpt-4o-mini\"}]}", "openai");
		assertEquals(List.of("gpt-4o-mini"), openai);

		assertTrue(OllamaDiscovery.parseModelNames("not json", "ollama").isEmpty());
		assertTrue(OllamaDiscovery.parseModelNames("{}", "ollama").isEmpty());
	}

	@Test
	void modelMatchingNeverSubstitutesADifferentModel() {
		List<String> installed = List.of("SparkLLM/Spark-X2.6-4B:q4_k_m", "lfm2.5:8b");

		assertEquals("SparkLLM/Spark-X2.6-4B:q4_k_m",
				ConnectionState.findModel("SparkLLM/Spark-X2.6-4B:q4_k_m", installed));
		assertEquals("lfm2.5:8b", ConnectionState.findModel("lfm2.5", installed),
				"a bare name may match its own :tag");
		assertNull(ConnectionState.findModel("llama3", installed),
				"never answer for a model that is not installed");
		assertNull(ConnectionState.findModel(null, installed));
		assertNull(ConnectionState.findModel("lfm2.5", List.of()));
	}

	@Test
	void aTimeoutFailsOnceInsteadOfWaitingOutASecondReadTimeout() throws Exception {
		// The whole point of the shorter default read timeout: a silent AI must produce the fallback
		// reply quickly, not after two 30-second waits.
		HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		slow.createContext("/", exchange -> {
			try {
				Thread.sleep(8000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r);
			t.setDaemon(true);
			return t;
		}));
		slow.start();
		try {
			String slowBase = "http://127.0.0.1:" + slow.getAddress().getPort() + "/";
			LlmClient client = new LlmClient(slowBase, "m", "", "ollama", 0.5, 64, 2, 5, 2);
			long start = System.nanoTime();
			LlmClient.Result r = client.completeOnce("s", "u").get(20, TimeUnit.SECONDS);
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

			assertFalse(r.success());
			assertFalse(r.retryable(), "a timeout must not be retried: the fallback reply is overdue");
			assertEquals(LlmClient.Failure.TIMEOUT, r.kind(), r.error());
			assertTrue(elapsedMs < 8000, "one timeout plus slack, never two: " + elapsedMs + "ms");
		} finally {
			slow.stop(0);
		}
	}

	@Test
	void actionVocabularyIsTheOnlyWayThrough() {
		// The runner and the parser share this list; anything outside it cannot be executed.
		assertFalse(ActionVocabulary.isAllowed("stop"));
		assertFalse(ActionVocabulary.isAllowed("op"));
		assertFalse(ActionVocabulary.isAllowed("say_command"));
		assertTrue(ActionVocabulary.allowed().size() >= 8);
	}

	@Test
	void pastedEndpointUrlsAreReducedToTheirOrigin() {
		// Real bug from the debugging session: base_url had been hand-set to ".../api/" and every
		// request then went to /api/api/chat.
		assertEquals("http://127.0.0.1:11434", LlmClient.origin("http://127.0.0.1:11434"));
		assertEquals("http://127.0.0.1:11434", LlmClient.origin("http://127.0.0.1:11434/"));
		assertEquals("http://127.0.0.1:11434", LlmClient.origin("http://127.0.0.1:11434/api/"));
		assertEquals("http://127.0.0.1:11434", LlmClient.origin("http://127.0.0.1:11434/api/chat"));
		assertEquals("https://api.openai.com", LlmClient.origin("https://api.openai.com/v1"));
		assertEquals("https://api.openai.com", LlmClient.origin("https://api.openai.com/v1/chat/completions"));
		assertEquals("http://lan:11434/ollama", LlmClient.origin("http://lan:11434/ollama/"),
				"a real context path must survive");
		assertEquals("http://127.0.0.1:11434/api/chat",
				new LlmClient("http://127.0.0.1:11434/api/", "m", "", "ollama", 0.5, 64, 2, 5, 0).endpoint());
	}
}
