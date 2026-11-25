package com.rishi.cobbie.voice;

import com.rishi.cobbie.Cobbie;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * His spoken lines, synthesised off the game thread with a cap and a silent fallback.
 *
 * <p>The text is already on screen before this class is called, so every failure here is a missing
 * voice and never a missing reply:
 * <ul>
 *   <li><b>Never blocks.</b> {@link #offer} stores the line and returns, whatever the engine is
 *       doing. Synthesis runs on one daemon thread, so a slow engine delays audio and nothing
 *       else.</li>
 *   <li><b>Never stamps.</b> A line that takes longer than the cap to synthesise is thrown away
 *       rather than played late — a warning that arrives after the creeper is a sentence about a
 *       situation that no longer exists.</li>
 *   <li><b>Newest wins.</b> Only one line waits, so a burst of speech queued behind a slow engine
 *       produces one thing said, not five in a row.</li>
 *   <li><b>One log line.</b> The first failure of each kind is logged once with its reason; after
 *       that it degrades silently, because a broken engine must not become a log flood.</li>
 * </ul>
 *
 * <p>Minecraft-free on purpose: the game thread and the speakers are the client's business, so the
 * cadence, the cap and the degradation are a unit test.
 */
public final class SpeechService {

	/** Why the last line was dropped, for a test and for the log line. */
	public enum Drop {
		SLOWER_THAN_CAP, ENGINE_FAILED, NO_AUDIO
	}

	private final BooleanSupplier enabled;
	private final VoiceEngine engine;
	private final AudioSink sink;
	private final int capMillis;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "cobbie-voice");
		thread.setDaemon(true);
		return thread;
	});

	private String pending;
	private boolean busy;
	private int submissions;
	private Drop lastDrop;
	private int dropped;
	private boolean reported;

	public SpeechService(BooleanSupplier enabled, VoiceEngine engine, AudioSink sink, int capMillis) {
		this.enabled = enabled;
		this.engine = engine;
		this.sink = sink;
		this.capMillis = Math.max(1, capMillis);
	}

	/** Offer a line to speak. Returns immediately; a disabled voice does nothing at all. */
	public void offer(String line) {
		if (!enabled.getAsBoolean()) return;
		if (engine == null || !engine.available()) {
			report("no speech engine on this machine");
			return;
		}
		if (line == null || line.isBlank()) return;
		synchronized (this) {
			pending = line;
			if (busy) return;
			busy = true;
			submissions++;
		}
		worker.submit(this::drain);
	}

	/** How many times a line actually reached the worker. Zero while the voice is off. */
	synchronized int submissions() {
		return submissions;
	}

	/** How many lines were dropped rather than spoken. */
	public synchronized int dropped() {
		return dropped;
	}

	/** Why the most recent line was dropped, or null when the last one played. */
	public synchronized Drop lastDrop() {
		return lastDrop;
	}

	private void drain() {
		try {
			while (true) {
				String line;
				synchronized (this) {
					line = pending;
					pending = null;
					if (line == null) {
						busy = false;
						return;
					}
				}
				speak(line);
			}
		} catch (Throwable t) {
			synchronized (this) {
				busy = false;
			}
			report("the speech thread failed: " + t);
		}
	}

	private void speak(String line) {
		long started = System.nanoTime();
		try {
			byte[] wav = engine.synthesize(line);
			long tookMillis = (System.nanoTime() - started) / 1_000_000L;
			if (wav == null || wav.length == 0) {
				degrade(Drop.NO_AUDIO, "the engine returned no audio");
				return;
			}
			if (tookMillis > capMillis) {
				degrade(Drop.SLOWER_THAN_CAP, "synthesis took " + tookMillis + " ms, over the "
						+ capMillis + " ms cap");
				return;
			}
			sink.play(wav);
		} catch (Exception e) {
			degrade(Drop.ENGINE_FAILED, e.toString());
		}
	}

	private void degrade(Drop why, String reason) {
		synchronized (this) {
			dropped++;
			lastDrop = why;
		}
		report(reason + " — staying with text");
	}

	/** Once per kind of failure is enough to diagnose it; a flood is not diagnosis. */
	private void report(String reason) {
		synchronized (this) {
			if (reported) return;
			reported = true;
		}
		Cobbie.LOGGER.warn("voice: {} ({})", reason, engine == null ? "no engine" : engine.name());
	}
}
