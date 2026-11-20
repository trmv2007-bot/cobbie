package com.rishi.cobbie.memory;

import com.rishi.cobbie.Cobbie;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Persistent cross-session memory for Cobbie.
 * Stores per-player: nickname, recent chat turns, learned skill names.
 * Lives in config/cobbie_memory.json so it survives game restarts.
 */
public final class CobbieMemory {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final int MAX_CHAT_TURNS = 60;
	private static final ConcurrentHashMap<UUID, PlayerMemory> memories = new ConcurrentHashMap<>();

	public record PlayerMemory(String nickname, List<ChatTurn> turns, List<String> skills) {}
	public record ChatTurn(String playerText, String cobbieAnswer, long timestamp) {}

	private static Path getMemoryFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cobbie_memory.json");
	}

	/** Lazy-load memory from disk on first access. */
	private static void ensureLoaded() {
		if (memories.isEmpty()) {
			try {
				Path file = getMemoryFile();
				if (Files.exists(file)) {
					JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : root.entrySet()) {
						JsonObject obj = e.getValue().getAsJsonObject();
						String nick = obj.has("nickname") ? obj.get("nickname").getAsString() : "Player";

						List<ChatTurn> turns = new ArrayList<>();
						if (obj.has("turns")) {
							for (JsonElement te : obj.getAsJsonArray("turns")) {
								JsonObject to = te.getAsJsonObject();
								turns.add(new ChatTurn(
										to.get("player").getAsString(),
										to.get("cobbie").getAsString(),
										to.get("ts").getAsLong()
								));
							}
						}

						List<String> skills = new ArrayList<>();
						if (obj.has("skills")) {
							for (JsonElement se : obj.getAsJsonArray("skills")) {
								skills.add(se.getAsString());
							}
						}

						memories.put(UUID.fromString(e.getKey()), new PlayerMemory(nick, turns, skills));
					}
				}
			} catch (Exception ex) {
				Cobbie.LOGGER.warn("Failed to load Cobbie memory: {}", ex.getMessage());
			}
		}
	}

	private static void save() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, PlayerMemory> e : memories.entrySet()) {
				JsonObject obj = new JsonObject();
				obj.addProperty("nickname", e.getValue().nickname());

				JsonArray turns = new JsonArray();
				for (ChatTurn t : e.getValue().turns) {
					JsonObject to = new JsonObject();
					to.addProperty("player", t.playerText());
					to.addProperty("cobbie", t.cobbieAnswer());
					to.addProperty("ts", t.timestamp());
					turns.add(to);
				}
				obj.add("turns", turns);

				JsonArray skills = new JsonArray();
				for (String s : e.getValue().skills()) skills.add(s);
				obj.add("skills", skills);

				root.add(e.getKey().toString(), obj);
			}
			Files.createDirectories(getMemoryFile().getParent());
			Files.writeString(getMemoryFile(), GSON.toJson(root));
		} catch (Exception ex) {
			Cobbie.LOGGER.warn("Failed to save Cobbie memory: {}", ex.getMessage());
		}
	}

	public static PlayerMemory get(UUID uuid) {
		ensureLoaded();
		return memories.computeIfAbsent(uuid, k -> new PlayerMemory("Player", new ArrayList<>(), new ArrayList<>()));
	}

	public static void addTurn(UUID uuid, String playerText, String cobbieAnswer) {
		ensureLoaded();
		PlayerMemory mem = get(uuid);
		List<ChatTurn> turns = new ArrayList<>(mem.turns());
		turns.add(new ChatTurn(playerText, cobbieAnswer, System.currentTimeMillis()));
		while (turns.size() > MAX_CHAT_TURNS) turns.remove(0);
		memories.put(uuid, new PlayerMemory(mem.nickname(), turns, mem.skills()));
		save();
	}

	public static void setNickname(UUID uuid, String nickname) {
		ensureLoaded();
		PlayerMemory mem = get(uuid);
		memories.put(uuid, new PlayerMemory(nickname, mem.turns(), mem.skills()));
		save();
	}

	public static void addSkill(UUID uuid, String skill) {
		ensureLoaded();
		PlayerMemory mem = get(uuid);
		List<String> skills = new ArrayList<>(mem.skills());
		if (!skills.contains(skill)) skills.add(skill);
		memories.put(uuid, new PlayerMemory(mem.nickname(), mem.turns(), skills));
		save();
	}

	public static List<String> recentAnswers(UUID uuid, int n) {
		ensureLoaded();
		List<ChatTurn> turns = get(uuid).turns();
		List<String> out = new ArrayList<>();
		int start = Math.max(0, turns.size() - n);
		for (int i = start; i < turns.size(); i++) {
			out.add(turns.get(i).cobbieAnswer());
		}
		return out;
	}
}
