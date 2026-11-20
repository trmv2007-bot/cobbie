package com.rishi.cobbie;

import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.command.SpawnCobbieCommand;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common mod initializer. Registers entities, config, chat brain, and commands.
 */
public class Cobbie implements ModInitializer {
	public static final String MOD_ID = "cobbie";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CobbieEntities.register();
		CobbieConfig.loadAndApply();
		SpawnCobbieCommand.register();
		try {
			CobbieBrain.register();
			LOGGER.info("Cobbie brain registered! (◕‿◕)");
		} catch (Throwable t) {
			LOGGER.warn("Cobbie brain unavailable on this side: {}", t.toString());
		}
	}
}
