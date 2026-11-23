package com.rishi.cobbie.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rishi.cobbie.brain.BrainPresets;
import com.rishi.cobbie.brain.CobbieBrain;
import com.rishi.cobbie.brain.ConnectionState;
import com.rishi.cobbie.brain.ConnectionTest;
import com.rishi.cobbie.brain.OllamaDiscovery;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.CobbieMemory;
import com.rishi.cobbie.skill.Skill;
import com.rishi.cobbie.skill.SkillRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /cobbie ...} — the owner's control surface. Everything here acts on the companion the
 * sender owns, and every effect is reported back rather than assumed.
 *
 * <ul>
 *   <li>{@code status} — real connection state, current task, last verified result, what he carries</li>
 *   <li>{@code follow} / {@code stay} / {@code cancel} — movement and plan control</li>
 *   <li>{@code permit <switch> <on|off>} — mining, building, combat, player_combat, exploration,
 *       collection, crafting (persisted; re-checked at execution time, not just when a reply is parsed)</li>
 *   <li>{@code autonomy <off|assistive|creative>}</li>
 *   <li>{@code skills [list|on|off|delete]} and {@code memory [show|clear]}</li>
 *   <li>{@code reload} — re-read config/cobbie.json without restarting the game</li>
 * </ul>
 */
public final class CobbieCommands {

