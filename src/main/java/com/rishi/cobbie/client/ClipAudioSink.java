package com.rishi.cobbie.client;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.voice.AudioSink;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;

/**
 * Plays a finished WAV through the desktop's own mixer.
 *
 * <p>Not routed through the game's sound engine because the audio was made here, not shipped in the
 * mod: there is no sound event to register it under. It still respects the volume slider a player
 * would expect a companion's voice to follow — the players slider — and a newer line stops an older
 * one rather than talking over it.
 */
public final class ClipAudioSink implements AudioSink {

	private Clip playing;

	@Override
	public synchronized void play(byte[] wav) {
		stop();
		try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
			Clip clip = AudioSystem.getClip();
			clip.open(stream);
			applyVolume(clip);
			clip.start();
			playing = clip;
		} catch (Exception e) {
			Cobbie.LOGGER.warn("voice: could not play the line ({})", e.toString());
		}
	}

	@Override
	public synchronized void stop() {
		if (playing == null) return;
		try {
			playing.stop();
			playing.close();
		} catch (Exception ignored) {
			// A clip that is already finished needs nothing from us.
		}
		playing = null;
	}

	/** The game's players-volume, converted from the linear slider to the decibels the mixer wants. */
	private static void applyVolume(Clip clip) {
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
		float level = 0.0f;
		try {
			level = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.PLAYERS);
		} catch (Exception ignored) {
			level = 1.0f;
		}
		float gain = (float) (20.0 * Math.log10(Math.max(level, 0.0001f)));
		FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), gain)));
	}
}
