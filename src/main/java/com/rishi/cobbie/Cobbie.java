package com.rishi.cobbie;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.command.CobbieCommands;
import com.rishi.cobbie.command.SpawnCobbieCommand;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.memory.CobbieMemory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint: entity type, config, commands, and the two server-side pumps.
 *
 * <p>Chat handling and the tick pump live here rather than in a server entrypoint because Fabric
 * has no {@code ServerModInitializer} in this version; the registration itself is guarded so a
 * dedicated server missing the message API cannot stop the mod loading.
 */
public class Cobbie implements ModInitializer {
	public static final String MOD_ID = "cobbie";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static int ticksSinceMemorySave;

	@Override
	public void onInitialize() {
		CobbieEntities.register();          // entity type must exist before anything spawns one
		CobbieConfig.loadAndApply();        // reads config/cobbie.json; does no network I/O
		// Registered on both sides: the server sends it from mobInteract, the client draws from it.
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
				.register(com.rishi.cobbie.net.CobbiePanelPayload.TYPE,
						com.rishi.cobbie.net.CobbiePanelPayload.CODEC);
		SpawnCobbieCommand.register();
		CobbieCommands.register();
		try {
			net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register(
					(message, sender, params) -> CobbieBrain.handleChat(message, sender));
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				CobbieBrain.onServerTick(server);
				// Memory writes are batched, not per-message: 30s is invisible to the player and
				// keeps a chatty session from rewriting the file on every turn.
				if (++ticksSinceMemorySave >= 600) {
					ticksSinceMemorySave = 0;
					CobbieMemory.save();
				}
			});
			LOGGER.info("Cobbie {} ready - say his name (◕‿◕)", version());
		} catch (Throwable t) {
			LOGGER.warn("Cobbie tick wiring unavailable on this side: {}", t.toString());
		}
	}

	/** The real version from the mod metadata, so the boot line can be trusted as evidence. */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}
}
