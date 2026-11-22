package com.rishi.cobbie.brain;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The one hole in his world: what he cannot see, he can look up.
 *
 * <p>Queries the Minecraft wiki's public API for a search term and returns a short, plain-text digest
 * of the top results. It exists so "how do I build a starter house" or "what makes obsidian" can be
 * answered from the wiki rather than from a model's memory of Minecraft.
 *
 * <p>Deliberately narrow: one GET, a fixed host, a bounded timeout, and the result is capped before it
 * ever reaches a prompt. No general fetching, no URLs from chat, no response larger than a paragraph.
 */
public final class WebLookup {

	private static final String ENDPOINT = "https://minecraft.wiki/api.php?action=query&list=search&format=json&srlimit=3&srsearch=";
	private static final int MAX_CHARS = 600;

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	private WebLookup() {}

	/** @return a digest of the top wiki results, or an explanation of why there is none. */
	public static CompletableFuture<String> search(String query) {
		String asked = query == null ? "" : query.trim();
		if (asked.isEmpty()) return CompletableFuture.completedFuture("I need something to look up");
		final String cleaned = asked.length() > 120 ? asked.substring(0, 120) : asked;

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT + URLEncoder.encode(cleaned, StandardCharsets.UTF_8)))
				.timeout(Duration.ofSeconds(10))
				.header("Accept", "application/json")
				.header("User-Agent", "Cobbie/0.11 (Minecraft companion)")
				.GET()
				.build();

		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> response.statusCode() == 200
						? digest(response.body(), cleaned)
						: "the wiki answered HTTP " + response.statusCode())
				.exceptionally(error -> "I could not reach the wiki (" + error.getClass().getSimpleName() + ")");
	}

	/** MediaWiki search JSON in, one paragraph of plain text out. */
	static String digest(String body, String query) {
		try {
			var root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
			var search = root.getAsJsonObject("query").getAsJsonArray("search");
			if (search == null || search.isEmpty()) return "nothing on the wiki for \"" + query + "\"";

			StringBuilder out = new StringBuilder();
			for (int i = 0; i < search.size(); i++) {
				var hit = search.get(i).getAsJsonObject();
				String title = hit.has("title") ? hit.get("title").getAsString() : "";
				String snippet = hit.has("snippet") ? hit.get("snippet").getAsString() : "";
				snippet = snippet.replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&amp;", "&")
						.replace("&#160;", " ").replaceAll("\\s+", " ").trim();
				if (out.length() > 0) out.append(" | ");
				out.append(title).append(": ").append(snippet);
				if (out.length() > MAX_CHARS) break;
			}
			String text = out.toString();
			return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 1) + "~" : text;
		} catch (Exception e) {
			return "the wiki sent something I could not read";
		}
	}
}
