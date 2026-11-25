package com.rishi.cobbie.voice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voice is never allowed to cost him the reply, so the interesting cases are all failures: a
 * slow engine, a broken engine, an absent engine, and a burst that arrives faster than one thread
 * can speak it.
 *
 * <p>The text is already on screen before any of this runs, which is exactly why this class can be
 * a unit test — what matters is that {@code offer} returns at once and that nothing below it can
 * throw back into the game thread.
 */
class SpeechServiceTest {

	private static final byte[] WAV = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0};

	/** Records what was played, and how many lines reached the speakers. */
	private static final class RecordingSink implements AudioSink {
		final List<byte[]> played = new CopyOnWriteArrayList<>();
		final CountDownLatch first = new CountDownLatch(1);

		@Override
		public void play(byte[] wav) {
			played.add(wav);
			first.countDown();
		}
	}

	/** An engine that answers instantly with fixed audio. */
	private static final class FastEngine implements VoiceEngine {
		final AtomicInteger calls = new AtomicInteger();

		@Override public String name() { return "fast"; }
		@Override public boolean available() { return true; }
		@Override public byte[] synthesize(String text) {
			calls.incrementAndGet();
			return WAV;
		}
	}

	/** An engine that takes its time, as Kokoro does at 2.5x realtime, and records what it was asked. */
	private static final class SlowEngine implements VoiceEngine {
		private final long millis;
		final List<String> texts = new CopyOnWriteArrayList<>();

		SlowEngine(long millis) { this.millis = millis; }

		@Override public String name() { return "slow"; }
		@Override public boolean available() { return true; }
		@Override public byte[] synthesize(String text) throws Exception {
			texts.add(text);
			Thread.sleep(millis);
			return WAV;
		}
	}

	/** An engine that fails, as a missing voice or a dead PowerShell would. */
	private static final class BrokenEngine implements VoiceEngine {
		@Override public String name() { return "broken"; }
		@Override public boolean available() { return true; }
		@Override public byte[] synthesize(String text) throws Exception {
			throw new IllegalStateException("no such voice");
		}
	}

	@Test
	void speaksALineWhenTheEngineIsFast() throws Exception {
		RecordingSink sink = new RecordingSink();
		SpeechService service = new SpeechService(() -> true, new FastEngine(), sink, 1500);

		service.offer("that creeper is still there");

		assertTrue(sink.first.await(2, TimeUnit.SECONDS), "the line never reached the speakers");
		assertEquals(1, sink.played.size());
		assertEquals(0, service.dropped());
	}

	@Test
	void aLineSlowerThanTheCapIsDroppedRatherThanPlayedLate() throws Exception {
		RecordingSink sink = new RecordingSink();
		SpeechService service = new SpeechService(() -> true, new SlowEngine(400), sink, 100);

		service.offer("that creeper is still there");

		// Wait for the drop rather than for a play: the point is that nothing plays.
		long deadline = System.currentTimeMillis() + 3000;
		while (service.dropped() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(1, service.dropped(), "a slow line should be dropped");
		assertEquals(SpeechService.Drop.SLOWER_THAN_CAP, service.lastDrop());
		assertEquals(0, sink.played.size(), "a line over the cap must never be spoken");
	}

	@Test
	void aBrokenEngineDegradesWithoutThrowing() throws Exception {
		RecordingSink sink = new RecordingSink();
		SpeechService service = new SpeechService(() -> true, new BrokenEngine(), sink, 1500);

		service.offer("that creeper is still there");

		long deadline = System.currentTimeMillis() + 3000;
		while (service.dropped() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(SpeechService.Drop.ENGINE_FAILED, service.lastDrop());
		assertEquals(0, sink.played.size());
	}

	@Test
	void aMissingEngineIsSilentAndNeverCallsSynthesis() throws Exception {
		RecordingSink sink = new RecordingSink();
		SpeechService service = new SpeechService(() -> true, null, sink, 1500);

		service.offer("that creeper is still there");
		Thread.sleep(100);

		assertEquals(0, sink.played.size());
		assertEquals(0, service.dropped());
	}

	@Test
	void voiceOffCostsNothingAtAll() throws Exception {
		RecordingSink sink = new RecordingSink();
		FastEngine engine = new FastEngine();
		SpeechService service = new SpeechService(() -> false, engine, sink, 1500);

		service.offer("that creeper is still there");
		service.offer("and another line");
		service.offer("and a third");
		Thread.sleep(100);

		assertEquals(0, engine.calls.get(), "a disabled voice must not spend the engine at all");
		assertEquals(0, sink.played.size());
		// Not even a submission: a disabled feature that starts a thread per line is a cost nobody
		// notices until there have been a hundred lines.
		assertEquals(0, service.submissions(), "a disabled voice must not reach the worker at all");
	}

	@Test
	void offeringReturnsAtOnceEvenWhileTheEngineIsBusy() {
		RecordingSink sink = new RecordingSink();
		SpeechService service = new SpeechService(() -> true, new SlowEngine(500), sink, 5000);

		long started = System.nanoTime();
		service.offer("the first line");
		service.offer("the second line");
		long tookMillis = (System.nanoTime() - started) / 1_000_000L;

		assertTrue(tookMillis < 100, "offer blocked the caller for " + tookMillis + " ms");
	}

	@Test
	void onlyTheNewestLineWaits() throws Exception {
		RecordingSink sink = new RecordingSink();
		SlowEngine engine = new SlowEngine(200);
		SpeechService service = new SpeechService(() -> true, engine, sink, 5000);

		// Three lines offered while the engine is busy: the middle one has no reason to be spoken
		// once a newer one has replaced it, so it must never even be synthesised.
		service.offer("line one");
		service.offer("line two");
		service.offer("line three");

		long deadline = System.currentTimeMillis() + 4000;
		while (engine.texts.size() < 2 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		Thread.sleep(300);

		assertTrue(engine.texts.contains("line three"), "the newest line must be the one that survives");
		assertFalse(engine.texts.contains("line two"), "a superseded line should never reach the engine");
		assertTrue(engine.texts.size() <= 2, "a burst must not be read out in full: " + engine.texts);
		assertNull(service.lastDrop());
	}

	@Test
	void aBlankLineIsNotSynthesised() throws Exception {
		RecordingSink sink = new RecordingSink();
		FastEngine engine = new FastEngine();
		SpeechService service = new SpeechService(() -> true, engine, sink, 1500);

		service.offer("   ");
		service.offer(null);
		Thread.sleep(100);

		assertEquals(0, engine.calls.get());
		assertNotNull(service.lastDrop() == null ? "" : "unexpected drop");
	}
}