	private CobbieCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("cobbie")
						.then(Commands.literal("status").executes(CobbieCommands::status))
						.then(Commands.literal("follow").executes(CobbieCommands::follow))
						.then(Commands.literal("stay").executes(CobbieCommands::stay))
						.then(Commands.literal("cancel").executes(CobbieCommands::cancel))
						.then(Commands.literal("inventory").executes(CobbieCommands::inventory))
						.then(Commands.literal("do")
								.then(Commands.argument("skill", StringArgumentType.greedyString())
										.executes(CobbieCommands::runSkill)))
						.then(Commands.literal("reload").executes(CobbieCommands::reload))
						.then(Commands.literal("test").executes(CobbieCommands::testConnection))
						.then(Commands.literal("autonomy")
								.then(Commands.argument("mode", StringArgumentType.word())
										.executes(CobbieCommands::autonomy)))
						.then(Commands.literal("permit")
								.then(Commands.argument("switch", StringArgumentType.word())
										.then(Commands.argument("value", StringArgumentType.word())
												.executes(CobbieCommands::permit))))
						.then(Commands.literal("skills")
								.then(Commands.argument("action", StringArgumentType.greedyString())
										.executes(CobbieCommands::skills)))
						.then(Commands.literal("memory")
								.then(Commands.argument("action", StringArgumentType.word())
										.executes(CobbieCommands::memory)))));
	}

	// ---- helpers ----

	private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
		return ctx.getSource().getPlayer();
	}

	private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
		ctx.getSource().sendSuccess(() -> Component.literal("§e" + text), false);
	}

	private static CobbieEntity requireCompanion(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = player(ctx);
		if (player == null) return null;
		if (!CobbieConfig.mayUse(player.getGameProfile().name())) {
			reply(ctx, "§cYou are not allowed to use Cobbie on this server.");
			return null;
		}
		CobbieEntity cobbie = CobbieBrain.companionOf(player);
		if (cobbie == null) {
			reply(ctx, "§cNo companion nearby that you may command. Use /spawncobbie.");
		}
		return cobbie;
	}

	// ---- subcommands ----

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ConnectionState state = CobbieBrain.lastState();
		reply(ctx, "§fAI: " + state + " — " + CobbieBrain.lastStateDetail());
		reply(ctx, "§fRequests in flight: " + CobbieBrain.inFlightCount());
		reply(ctx, "§fConfig: " + CobbieConfig.describe().replace("\n", "  |  "));
		CobbieEntity cobbie = CobbieBrain.companionOf(player(ctx));
		if (cobbie == null) {
			reply(ctx, "§fCompanion: none nearby");
			return 1;
		}
		reply(ctx, "§fCompanion: " + (cobbie.isFollowing() ? "following" : "staying")
				+ ", health " + String.format("%.0f", cobbie.getHealth()) + "/"
				+ String.format("%.0f", cobbie.getMaxHealth())
				+ ", at " + cobbie.blockPosition().getX() + " " + cobbie.blockPosition().getY()
				+ " " + cobbie.blockPosition().getZ());
		reply(ctx, "§fTask: " + cobbie.currentTaskDescription());
		reply(ctx, "§fLast result: " + cobbie.lastActionResult());
		reply(ctx, "§fCarrying: " + cobbie.describeInventory());
		return 1;
	}

	private static int follow(CommandContext<CommandSourceStack> ctx) {
		CobbieEntity cobbie = requireCompanion(ctx);
		if (cobbie == null) return 0;
		cobbie.startFollowing();
		reply(ctx, "§fCobbie will follow you again.");
		return 1;
	}

	private static int stay(CommandContext<CommandSourceStack> ctx) {
		CobbieEntity cobbie = requireCompanion(ctx);
		if (cobbie == null) return 0;
		cobbie.stopFollowing();
		reply(ctx, "§fCobbie will stay where he is.");
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		CobbieEntity cobbie = requireCompanion(ctx);
		if (cobbie == null) return 0;
		boolean stopped = cobbie.cancelTask("cancelled by owner");
		reply(ctx, stopped ? "§fStopped what he was doing." : "§fHe was not doing anything.");
		return stopped ? 1 : 0;
	}

	private static int inventory(CommandContext<CommandSourceStack> ctx) {
		CobbieEntity cobbie = requireCompanion(ctx);
		if (cobbie == null) return 0;
		reply(ctx, "§fCarrying: " + cobbie.describeInventory());
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		CobbieConfig.reload();
		reply(ctx, "§fConfig reloaded: " + CobbieConfig.describe().replace("\n", "  |  "));
		return 1;
	}

	/**
	 * Same check the config screen's button runs, so chat and GUI can never disagree: a real
	 * completion, classified into key rejected / model missing / unreachable / ready.
	 */
	private static int testConnection(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		BrainPresets.Preset preset = BrainPresets.infer(CobbieConfig.getProvider(), CobbieConfig.getBaseUrl());
		reply(ctx, "§7Testing " + preset.label() + " (" + CobbieConfig.getModel() + ")...");
		ConnectionTest.run(CobbieBrain.client(), preset.label()).thenAccept(report -> {
			if (report.healthy()) CobbieBrain.noteRequestSuccess();
			source.getServer().execute(() -> reply(ctx,
					(report.healthy() ? "§a" : "§c") + report.line()));
		}).exceptionally(ex -> {
			source.getServer().execute(() -> reply(ctx, "§cTest threw: " + ex.getClass().getSimpleName()));
			return null;
		});
		return 1;
	}

	private static int autonomy(CommandContext<CommandSourceStack> ctx) {
		String mode = StringArgumentType.getString(ctx, "mode");
		if (!CobbieConfig.setAutonomyMode(mode)) {
			reply(ctx, "§cUnknown mode. Use off, assistive or creative.");
			return 0;
		}
		reply(ctx, "§fAutonomy is now " + CobbieConfig.getAutonomyMode() + " — "
				+ CobbieConfig.getAutonomyMode().blurb() + ".");
		return 1;
	}

	private static int permit(CommandContext<CommandSourceStack> ctx) {
		String which = StringArgumentType.getString(ctx, "switch");
		String value = StringArgumentType.getString(ctx, "value");
		if (!CobbieConfig.setPermission(which, value)) {
			reply(ctx, "§cUnknown switch or value. Try: mining|building|combat|player_combat|exploration"
					+ "|collection|crafting on|off");
			return 0;
		}
		reply(ctx, "§fUpdated. " + CobbieConfig.describe().replace("\n", "  |  "));
		return 1;
	}

	/** Run a learned skill now, through the same verified planner as everything else. */
	private static int runSkill(CommandContext<CommandSourceStack> ctx) {
		CobbieEntity cobbie = requireCompanion(ctx);
		if (cobbie == null) return 0;
		String name = StringArgumentType.getString(ctx, "skill");
		com.rishi.cobbie.skill.SkillExecutor.Outcome outcome =
				com.rishi.cobbie.skill.SkillExecutor.run(cobbie, name);
		reply(ctx, outcome.started() ? "§f" + outcome.message() : "§c" + outcome.message());
		return outcome.started() ? 1 : 0;
	}

	private static int skills(CommandContext<CommandSourceStack> ctx) {
		String raw = StringArgumentType.getString(ctx, "action").trim();
		String lower = raw.toLowerCase(java.util.Locale.ROOT);

		if (lower.isEmpty() || lower.equals("list")) {
			List<Skill> all = SkillRegistry.all();
			if (all.isEmpty()) {
				reply(ctx, "§fNo skills learned yet. Finish a multi-step job with me and I will remember how.");
				return 1;
			}
			reply(ctx, "§fSkills:\n" + SkillRegistry.inspect());
			return 1;
		}
		if (lower.startsWith("delete ")) {
			String name = raw.substring("delete ".length()).trim();
			reply(ctx, SkillRegistry.delete(name) ? "§fDeleted \"" + name + "\"."
					: "§cNo skill called \"" + name + "\".");
			return 1;
		}
		if (lower.startsWith("on ") || lower.startsWith("off ")) {
			boolean enable = lower.startsWith("on ");
			String name = raw.substring(3).trim();
			reply(ctx, SkillRegistry.setEnabled(name, enable)
					? "§fSkill \"" + name + "\" " + (enable ? "enabled." : "disabled.")
					: "§cNo skill called \"" + name + "\".");
			return 1;
		}
		reply(ctx, "§cUsage: /cobbie skills [list|on <name>|off <name>|delete <name>]");
		return 0;
	}

	private static int memory(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = player(ctx);
		if (player == null) return 0;
		String action = StringArgumentType.getString(ctx, "action").trim().toLowerCase(java.util.Locale.ROOT);
		if (action.equals("clear")) {
			CobbieMemory.clear(player.getUUID());
			reply(ctx, "§fForgot everything about you.");
			return 1;
		}
		if (action.equals("show")) {
			reply(ctx, "§f" + CobbieMemory.inspect(player.getUUID()));
			return 1;
		}
		reply(ctx, "§cUsage: /cobbie memory show|clear");
		return 0;
	}
}
