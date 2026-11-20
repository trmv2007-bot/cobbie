package com.rishi.cobbie.brain;

import java.util.List;
import java.util.Locale;

/**
 * Meaningful LLM connectivity states.
 *
 * <p>{@link #CONNECTED} means the server answered; it deliberately does NOT mean the configured
 * model is present — that is {@link #MODEL_AVAILABLE} vs {@link #MODEL_MISSING}. Reporting
 * CONNECTED for a bare HTTP 200 was the 0.4.0 bug that made {@code /spawncobbie} lie.
 */
public enum ConnectionState {

	/** Nothing has been tried yet. */
	DISCONNECTED("not connected yet"),
	/** A probe is in flight. */
	CONNECTING("connecting"),
	/** The server answered, but the configured model was not checked or could not be listed. */
	CONNECTED("connected"),
	/** The server answered and the configured model is installed. */
	MODEL_AVAILABLE("ready"),
	/** The server answered but the configured model is not installed. */
	MODEL_MISSING("model not installed"),
	/** A chat request to an otherwise reachable server failed. */
	REQUEST_FAILED("last request failed");

	private final String blurb;

	ConnectionState(String blurb) {
		this.blurb = blurb;
	}

	public String blurb() {
		return blurb;
	}

	/** Safe to greet the player with. */
	public boolean healthy() {
		return this == CONNECTED || this == MODEL_AVAILABLE;
	}

	/**
	 * Does {@code listed} contain the configured model {@code wanted}?
	 * Exact match first, then name-only match so "llama3" finds "llama3:latest" — but never a
	 * different model. No silent substitution.
	 */
	public static String findModel(String wanted, List<String> listed) {
		if (wanted == null || wanted.isBlank() || listed == null || listed.isEmpty()) return null;
		String want = wanted.trim().toLowerCase(Locale.ROOT);
		for (String name : listed) {
			if (name != null && name.equalsIgnoreCase(want)) return name;
		}
		String wantBase = baseOf(want);
		for (String name : listed) {
			if (name == null) continue;
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.equals(wantBase) || lower.startsWith(wantBase + ":")) return name;
		}
		return null;
	}

	private static String baseOf(String model) {
		int colon = model.indexOf(':');
		return colon > 0 ? model.substring(0, colon) : model;
	}
}
