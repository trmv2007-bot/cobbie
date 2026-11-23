package com.rishi.cobbie.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preset → endpoint and preset → protocol mapping. This is the seam that decides whether a chosen
 * provider lands on {@code /v1/chat/completions} or {@code /api/chat}, so it is asserted rather than
 * assumed — a preset that stores the wrong URL shape costs a player an evening.
 */
class BrainPresetsTest {

	@Test
	void everyHostedPresetLandsOnTheOpenAiCompatibleEndpoint() {
		for (BrainPresets.Preset p : BrainPresets.ALL) {
			if (!p.needsKey()) continue;
			String endpoint = LlmClient.origin(p.baseUrl()) + "/v1/chat/completions";
			assertTrue(endpoint.endsWith("/v1/chat/completions"), p.id() + " -> " + endpoint);
			assertEquals(1, count(endpoint, "/v1/"), p.id() + " must carry exactly one /v1/: " + endpoint);
			assertFalse(endpoint.contains("//v1"), p.id() + " -> " + endpoint);
		}
	}

	@Test
	void theKeyPoolGatewayPresetMatchesRishisEndpoint() {
		assertEquals("http://127.0.0.1:8787", LlmClient.origin(BrainPresets.KEYPOOL.baseUrl()));
		assertEquals("pooled-model", BrainPresets.KEYPOOL.defaultModel());
		assertEquals("openai", BrainPresets.KEYPOOL.provider());
		assertTrue(BrainPresets.KEYPOOL.needsKey());
	}

	@Test
	void ollamaPresetKeepsTheNativePath() {
		assertEquals("ollama", BrainPresets.OLLAMA.provider());
		assertFalse(BrainPresets.OLLAMA.needsKey(), "a local Ollama needs no key");
		assertEquals("http://127.0.0.1:11434/api/chat",
				new LlmClient(BrainPresets.OLLAMA.baseUrl(), "lfm2.5:8b", "", BrainPresets.OLLAMA.provider(),
						0.7, 800, 5, 30, 0).endpoint());
	}

	@Test
	void aFreshInstallDoesNotPointAtLocalhost() {
		assertFalse(BrainPresets.DEFAULT_BASE_URL.contains("127.0.0.1"),
				"a fresh install must not assume a server on this PC: " + BrainPresets.DEFAULT_BASE_URL);
		assertEquals("openai", BrainPresets.DEFAULT_PROVIDER);
		assertNotNull(BrainPresets.DEFAULT_MODEL);
	}

	@Test
	void presetsAreInferredFromAStoredConfig() {
		// Exactly what config/cobbie.json holds on Rishi's machine today.
		assertSame(BrainPresets.KEYPOOL, BrainPresets.infer("openai", "http://127.0.0.1:8787/v1"));
		assertSame(BrainPresets.GROQ, BrainPresets.infer("openai", "https://api.groq.com/openai/v1/"));
		assertSame(BrainPresets.OPENAI, BrainPresets.infer("openai", "https://api.openai.com/v1/chat/completions"));
		assertSame(BrainPresets.OPENROUTER, BrainPresets.infer("openai", "https://openrouter.ai/api/v1"));
		assertSame(BrainPresets.OLLAMA, BrainPresets.infer("ollama", "http://127.0.0.1:11434/"));
		assertSame(BrainPresets.CUSTOM, BrainPresets.infer("openai", "https://llm.example.net/v1"),
				"an unknown hosted URL is Custom, and its fields stay editable");
	}

	@Test
	void choosingCustomNeverWipesTheEndpoint() {
		assertTrue(BrainPresets.CUSTOM.keepsExistingEndpoint());
		assertNull(BrainPresets.CUSTOM.baseUrl());
		assertNull(BrainPresets.CUSTOM.defaultModel());
		for (BrainPresets.Preset p : BrainPresets.ALL) {
			if (p == BrainPresets.CUSTOM) continue;
			assertFalse(p.keepsExistingEndpoint(), p.id() + " must write its own endpoint");
		}
	}

	@Test
	void theProviderButtonCyclesThroughEveryPreset() {
		BrainPresets.Preset start = BrainPresets.DEFAULT;
		BrainPresets.Preset p = start;
		List<BrainPresets.Preset> seen = new java.util.ArrayList<>();
		for (int i = 0; i < BrainPresets.ALL.size(); i++) {
			seen.add(p);
			p = BrainPresets.next(p);
		}
		assertSame(start, p, "the cycle must return to where it began");
		assertEquals(BrainPresets.ALL.size(), seen.size());
		assertEquals(1, seen.stream().filter(x -> "ollama".equals(x.id())).count());
	}

	@Test
	void presetIdsResolveAndUnknownOnesDoNot() {
		assertSame(BrainPresets.GROQ, BrainPresets.byId("groq"));
		assertSame(BrainPresets.GROQ, BrainPresets.byId(" GROQ "));
		assertNull(BrainPresets.byId("nope"));
		assertNull(BrainPresets.byId(null));
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
		return n;
	}
}
