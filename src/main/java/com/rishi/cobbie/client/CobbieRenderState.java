package com.rishi.cobbie.client;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Render state for {@link CobbieRenderer} / {@link CobbieModel}.
 *
 * <p>Extends {@link HumanoidRenderState} so vanilla {@code HumanoidModel.setupAnim} gets the
 * fields it reads (walkAnimationPos/Speed, xRot/yRot/bodyRot, swimAmount, isCrouching, ...).
 * Two extra fields are copied off the entity every frame by {@link CobbieRenderer}:
 *
 * <ul>
 *   <li>{@link #waving} — true while Cobbie is performing his greeting.</li>
 *   <li>{@link #wavePhase} — 0..1 progress through one wave cycle, used to oscillate the arm.</li>
 * </ul>
 *
 * <p>Note: {@code speedValue} must never be left at the Java default of {@code 0.0f} — vanilla
 * humanoid walk animation divides by it. {@link CobbieRenderer#extractRenderState} populates it
 * (via {@code HumanoidMobRenderer.extractHumanoidRenderState}), which clamps it to a minimum of 1.
 */
public class CobbieRenderState extends HumanoidRenderState {
	/** True while Cobbie is greeting someone. */
	public boolean waving;
	/** 0..1 position within the current wave cycle. */
	public float wavePhase;
}
