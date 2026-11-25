package com.rishi.cobbie.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test that runs the real engine, because the seam passing its fakes says nothing about
 * whether this machine can actually speak.
 *
 * <p>Windows-only: the engine is {@code System.Speech} through PowerShell and there is nothing to
 * check on another OS. It asserts the shape of a WAV rather than listening to it — that the file is
 * a RIFF header with audio behind it, written by the configured voice in reasonable time.
 */
@EnabledOnOs(OS.WINDOWS)
class SapiVoiceEngineTest {

	@Test
	void theConfiguredVoiceActuallyWritesAudio() throws Exception {
		SapiVoiceEngine engine = new SapiVoiceEngine("Microsoft Hazel Desktop");
		assertTrue(engine.available());

		long started = System.nanoTime();
		byte[] wav = engine.synthesize("Careful, that creeper is right behind you, back up a step.");
		long tookMillis = (System.nanoTime() - started) / 1_000_000L;

		String header = new String(wav, 0, 4, StandardCharsets.US_ASCII);
		assertEquals("RIFF", header, "the engine did not return a WAV");
		String form = new String(wav, 8, 4, StandardCharsets.US_ASCII);
		assertEquals("WAVE", form);
		assertTrue(wav.length > 20_000, "the audio was suspiciously short: " + wav.length + " bytes");
		// The measured budget: about 190 ms of synthesis for a line this long. Generous here so a
		// loaded machine fails on being broken, not on being busy.
		assertTrue(tookMillis < 5000, "synthesis took " + tookMillis + " ms");
	}

	@Test
	void anUnknownVoiceNameStillSpeaks() throws Exception {
		SapiVoiceEngine engine = new SapiVoiceEngine("No Such Voice Anywhere");
		byte[] wav = engine.synthesize("Two steps back.");

		assertTrue(wav.length > 4_000, "an unrecognised voice should fall back, not go silent");
	}
}
