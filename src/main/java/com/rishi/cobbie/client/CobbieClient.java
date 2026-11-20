package com.rishi.cobbie.client;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * Client-side entrypoint: registers Cobbie's humanoid renderer.
 * Settings live in config/cobbie.json (editable at any time).
 */
public class CobbieClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(CobbieEntities.COBBIE, CobbieRenderer::new);
		Cobbie.LOGGER.info("Cobbie client renderer registered (◕‿◕)");
	}
}
