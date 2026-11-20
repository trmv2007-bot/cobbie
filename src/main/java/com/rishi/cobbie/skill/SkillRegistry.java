package com.rishi.cobbie.skill;

import com.rishi.cobbie.Cobbie;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Registry of learned skills. Loads/saves to config/cobbie_skills.json.
 * Matches a current context against known skills and suggests replays.
 */
public final class SkillRegistry {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();

	private static Path getSkillsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cobbie_skills.json");
	}

	/** Lazy-load skills from disk on first access. */
	private static void ensureLoaded() {
		if (skills.isEmpty()) {
			try {
				Path file = getSkillsFile();
				if (Files.exists(file)) {
					JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : root.entrySet()) {
						JsonObject obj = e.getValue().getAsJsonObject();
						String name = obj.get("name").getAsString();
						String context = obj.get("context").getAsString();
						List<ActionStep> steps = new ArrayList<>();
						if (obj.has("steps")) {
							for (JsonElement se : obj.getAsJsonArray("steps")) {
								JsonObject so = se.getAsJsonObject();
								steps.add(new ActionStep(
										so.get("action").getAsString(),
										so.get("target").getAsString(),
										so.get("x").getAsInt(),
										so.get("y").getAsInt(),
										so.get("z").getAsInt(),
										so.has("data") ? so.get("data").getAsString() : ""
								));
							}
						}
						int success = obj.has("success") ? obj.get("success").getAsInt() : 0;
						int failure = obj.has("failure") ? obj.get("failure").getAsInt() : 0;
						skills.put(name, new Skill(name, context, steps, success, failure));
					}
				}
			} catch (Exception ex) {
				Cobbie.LOGGER.warn("Failed to load Cobbie skills: {}", ex.getMessage());
			}
		}
	}

	private static void save() {
		try {
			JsonObject root = new JsonObject();
			for (Skill s : skills.values()) {
				JsonObject obj = new JsonObject();
				obj.addProperty("name", s.name());
				obj.addProperty("context", s.context());
				JsonArray steps = new JsonArray();
				for (ActionStep st : s.steps()) {
					JsonObject so = new JsonObject();
					so.addProperty("action", st.action());
					so.addProperty("target", st.target());
					so.addProperty("x", st.x());
					so.addProperty("y", st.y());
					so.addProperty("z", st.z());
					so.addProperty("data", st.data());
					steps.add(so);
				}
				obj.add("steps", steps);
				obj.addProperty("success", s.successCount());
				obj.addProperty("failure", s.failureCount());
				root.add(s.name(), obj);
			}
			Files.createDirectories(getSkillsFile().getParent());
			Files.writeString(getSkillsFile(), GSON.toJson(root));
		} catch (Exception ex) {
			Cobbie.LOGGER.warn("Failed to save Cobbie skills: {}", ex.getMessage());
		}
	}

	public static Optional<Skill> get(String name) {
		ensureLoaded();
		return Optional.ofNullable(skills.get(name));
	}

	public static List<Skill> all() {
		ensureLoaded();
		return new ArrayList<>(skills.values());
	}

	public static Optional<Skill> match(String context) {
		ensureLoaded();
		// Simple substring match on context — good enough for v1
		return all().stream()
				.filter(s -> s.context().contains(context) || context.contains(s.context()))
				.max(Comparator.comparingInt(Skill::successCount));
	}

	public static void register(Skill skill) {
		ensureLoaded();
		skills.put(skill.name(), skill);
		save();
	}

	public static void recordSuccess(String name) {
		ensureLoaded();
		skills.computeIfPresent(name, (k, s) ->
				new Skill(s.name(), s.context(), s.steps(), s.successCount() + 1, s.failureCount()));
		save();
	}

	public static void recordFailure(String name) {
		ensureLoaded();
		skills.computeIfPresent(name, (k, s) ->
				new Skill(s.name(), s.context(), s.steps(), s.successCount(), s.failureCount() + 1));
		save();
	}
}
