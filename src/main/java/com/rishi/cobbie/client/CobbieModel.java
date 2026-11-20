package com.rishi.cobbie.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartNames;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Cobbie's body: a chunky, friendly chibi humanoid built from vanilla cube geometry.
 *
 * <p>No {@code EntityModelSet}/{@code ModelLayerLocation} registration is needed — the mesh is
 * baked on the fly in {@link #create()} via {@code LayerDefinition.create(mesh, 64, 64).bakeRoot()}.
 * The single-arg {@link HumanoidModel#HumanoidModel(ModelPart)} constructor renders with
 * {@code RenderTypes::entityCutout}, i.e. an alpha-tested (cutout) entity texture, which is what
 * the transparent front of the hat cube relies on.
 *
 * <h2>Part naming</h2>
 * {@code HumanoidModel}'s constructor performs {@code root.getChild("head")},
 * {@code head.getChild("hat")} (the hat is a CHILD of the head, not of the root),
 * {@code root.getChild("body"|"right_arm"|"left_arm"|"right_leg"|"left_leg")}, and
 * {@code ModelPart.getChild} throws {@link java.util.NoSuchElementException} on a miss, so every
 * one of those seven names must exist exactly. {@link PartNames} holds the constants.
 *
 * <h2>Vertical placement</h2>
 * {@code LivingEntityRenderer} does {@code scale(-1,-1,1)} then {@code translate(0, -1.501f, 0)}
 * before drawing, which pins model-space {@code y = 0} at 1.501 blocks above the entity's feet and
 * puts the <em>ground plane at model {@code y = 24}</em> (16 model units = 1 block, y grows down).
 * So although Cobbie is only ~1.375 blocks tall, his legs still have to end at y = 24 or he would
 * float. All parts below are therefore authored inside the standard 32-unit vanilla band:
 *
 * <pre>
 *   model y:  -0 ..  2   antenna        (sticks up above the head)
 *   model y:   2 .. 10   head   8x8x8
 *   model y:  10 .. 17   body   8x7x4
 *   model y:  10 .. 16   arms   4x6x4   (slightly shorter than the legs)
 *   model y:  17 .. 24   legs   4x7x4   (feet land exactly on the ground plane)
 * </pre>
 *
 * <h2>UV map (64x64, matches assets/cobbie/textures/entity/cobbie.png)</h2>
 * Standard cube unwrap for a box at (u,v) sized (w,h,d): top (u+d, v, w, d),
 * bottom (u+d+w, v, w, d), right (u, v+d, d, h), front (u+d, v+d, w, h),
 * left (u+d+w, v+d, d, h), back (u+d+w+d, v+d, w, h).
 *
 * <pre>
 *   part       u   v   w  h  d   footprint
 *   head       0   0   8  8  8   x  0..31  y  0..15
 *   right_arm 32   0   4  6  4   x 32..47  y  0..9
 *   left_arm  48   0   4  6  4   x 48..63  y  0..9
 *   body       0  16   8  7  4   x  0..23  y 16..26
 *   right_leg 24  16   4  7  4   x 24..39  y 16..26
 *   left_leg  40  16   4  7  4   x 40..55  y 16..26
 *   hat        0  32   8  8  8   x  0..31  y 32..47   (front face is transparent)
 *   antenna   32  32   1  2  1   x 32..35  y 32..34
 * </pre>
 */
public class CobbieModel extends HumanoidModel<CobbieRenderState> {
	/** Texture size; the generated PNG must match. */
	public static final int TEXTURE_WIDTH = 64;
	public static final int TEXTURE_HEIGHT = 64;

	/** Part name for the little antenna parented to the head (not a vanilla part). */
	private static final String ANTENNA = "antenna";

	// Part pose Y offsets — see the class comment for why the feet sit at y = 24.
	private static final float HEAD_Y = 10.0F;
	private static final float BODY_Y = 10.0F;
	private static final float ARM_Y = 11.0F;
	private static final float LEG_Y = 17.0F;

	// Cube sizes.
	private static final float HEAD_SIZE = 8.0F;
	private static final float BODY_W = 8.0F, BODY_H = 7.0F, BODY_D = 4.0F;
	private static final float ARM_W = 4.0F, ARM_H = 6.0F, ARM_D = 4.0F;
	private static final float LEG_W = 4.0F, LEG_H = 7.0F, LEG_D = 4.0F;
	private static final float ANTENNA_W = 1.0F, ANTENNA_H = 2.0F, ANTENNA_D = 1.0F;

	/** How much the hat/helmet cube grows past the head cube. */
	private static final float HAT_GROWTH = 0.25F;

	// ----- Wave pose (radians) -----
	/** Base outward swing of the raised right arm; ~2.4 rad points it up and away from the body. */
	private static final float WAVE_ARM_SWING = 2.4F;
	/** Amplitude of the side-to-side wave. */
	private static final float WAVE_Z_AMPLITUDE = 0.35F;
	/** Amplitude of the smaller forward/back wobble. */
	private static final float WAVE_X_AMPLITUDE = 0.25F;
	private static final float TWO_PI = (float) (Math.PI * 2.0);

	public CobbieModel(ModelPart root) {
		super(root);
	}

	/** Bakes Cobbie's mesh and returns a ready-to-use model (no model-layer registration needed). */
	public static CobbieModel create() {
		return new CobbieModel(bakeMesh());
	}

	private static ModelPart bakeMesh() {
		LayerDefinition layer = LayerDefinition.create(defineMesh(), TEXTURE_WIDTH, TEXTURE_HEIGHT);
		return layer.bakeRoot();
	}

	private static MeshDefinition defineMesh() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		// --- head (+ hat and antenna as children, so they follow head look-around) ---
		PartDefinition head = root.addOrReplaceChild(
				PartNames.HEAD,
				CubeListBuilder.create()
						.texOffs(0, 0)
						.addBox(-HEAD_SIZE / 2.0F, -HEAD_SIZE, -HEAD_SIZE / 2.0F, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE),
				PartPose.offset(0.0F, HEAD_Y, 0.0F));

		head.addOrReplaceChild(
				PartNames.HAT,
				CubeListBuilder.create()
						.texOffs(0, 32)
						.addBox(
								-HEAD_SIZE / 2.0F, -HEAD_SIZE, -HEAD_SIZE / 2.0F,
								HEAD_SIZE, HEAD_SIZE, HEAD_SIZE,
								new CubeDeformation(HAT_GROWTH)),
				PartPose.ZERO);

		head.addOrReplaceChild(
				ANTENNA,
				CubeListBuilder.create()
						.texOffs(32, 32)
						.addBox(
								-ANTENNA_W / 2.0F, -ANTENNA_H, -ANTENNA_D / 2.0F,
								ANTENNA_W, ANTENNA_H, ANTENNA_D),
				PartPose.offset(2.0F, -HEAD_SIZE, 1.0F));

		// --- torso ---
		root.addOrReplaceChild(
				PartNames.BODY,
				CubeListBuilder.create()
						.texOffs(0, 16)
						.addBox(-BODY_W / 2.0F, 0.0F, -BODY_D / 2.0F, BODY_W, BODY_H, BODY_D),
				PartPose.offset(0.0F, BODY_Y, 0.0F));

		// --- arms (own UV regions, so no mirroring needed) ---
		root.addOrReplaceChild(
				PartNames.RIGHT_ARM,
				CubeListBuilder.create()
						.texOffs(32, 0)
						.addBox(-ARM_W + 1.0F, -1.0F, -ARM_D / 2.0F, ARM_W, ARM_H, ARM_D),
				PartPose.offset(-5.0F, ARM_Y, 0.0F));

		root.addOrReplaceChild(
				PartNames.LEFT_ARM,
				CubeListBuilder.create()
						.texOffs(48, 0)
						.addBox(-1.0F, -1.0F, -ARM_D / 2.0F, ARM_W, ARM_H, ARM_D),
				PartPose.offset(5.0F, ARM_Y, 0.0F));

		// --- legs: bottom of the cubes lands on the ground plane at model y = 24 ---
		root.addOrReplaceChild(
				PartNames.RIGHT_LEG,
				CubeListBuilder.create()
						.texOffs(24, 16)
						.addBox(-LEG_W / 2.0F, 0.0F, -LEG_D / 2.0F, LEG_W, LEG_H, LEG_D),
				PartPose.offset(-2.0F, LEG_Y, 0.0F));

		root.addOrReplaceChild(
				PartNames.LEFT_LEG,
				CubeListBuilder.create()
						.texOffs(40, 16)
						.addBox(-LEG_W / 2.0F, 0.0F, -LEG_D / 2.0F, LEG_W, LEG_H, LEG_D),
				PartPose.offset(2.0F, LEG_Y, 0.0F));

		return mesh;
	}

	@Override
	public void setupAnim(CobbieRenderState state) {
		// Vanilla gives us idle breathing, walk/arm-swing, head look (xRot/yRot) and body rotation.
		super.setupAnim(state);

		// Idle pose keeps the arms just barely off the torso so the cubes don't clip into the body.
		// For a hanging arm, +zRot swings it toward -x: that is "outward" for the right arm
		// (which lives at negative x) and "inward" for the left, hence the mirrored signs.
		this.rightArm.zRot = 0.06F;
		this.leftArm.zRot = -0.06F;

		if (state.waving) {
			float phase = state.wavePhase * TWO_PI;
			float osc = (float) Math.sin(phase);
			// Raise the right arm up and outward (positive zRot swings a hanging arm toward -x,
			// which is away from the body for the right arm), then rock the hand side to side.
			this.rightArm.zRot = WAVE_ARM_SWING + WAVE_Z_AMPLITUDE * osc;
			this.rightArm.xRot = -0.2F + WAVE_X_AMPLITUDE * osc;
			this.rightArm.yRot = 0.2F * osc;
			// Counter-balance: the free arm drifts slightly outward (negative zRot for the left arm).
			this.leftArm.zRot = -0.18F;
			this.leftArm.xRot = 0.1F * osc;
		}
	}
}
