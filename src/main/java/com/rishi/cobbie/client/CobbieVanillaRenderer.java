package com.rishi.cobbie.client;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.entity.CobbieEntities;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * Vanilla renderer for Cobbie — renders him without needing GeckoLib.
 * Uses the cobbie texture so he has a visible body.
 */
public class CobbieVanillaRenderer extends EntityRenderer<CobbieEntity, LivingEntityRenderState> {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cobbie", "textures/entity/cobbie.png");

    public CobbieVanillaRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
        this.shadowStrength = 0.8f;
    }

    @Override
    public LivingEntityRenderState createRenderState() {
        return new LivingEntityRenderState();
    }

    public Identifier getTextureLocation(CobbieEntity entity) {
        return TEXTURE;
    }
}
