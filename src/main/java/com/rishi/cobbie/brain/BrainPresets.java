package com.rishi.cobbie.brain;

import java.util.List;
import java.util.Locale;

/**
 * The providers Cobbie knows how to talk to, as data.
 *
 * <p>Three rules, all so the surface cannot drift away from the transport:
 * <ul>
 *   <li>Every hosted entry stores {@code provider = "openai"} — they all speak
 *       {@code POST /v1/chat/completions} with a bearer key, which is the one code path
 *       {@link LlmClient} implements per vendor-agnostic protocol. Ollama keeps its own path.</li>
 *   <li>{@code baseUrl} is stored the way a person would paste it (ending in {@code /v1}). The
 *       client's {@link LlmClient#origin} stripper then removes the paste and the endpoint is
 *       appended exactly once — including for a gateway mounted at a path like {@code /openai}.</li>
 *   <li>A preset with a {@code null} URL or model is a "keep what is there" entry: choosing Custom
 *       must never wipe a URL someone just typed.</li>
 * </ul>
 *
 * <p>Nothing here is persisted. The preset is inferred from {@code provider} plus {@code base_url},
 * so a hand-edited config still shows the right entry and there is no second source of truth to get
 * out of sync.
 */
public final class BrainPresets {

	/** One selectable provider: what to write into the config, and where its key comes from. */
	public record Preset(String id, String label, String provider, String baseUrl, String defaultModel,
			String keyHint) {

		/** True when this entry leaves the URL and model alone (the Custom entry). */
		public boolean keepsExistingEndpoint() {
			return baseUrl == null;
		}

		/** True when the entry needs a bearer key at all. Ollama on localhost does not. */
		public boolean needsKey() {
			return !"ollama".equals(provider);
		}
	}

	public static final Preset GROQ = new Preset("groq", "Groq", "openai",
			"https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "key from console.groq.com/keys");

	public static final Preset OPENAI = new Preset("openai", "OpenAI", "openai",
			"https://api.openai.com/v1", "gpt-5.4-mini", "key from platform.openai.com/api-keys");

	public static final Preset OPENROUTER = new Preset("openrouter", "OpenRouter", "openai",
			"https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct", "key from openrouter.ai/keys");

	/** A gateway on this PC that forwards to hosted models — inference still happens elsewhere. */
	public static final Preset KEYPOOL = new Preset("keypool", "KeyPool gateway", "openai",
			"http://127.0.0.1:8787/v1", "pooled-model", "key from your gateway");

	public static final Preset CUSTOM = new Preset("custom", "Custom", "openai",
			null, null, "key from your provider");

	public static final Preset OLLAMA = new Preset("ollama", "Ollama (this PC)", "ollama",
			"http://127.0.0.1:11434", "lfm2.5:8b", "Ollama needs no key");

	/** Cycle order for the Provider button: hosted first, local last. */
	public static final List<Preset> ALL = List.of(GROQ, OPENAI, OPENROUTER, KEYPOOL, CUSTOM, OLLAMA);

	/** What a fresh install gets: a hosted provider, so nothing is probed on localhost. */
	public static final Preset DEFAULT = GROQ;

	public static final String DEFAULT_PROVIDER = DEFAULT.provider();
	public static final String DEFAULT_BASE_URL = DEFAULT.baseUrl();
	public static final String DEFAULT_MODEL = DEFAULT.defaultModel();

	private BrainPresets() {}

	public static Preset byId(String id) {
		if (id == null) return null;
		String want = id.trim().toLowerCase(Locale.ROOT);
		for (Preset p : ALL) {
			if (p.id().equals(want)) return p;
		}
		return null;
	}

	/**
	 * Which entry a stored config corresponds to. Ollama is decided by provider alone (the wire
	 * format is what matters, not the host); hosted entries are matched on the normalised origin so
	 * ".../v1", ".../v1/" and ".../v1/chat/completions" all resolve to the same preset.
	 */
	public static Preset infer(String provider, String baseUrl) {
		String prov = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
		if (!prov.startsWith("openai")) return OLLAMA;
		String origin = LlmClient.origin(baseUrl).toLowerCase(Locale.ROOT);
		for (Preset p : ALL) {
			if (p.baseUrl() == null) continue;
			if (LlmClient.origin(p.baseUrl()).toLowerCase(Locale.ROOT).equals(origin)) return p;
		}
		return CUSTOM;
	}

	/** The next entry in the cycle, wrapping at the end. Unknown input starts at the default. */
	public static Preset next(Preset current) {
		int idx = current == null ? -1 : ALL.indexOf(current);
		return ALL.get((idx + 1) % ALL.size());
	}
}
