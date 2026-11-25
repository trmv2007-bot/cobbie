package com.rishi.cobbie.presence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The last few lines he has said, newest last.
 *
 * <p>One field holding the newest line answers "what did he say" and fails the question a test actually has — *did he
 * say X* — because a line that lands half a second later (a plan's own settled summary, a follow-up) overwrites the
 * one that mattered. Twice now a red came from exactly that: a world change and a wall rescue were both announced
 * correctly and then reported as *"all done"*, because the report arrived inside the same observation window.
 *
 * <p>So this is a window, not a slot: bounded, in order, and able to answer whether something was said recently. Small
 * on purpose — eight lines is enough for a question about a moment, and a companion that hoarded its every word would
 * be a memory leak with a nice name.
 */
public final class RecentLines {

	/** How many lines are kept. Enough to span an event and whatever lands behind it. */
	public static final int DEFAULT_CAPACITY = 8;

	private final int capacity;
	private final Deque<String> lines = new ArrayDeque<>();

	public RecentLines() {
		this(DEFAULT_CAPACITY);
	}

	public RecentLines(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	/** Record a line; blank ones are not speech and are ignored. */
	public void add(String line) {
		if (line == null || line.isBlank()) return;
		lines.addLast(line);
		while (lines.size() > capacity) lines.removeFirst();
	}

	/** The newest line, or "" when he has said nothing. */
	public String last() {
		return lines.isEmpty() ? "" : lines.peekLast();
	}

	/** Did he say a line containing this fragment among the ones still held? Case-sensitive by design. */
	public boolean saidRecently(String fragment) {
		if (fragment == null || fragment.isBlank()) return false;
		for (String line : lines) {
			if (line.contains(fragment)) return true;
		}
		return false;
	}

	/** The lines still held, oldest first — for a failure message that shows what he actually said. */
	public List<String> recent() {
		return Collections.unmodifiableList(new ArrayList<>(lines));
	}

	public int size() {
		return lines.size();
	}
}
