package com.rishi.cobbie.command;

import com.rishi.cobbie.Cobbie;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntities;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * /spawncobbie command — spawns Cobbie near the player, assigns the spawning
 * player as owner, shows "Connecting..." above his head while the LLM is being
 * reached, then says "I'm Ready To Play" once the AI model responds.
 */
public class SpawnCobbieCommand {

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("spawncobbie").executes(ctx -> {
				CommandSourceStack source = ctx.getSource();
				if (!(source.getEntity() instanceof ServerPlayer player)) {
					source.sendFailure(Component.literal("Only players can use this command."));
					return 0;
				}

				try {
					var level = player.level();
					var pos = player.position();

					// Spawn Cobbie near the player
					com.rishi.cobbie.entity.CobbieEntity cobbie = CobbieEntities.COBBIE.create((ServerLevel) level, EntitySpawnReason.COMMAND);
					if (cobbie == null) {
						source.sendFailure(Component.literal("Failed to create Cobbie entity."));
						return 0;
					}

					// Assign the spawning player as owner
					cobbie.setOwner(player.getUUID());

					// Set connecting status above head
					cobbie.setCustomName(Component.literal("§eConnecting..."));
					cobbie.setCustomNameVisible(true);
					cobbie.setPos(pos.x + 2.0, pos.y, pos.z + 2.0);

					if (!((ServerLevel) level).addFreshEntity(cobbie)) {
						source.sendFailure(Component.literal("Failed to spawn Cobbie in the world."));
						return 0;
					}

					Cobbie.LOGGER.info("Cobbie spawned and owned by {}", player.getName().getString());

					// Test LLM connection asynchronously (Ollama /api/tags)
					testLlmConnection().thenAccept(connected -> {
						if (connected && cobbie.isAlive()) {
							cobbie.setCustomName(Component.literal("Cobbie"));
							cobbie.setCustomNameVisible(true);
							player.sendSystemMessage(Component.literal("§aI'm Ready To Play"));
							Cobbie.LOGGER.info("Cobbie spawned and LLM connected for player {}", player.getName().getString());
						} else if (cobbie.isAlive()) {
							cobbie.setCustomName(Component.literal("§cCobbie (LLM offline)"));
							cobbie.setCustomNameVisible(true);
							player.sendSystemMessage(Component.literal("§cCobbie spawned but LLM is not reachable. Check config/cobbie.json"));
							Cobbie.LOGGER.warn("Cobbie spawned but LLM unreachable for player {}", player.getName().getString());
						}
					}).exceptionally(ex -> {
						Cobbie.LOGGER.warn("LLM connection test threw exception: {}", ex.getMessage());
						if (cobbie.isAlive()) {
							cobbie.setCustomName(Component.literal("§cCobbie (LLM error)"));
							cobbie.setCustomNameVisible(true);
						}
						return null;
					});

					return 1;
				} catch (Exception e) {
					Cobbie.LOGGER.error("Cobbie spawn command failed", e);
					source.sendFailure(Component.literal("§cError: " + e.getMessage()));
					return 0;
				}
			}));
		});
	}

	/** Ping the LLM base URL via GET /api/tags to check connectivity. */
	private static CompletableFuture<Boolean> testLlmConnection() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String url = CobbieConfig.getBaseUrl();
				if (url == null || url.isBlank()) return false;
				if (!url.endsWith("/")) url = url + "/";
				url = url + "api/tags";

				HttpClient client = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(CobbieConfig.getConnectTimeoutSeconds()))
						.build();
				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create(url))
						.timeout(Duration.ofSeconds(CobbieConfig.getReadTimeoutSeconds()))
						.GET()
						.build();
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				return response.statusCode() == 200;
			} catch (Exception e) {
				Cobbie.LOGGER.warn("LLM connection test failed: {}", e.getMessage());
				return false;
			}
		});
	}
}
