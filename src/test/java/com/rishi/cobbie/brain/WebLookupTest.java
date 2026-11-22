package com.rishi.cobbie.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing the wiki's answer, without touching the network. */
class WebLookupTest {

	@Test
	void digestStripsMarkupAndKeepsTheUsefulWords() {
		String body = "{\"query\":{\"search\":["
				+ "{\"title\":\"Obsidian\",\"snippet\":\"<span class=\\\"searchmatch\\\">Obsidian</span> is a block.\"},"
				+ "{\"title\":\"Nether\",\"snippet\":\"Found in the &quot;nether&quot;.&#160;Lava.\"}]}}";
		String text = WebLookup.digest(body, "obsidian");

		assertTrue(text.contains("Obsidian"), text);
		assertTrue(text.contains("is a block"), text);
		assertFalse(text.contains("<span"), "markup must not survive: " + text);
		assertFalse(text.contains("&quot;"), text);
		assertTrue(text.length() <= 600, "digest must stay small: " + text.length());
	}

	@Test
	void emptyResultsSaySoInsteadOfReturningNothing() {
		String text = WebLookup.digest("{\"query\":{\"search\":[]}}", "zzzz");
		assertTrue(text.contains("nothing on the wiki"), text);
	}

	@Test
	void nonsenseBodyDoesNotThrow() {
		assertTrue(WebLookup.digest("not json at all", "x").contains("could not read"));
	}
}
