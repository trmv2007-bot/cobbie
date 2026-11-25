package com.rishi.cobbie.voice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows' own voices, through PowerShell and {@code System.Speech}.
 *
 * <p>Chosen over the local Kokoro model on measurement, not preference: on this machine Kokoro
 * synthesises a twelve-word line in 8.5–9.3 s on the CPU (2.5–2.8× realtime, no GPU provider), and a
 * warning spoken nine seconds late describes a situation that no longer exists. SAPI does the same
 * line in about 190 ms. Kokoro fits behind {@link VoiceEngine} whenever there is a GPU to run it on.
 *
 * <p>The line is handed over in a file rather than on the command line. A chat line can contain
 * quotes, semicolons, apostrophes and dollar signs, and every one of them is a shell escape waiting
 * to eat the sentence.
 */
public final class SapiVoiceEngine implements VoiceEngine {

	/** A hung PowerShell must not own the synthesis thread forever. */
	private static final int HARD_TIMEOUT_SECONDS = 30;

	private final String voice;

	public SapiVoiceEngine(String voice) {
		this.voice = voice == null ? "" : voice.trim();
	}

	@Override
	public String name() {
		return voice.isEmpty() ? "sapi" : "sapi/" + voice;
	}

	@Override
	public boolean available() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	@Override
	public byte[] synthesize(String text) throws Exception {
		Path spoken = Files.createTempFile("cobbie-voice-", ".txt");
		Path audio = Files.createTempFile("cobbie-voice-", ".wav");
		try {
			Files.writeString(spoken, text, StandardCharsets.UTF_8);
			Files.deleteIfExists(audio);
			Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
					"-WindowStyle", "Hidden", "-Command", script(spoken, audio))
					.redirectErrorStream(true)
					.start();
			if (!process.waitFor(HARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("the speech engine did not finish in "
						+ HARD_TIMEOUT_SECONDS + "s");
			}
			String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!Files.exists(audio)) {
				throw new IllegalStateException("no audio was written: " + firstLine(said));
			}
			byte[] wav = Files.readAllBytes(audio);
			if (wav.length < 44) {
				throw new IllegalStateException("the audio file was empty: " + firstLine(said));
			}
			return wav;
		} finally {
			Files.deleteIfExists(spoken);
			Files.deleteIfExists(audio);
		}
	}

	/**
	 * A recognised name is selected if it is installed; an unrecognised one falls back to whatever
	 * Windows has as its default rather than failing the line. A voice is a setting, not a
	 * precondition for him speaking.
	 */
	private String script(Path spoken, Path audio) {
		String select = voice.isEmpty() ? "" : "try { $synth.SelectVoice('" + voice.replace("'", "''")
				+ "') } catch { }\n";
		return "Add-Type -AssemblyName System.Speech\n"
				+ "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
				+ select
				+ "$synth.SetOutputToWaveFile('" + audio.toAbsolutePath() + "')\n"
				+ "$synth.Speak([IO.File]::ReadAllText('" + spoken.toAbsolutePath() + "'))\n"
				+ "$synth.Dispose()\n";
	}

	private static String firstLine(String output) {
		if (output == null || output.isBlank()) return "no output";
		String trimmed = output.strip();
		int end = trimmed.indexOf('\n');
		return end < 0 ? trimmed : trimmed.substring(0, end);
	}
}
