package com.rishi.cobbie.client;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * Client-side entrypoint: registers Cobbie's vanilla renderer.
 * Renders as a simple textured box — no GeckoLib dependency.
 * Settings are configured in config/cobbie.json (editable at any time).
 */
public class CobbieClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(
				CobbieEntities.COBBIE,
				CobbieVanillaRenderer::new);
		Cobbie.LOGGER.info("Cobbie client renderer registered (◕‿◕)");
	}
}
