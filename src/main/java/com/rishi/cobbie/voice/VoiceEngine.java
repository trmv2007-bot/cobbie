package com.rishi.cobbie.voice;

/**
 * Something on this machine that can turn one of his lines into audio.
 *
 * <p>Deliberately narrow: hand it text, get a WAV back. Which engine answers is a config value, not
 * a code path in the caller, so a better voice later is a new implementation of this interface and
 * nothing else.
 */
public interface VoiceEngine {

	/** How the log line names it — "sapi/Microsoft Hazel Desktop". */
	String name();

	/** Whether this engine can run here at all: the wrong OS, a missing module, a model not on disk. */
	boolean available();

	/**
	 * @return a complete WAV file's bytes.
	 * @throws Exception when the engine cannot speak this line; the caller degrades to text.
	 */
	byte[] synthesize(String text) throws Exception;
}
