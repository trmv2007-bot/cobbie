package com.rishi.cobbie.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured, immutable observation of what Cobbie perceives. Built by
 * {@link WorldPerception} on demand and handed to the prompt layer.
 *
 * Deliberately plain Java (no Minecraft types) so it can be unit-tested and
 * so the game stays out of the prompt layer. {@link #describe()} renders it as
 * one compact block of text — Cobbie's brain runs a 4B model with a small
 * context window, so every character here is paid for twice.
 *
 * Field notes:
 *  - ids (dimension / equippedItem / block+entity ids / inventoryIds) are full
 *    registry ids, e.g. "minecraft:stone"; describe() strips the "minecraft:"
 *    prefix to save room (the game defaults to that namespace).
 *  - foodLevel is Cobbie's <i>owner's</i> hunger (Cobbie does not eat); 20 when
 *    the owner is offline or absent.
 *  - coordinates in BlockNote/EntityNote are absolute world coords; distance is
 *    straight-line distance from Cobbie in blocks.
 */
public record WorldSnapshot(
        String dimension,
        String biome,
        long timeOfDay,
        boolean raining,
        boolean thundering,
        int x,
        int y,
        int z,
        double health,
        double maxHealth,
        int foodLevel,
        String equippedItem,
        List<BlockNote> nearbyBlocks,
        List<EntityNote> nearbyEntities,
        List<String> nearbyPlayerNames,
        List<String> inventoryIds,
        String currentTask,
        String currentAction,
        String lastActionResult) {

	/** Sampling / output caps, shared by WorldPerception and describe(). */
	public static final int MAX_BLOCKS = 10;
	public static final int MAX_ENTITIES = 10;
	public static final int MAX_PLAYERS = 8;
	public static final int MAX_INVENTORY = 8;
	public static final int MAX_DESC_CHARS = 1200;
	public static final int MAX_FIELD_CHARS = 90;

	public record BlockNote(String id, int x, int y, int z, double distance, boolean solid) {}

	public record EntityNote(String id, double x, double y, double z, double distance,
			boolean hostile, double health, boolean isOwner) {}

	public WorldSnapshot {
		if (nearbyBlocks == null) nearbyBlocks = List.of();
		if (nearbyEntities == null) nearbyEntities = List.of();
		if (nearbyPlayerNames == null) nearbyPlayerNames = List.of();
		if (inventoryIds == null) inventoryIds = List.of();
		dimension = orUnknown(dimension);
		biome = orUnknown(biome);
		equippedItem = orNone(equippedItem);
		currentTask = orNone(currentTask);
		currentAction = orUnknown(currentAction);
		lastActionResult = orNone(lastActionResult);
	}

	/**
	 * Compact prompt block, e.g.
	 * <pre>
	 * at 10 64 -3 in overworld/plains, time 6234 (day), rain
	 * hp 18/20, owner food 20, holding stone_pickaxe
	 * blocks: stone@10 63 -3 d1.1s, dirt@9 64 -3 d1.4s
	 * entities: zombie@12 64 -1 d5.2 hp20 HOSTILE
	 * players: Rishi
	 * inventory: oak_log x12
	 * task: mine 5 iron, action: walk-to, last: OK mined stone at 10 64 -3
	 * </pre>
	 * Never throws and never exceeds {@link #MAX_DESC_CHARS}.
	 */
	public String describe() {
		StringBuilder sb = new StringBuilder(384);
		try {
			sb.append("at ").append(x).append(' ').append(y).append(' ').append(z)
					.append(" in ").append(shortId(dimension)).append('/').append(orUnknown(biome))
					.append(", time ").append(Math.floorMod(timeOfDay, 24000L)).append(" (").append(phase(timeOfDay)).append(')');
			if (raining) sb.append(", rain");
			if (thundering) sb.append(", thunder");
			sb.append('\n');

			sb.append("hp ").append(num(health)).append('/').append(num(maxHealth))
					.append(", owner food ").append(foodLevel)
					.append(", holding ").append(held()).append('\n');

			line(sb, "blocks", blockNotes());
			line(sb, "entities", entityNotes());
			line(sb, "players", playerNames());
			line(sb, "YOUR INVENTORY (exactly what you carry; never claim anything else)", inventory());

			sb.append("task: ").append(oneLine(currentTask, MAX_FIELD_CHARS))
					.append(", action: ").append(oneLine(currentAction, 24))
					.append(", last: ").append(oneLine(lastActionResult, MAX_FIELD_CHARS));

			String out = sb.toString().trim();
			if (out.length() <= MAX_DESC_CHARS) return out;
			return out.substring(0, MAX_DESC_CHARS - 1) + "~";
		} catch (Throwable t) {
			// The prompt pipeline must never break because perception looked odd.
			return "at " + x + " " + y + " " + z + ", hp " + num(health) + "/" + num(maxHealth) + " (describe failed)";
		}
	}

	// ----- renderers (each list already capped by perception, re-capped here) -----

	private List<String> blockNotes() {
		List<String> out = new ArrayList<>();
		for (BlockNote b : nearbyBlocks) {
			if (b == null || out.size() >= MAX_BLOCKS) break;
			out.add(shortId(b.id()) + "@" + b.x() + ' ' + b.y() + ' ' + b.z()
					+ " d" + num(b.distance()) + (b.solid() ? "s" : "-"));
		}
		return out;
	}

	private List<String> entityNotes() {
		List<String> out = new ArrayList<>();
		for (EntityNote e : nearbyEntities) {
			if (e == null || out.size() >= MAX_ENTITIES) break;
			StringBuilder sb = new StringBuilder();
			sb.append(shortId(e.id())).append('@').append(coord(e.x())).append(' ').append(coord(e.y()))
					.append(' ').append(coord(e.z())).append(" d").append(num(e.distance()));
			if (e.health() >= 0) sb.append(" hp").append(num(e.health()));
			if (e.hostile()) sb.append(" HOSTILE");
			if (e.isOwner()) sb.append(" (owner)");
			out.add(sb.toString());
		}
		return out;
	}

	private List<String> playerNames() {
		List<String> out = new ArrayList<>();
		for (String p : nearbyPlayerNames) {
			if (out.size() >= MAX_PLAYERS) break;
			if (p != null && !p.isBlank()) out.add(oneLine(p, 24));
		}
		return out;
	}

	private List<String> inventory() {
		List<String> out = new ArrayList<>();
		for (String i : inventoryIds) {
			if (out.size() >= MAX_INVENTORY) break;
			if (i != null && !i.isBlank()) out.add(shortId(oneLine(i, 40)));
		}
		return out;
	}

	private static void line(StringBuilder sb, String label, List<String> items) {
		sb.append(label).append(": ");
		if (items.isEmpty()) {
			sb.append("none");
		} else {
			sb.append(String.join(", ", items));
		}
		sb.append('\n');
	}

	// ----- tiny formatting helpers -----

	private static String shortId(String id) {
		if (id == null || id.isBlank()) return "?";
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	/** Reads better in a prompt than the literal "none". */
	private String held() {
		return equippedItem == null || equippedItem.isBlank() || equippedItem.equals("none")
				? "nothing" : shortId(equippedItem);
	}

	private static String orUnknown(String s) {
		return s == null || s.isBlank() ? "?" : s;
	}

	private static String orNone(String s) {
		return s == null || s.isBlank() ? "none" : s;
	}

	/** Collapse whitespace/newlines so one field can never wreck the line layout. */
	private static String oneLine(String s, int max) {
		if (s == null) return "none";
		String flat = s.replaceAll("\\s+", " ").trim();
		if (flat.isEmpty()) return "none";
		return flat.length() <= max ? flat : flat.substring(0, max - 1) + "~";
	}

	/** Whole numbers print as integers; anything else keeps one decimal. */
	private static String plain(double v) {
		long r = Math.round(v);
		if (Math.abs(v - r) < 0.05) return Long.toString(r);
		return Double.toString(Math.round(v * 10) / 10.0);
	}

	/** Health / distance: missing or negative means "unknown". */
	private static String num(double v) {
		return Double.isNaN(v) || v < 0 ? "?" : plain(v);
	}

	/** Coordinates may legitimately be negative (build goes down to -64). */
	private static String coord(double v) {
		return Double.isNaN(v) ? "?" : plain(v);
	}

	private static String phase(long timeOfDay) {
		long d = Math.floorMod(timeOfDay, 24000L);
		if (d < 12000) return "day";
		if (d < 13700) return "dusk";
		if (d < 22000) return "night";
		return "dawn";
	}

	/** A snapshot that says "I know nothing" — used when perception fails outright. */
	public static WorldSnapshot unknown() {
		return new WorldSnapshot("?", "?", 0L, false, false, 0, 0, 0, 0.0, 0.0, 20, "none",
				List.of(), List.of(), List.of(), List.of(), "none", "idle", "none");
	}
}
