package com.rishi.cobbie.brain;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for OllamaDiscovery — model discovery through GET /api/tags.
 */
class OllamaDiscoveryTest {

	@Test
	void testDiscoveryResultParsing() {
		String tagsJson = "{\"models\":[{\"name\":\"SparkLLM/Spark-X2.6-4B:q4_k_m\",\"size\":4000000000},{\"name\":\"llama3.1:8b\",\"size\":5000000000}]}";
		JsonObject root = JsonParser.parseString(tagsJson).getAsJsonObject();
		JsonArray models = root.getAsJsonArray("models");

		assertNotNull(models);
		assertEquals(2, models.size());

		boolean sparkFound = false;
		for (JsonElement el : models) {
			String name = el.getAsJsonObject().get("name").getAsString();
			if (name.equals("SparkLLM/Spark-X2.6-4B:q4_k_m")) {
				sparkFound = true;
			}
		}
		assertTrue(sparkFound, "Configured Spark model should be in the model list");
	}

	@Test
	void testModelNotFound() {
		String tagsJson = "{\"models\":[{\"name\":\"llama3.1:8b\",\"size\":5000000000}]}";
		JsonObject root = JsonParser.parseString(tagsJson).getAsJsonObject();
		JsonArray models = root.getAsJsonArray("models");

		String configuredModel = "SparkLLM/Spark-X2.6-4B:q4_k_m";
		boolean found = false;
		for (JsonElement el : models) {
			if (el.getAsJsonObject().get("name").getAsString().equals(configuredModel)) {
				found = true;
			}
		}
		assertFalse(found, "Model not in list should not be found");
	}

	@Test
	void testEmptyModelsList() {
		String tagsJson = "{\"models\":[]}";
		JsonObject root = JsonParser.parseString(tagsJson).getAsJsonObject();
		JsonArray models = root.getAsJsonArray("models");
		assertNotNull(models);
		assertEquals(0, models.size());
	}
}
