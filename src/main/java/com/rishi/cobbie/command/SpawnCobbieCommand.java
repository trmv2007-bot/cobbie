package com.rishi.cobbie.command;

import com.mojang.brigadier.context.CommandContext;
import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.brain.OllamaDiscovery;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntities;
import com.rishi.cobbie.entity.CobbieEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * {@code /spawncobbie} — summon your companion and connect him to the AI.
 *
 * <p>Sequence, and why it is ordered this way: the entity is created and owned first (so he exists
 * even if the AI is down), then the configured provider is probed <em>asynchronously</em> through
 * {@link OllamaDiscovery} — the same code path {@code /cobbie status} uses, so the two can never
 * disagree. While that is in flight his nameplate reads "Connecting...". Every nameplate change and
 * chat message is pushed back onto the server thread; nothing here touches the world from a worker
 * thread.
 *
 * <p>The nameplate after the probe is honest about which of the four things happened: model ready,
 * server reachable but model unconfirmed, model missing (with the exact tag to configure and what
 * is actually installed), or server unreachable.
 */
public final class SpawnCobbieCommand {

	private SpawnCobbieCommand() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("spawncobbie").executes(SpawnCobbieCommand::run)));
	}

	private static int run(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only a player can spawn a companion."));
			return 0;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			source.sendFailure(Component.literal("No server world to spawn into."));
			return 0;
		}

		String name = player.getGameProfile().name();
		if (!CobbieConfig.mayUse(name)) {
			source.sendFailure(Component.literal("Cobbie is restricted to the players in "
					+ "allowed_players (config/cobbie.json)."));
			return 0;
		}

		// Duplicate / limit protection: no stacking a horde of companions on one owner.
		int limit = Math.max(1, CobbieConfig.getMaxCobbiesPerPlayer());
		List<? extends CobbieEntity> already = level.getEntities(
				EntityTypeTest.forClass(CobbieEntity.class), e -> player.getUUID().equals(e.getOwner()));
		if (already.size() >= limit) {
			source.sendFailure(Component.literal("You already have " + already.size()
					+ " companion(s) — the limit is " + limit + ". Talk to him instead."));
			return 0;
		}

		CobbieEntity cobbie = CobbieEntities.COBBIE.create(level, EntitySpawnReason.COMMAND);
		if (cobbie == null) {
			source.sendFailure(Component.literal("The game refused to create the companion entity."));
			return 0;
		}

		cobbie.setOwner(player.getUUID());
		cobbie.startFollowing();
		cobbie.setCustomName(Component.literal("Cobbie"));
		cobbie.setCustomNameVisible(true);
		Vec3 at = player.position();
		cobbie.setPos(at.x + 2.0, at.y, at.z + 2.0);
		if (!level.addFreshEntity(cobbie)) {
			source.sendFailure(Component.literal("Could not place the companion in this world."));
			return 0;
		}
		Cobbie.LOGGER.info("Cobbie spawned, owned by {}", name);
		player.sendSystemMessage(Component.literal("§e[Cobbie] §7Connecting to the AI..."));

		connectAsync(cobbie, player, level.getServer());
		return 1;
	}

	/** Probe the configured provider off-thread, then report through the nameplate + chat. */
	private static void connectAsync(CobbieEntity cobbie, ServerPlayer player, MinecraftServer server) {
		cobbie.setCustomName(Component.literal("§eConnecting..."));
		OllamaDiscovery.probeConfiguredAsync().thenAccept(probe -> {
			CobbieBrain.noteProbe(probe);
			server.execute(() -> apply(cobbie, player, probe));
		}).exceptionally(ex -> {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			OllamaDiscovery.Probe failed = new OllamaDiscovery.Probe(
					com.rishi.cobbie.brain.ConnectionState.REQUEST_FAILED, List.of(), null, cause.toString());
			server.execute(() -> apply(cobbie, player, failed));
			return null;
		});
	}

	private static void apply(CobbieEntity cobbie, ServerPlayer player, OllamaDiscovery.Probe probe) {
		if (cobbie.isRemoved() || player.isRemoved()) return;

		switch (probe.state()) {
			case MODEL_AVAILABLE -> {
				cobbie.setCustomName(Component.literal("§eCobbie"));
				cobbie.setCustomNameVisible(true);
				cobbie.wave();
				player.sendSystemMessage(Component.literal("§aI'm Ready To Play"));
				player.sendSystemMessage(Component.literal("§7(model " + probe.matchedModel() + ")"));
			}
			case CONNECTED -> {
				cobbie.setCustomName(Component.literal("§6Cobbie (model unconfirmed)"));
				player.sendSystemMessage(Component.literal("§6" + probe.describe()
						+ ". I can still try to talk — set \"model\" in config/cobbie.json if I stay quiet."));
			}
			case MODEL_MISSING -> {
				cobbie.setCustomName(Component.literal("§6Cobbie (model missing)"));
				boolean local = CobbieConfig.getProvider().startsWith("ollama");
				player.sendSystemMessage(Component.literal("§6My configured model \""
						+ CobbieConfig.getModel() + "\" is not installed."));
				player.sendSystemMessage(Component.literal("§7Available: "
						+ (probe.models().isEmpty() ? "none" : String.join(", ", probe.models()))));
				player.sendSystemMessage(Component.literal(local
						? "§7Set \"model\" to one of those, or run: ollama pull " + CobbieConfig.getModel()
						: "§7Pick one in /cobbie config (Provider → Test Connection tells you if it fits)."));
			}
			default -> {
				cobbie.setCustomName(Component.literal("§cCobbie (LLM offline)"));
				player.sendSystemMessage(Component.literal("§cCobbie is here, but the AI is unreachable — "
						+ probe.describe()));
				boolean local = CobbieConfig.getProvider().startsWith("ollama");
				player.sendSystemMessage(Component.literal(local
						? "§7Check Ollama is running on " + CobbieConfig.getBaseUrl()
								+ " (ollama serve), then /cobbie reload"
						: "§7Check the key and base URL in /cobbie config, then press Test Connection."));
			}
		}
		Cobbie.LOGGER.info("Cobbie connection state for {}: {} ({})",
				player.getGameProfile().name(), probe.state(), probe.error());
	}
}
