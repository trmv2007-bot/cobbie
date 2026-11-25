package com.rishi.cobbie.voice;

/** Where finished audio goes. The client plays it; a test records it. */
public interface AudioSink {

	/** Play a complete WAV. Called on the speech thread, never on the game thread. */
	void play(byte[] wav);

	/** Cut off anything still playing, so a newer line replaces an older one instead of stacking. */
	default void stop() {
	}
}
