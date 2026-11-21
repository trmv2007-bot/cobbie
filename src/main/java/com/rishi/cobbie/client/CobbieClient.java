package com.rishi.cobbie.client;

import com.rishi.cobbie.Cobbie;
import com.mojang.blaze3d.platform.InputConstants;
import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/**
 * Client-side entrypoint: registers Cobbie's humanoid renderer and the in-game config screen.
 * Opens with {@code /cobbie config}.
 */
public class CobbieClient implements ClientModInitializer {

	/** Opens the settings screen. Same screen as {@code /cobbie config}, for people who prefer keys. */
	private static final net.minecraft.client.KeyMapping OPEN_CONFIG =
			new net.minecraft.client.KeyMapping("key.cobbie.settings", InputConstants.Type.KEYBOARD,
					InputConstants.KEY_C, net.minecraft.client.KeyMapping.Category.MISC);

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(CobbieEntities.COBBIE, CobbieRenderer::new);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cobbie")
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("config")
								.executes(ctx -> {
									Minecraft.getInstance().execute(() ->
											Minecraft.getInstance().setScreenAndShow(new CobbieConfigScreen(null)));
									return 1;
								}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reload")
								.executes(ctx -> {
									com.rishi.cobbie.config.CobbieConfig.reload();
									ctx.getSource().sendFeedback(Component.literal("§aCobbie config reloaded"));
									return 1;
								}))
				));

		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CONFIG.consumeClick()) {
				client.setScreenAndShow(new CobbieConfigScreen(null));
			}
		});

		Cobbie.LOGGER.info("Cobbie client renderer + config screen registered (◕‿◕)");
	}
}
