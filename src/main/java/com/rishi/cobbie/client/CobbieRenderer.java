package com.rishi.cobbie.client;

import com.rishi.cobbie.entity.CobbieEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

/**
 * Renders Cobbie as a visible, animated humanoid using {@link CobbieModel} — no GeckoLib.
 *
 * <p>Deliberately extends {@link MobRenderer} (rather than {@code HumanoidMobRenderer}, which drags
 * in {@code AgeableMobRenderer}'s child-model/child-state requirements) and calls the public static
 * {@link HumanoidMobRenderer#extractHumanoidRenderState} itself. That static call is not optional:
 * it is what fills {@code isCrouching}, {@code swimAmount}, the hand items and — critically —
 * {@code speedValue}, which vanilla humanoid walk animation divides by. Left at the field default
 * of {@code 0.0f} the whole rig would animate to NaN and disappear.
 *
 * <p>Nameplates come free from {@code EntityRenderer.submitNameDisplay}; nothing here overrides it.
 */
public class CobbieRenderer extends MobRenderer<CobbieEntity, CobbieRenderState, CobbieModel> {
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath("cobbie", "textures/entity/cobbie.png");

	public CobbieRenderer(EntityRendererProvider.Context context) {
		super(context, CobbieModel.create(), 0.5F);
		this.shadowRadius = 0.5F;
		this.shadowStrength = 0.8F;
	}

	@Override
	public CobbieRenderState createRenderState() {
		return new CobbieRenderState();
	}

	@Override
	public void extractRenderState(CobbieEntity entity, CobbieRenderState state, float partialTick) {
		// Living entity bits: bodyRot/yRot/xRot, walkAnimationPos/Speed, scale, pose, deathTime...
		super.extractRenderState(entity, state, partialTick);
		// Humanoid bits: crouch/swim/fall-flying, main arm, hand item states, speedValue.
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);
		// Cobbie-specific greeting state.
		state.waving = entity.isWaving();
		state.wavePhase = entity.getWavePhase(partialTick);
	}

	@Override
	public Identifier getTextureLocation(CobbieRenderState state) {
		return TEXTURE;
	}
}
