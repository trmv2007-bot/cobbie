package com.rishi.cobbie.gametest;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.client.CobbiePanelScreen;
import com.rishi.cobbie.presence.Narrator;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Presence, in game: the tag says "idle" when he is idle, shift-right-click opens his panel rather
 * than pasting a paragraph into chat, and the panel shows his real bag and the steps of the job.
 *
 * <p>Also asserts the cadence rule from the outside — the chat lines he actually emitted while
 * working — because "act, then speak" is a claim about messages the client received, not about a
 * code path.
 *
 * Run with {@code ./gradlew runClientGameTest -PcobbieTests=Panel}.
 */
public final class CobbiePanelTest implements FabricClientGameTest {

	/** Everything the client is told, in order, as the client receives it. */
	private static final List<String> HEARD = java.util.Collections.synchronizedList(new ArrayList<>());

	static {
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
				(message, overlay) -> HEARD.add(message.getString()));
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		// The collector is process-wide; this test judges only what he said during its own job.
		synchronized (HEARD) {
			HEARD.clear();
		}
		try (TestSingleplayerContext world = CobbieTestSupport.createFlatWorld(context,
				Difficulty.PEACEFUL, WorldCreationUiState.SelectedGameMode.CREATIVE)) {
			CobbieTestSupport.awaitWorld(context);
			TestServerContext server = world.getServer();
			CobbieTestSupport.spawn(context, server);
			CobbieTestSupport.permit(server, "mining", true);

			// A job with a real step in it, and something in his bag for the panel to draw.
			BlockPos target = server.computeOnServer(s -> CobbieTestSupport.owner(s).blockPosition().offset(2, 0, 2));
			server.computeOnServer(s -> {
				// Placed through the world, not with /setblock: a command prints "Changed the block
				// at x, y, z" into the same chat this test screenshots, and that line would look
				// exactly like a coordinate leak from him.
				s.overworld().setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
				var cobbie = CobbieTestSupport.cobbie(s).orElseThrow();
				cobbie.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
				cobbie.addItem(new ItemStack(Items.DIRT, 16));
				cobbie.addItem(new ItemStack(Items.TORCH, 8));
				cobbie.offerPlan("get that stone out of the way",
						List.of(new ActionIntent(ActionVocabulary.MINE, "minecraft:stone",
								target.getX(), target.getY(), target.getZ(), "")));
				return null;
			});

			// ---- the gesture opens a screen ----
			var sneaking = CobbieTestSupport.rightClick(server, true);
			if (sneaking != net.minecraft.world.InteractionResult.SUCCESS) {
				throw new AssertionError("Shift-right-click on him returned " + sneaking + ", not SUCCESS");
			}
			try {
				context.waitForScreen(CobbiePanelScreen.class);
			} catch (Throwable t) {
				throw new AssertionError("Shift-right-click never opened his panel; the client screen was "
						+ context.computeOnClient(mc -> String.valueOf(mc.gui.screen())), t);
			}

			String status = context.computeOnClient(mc -> ((CobbiePanelScreen) mc.gui.screen()).shownStatus());
			List<String> steps = context.computeOnClient(mc ->
					new ArrayList<>(((CobbiePanelScreen) mc.gui.screen()).shownSteps()));
			List<ItemStack> items = context.computeOnClient(mc ->
					new ArrayList<>(((CobbiePanelScreen) mc.gui.screen()).shownItems()));

			if (status == null || status.isBlank()) {
				throw new AssertionError("The panel opened with no status on it");
			}
			if (steps.stream().noneMatch(step -> step.contains("mining"))) {
				throw new AssertionError("The panel's step list does not describe the job: " + steps);
			}
			boolean hasPickaxe = items.stream().anyMatch(stack -> stack.is(Items.IRON_PICKAXE));
			boolean hasTorches = items.stream().anyMatch(stack -> stack.is(Items.TORCH));
			if (!hasPickaxe || !hasTorches) {
				throw new AssertionError("The panel did not draw what he is carrying (pickaxe=" + hasPickaxe
						+ " torches=" + hasTorches + "): " + items);
			}

			java.nio.file.Path panelShot = context.takeScreenshot("cobbie-panel");
			System.out.println("[gametest] screenshot: " + panelShot);

			// A plain right-click must still not open it: the shift gate is doing something.
			context.computeOnClient(mc -> {
				mc.gui.setScreen(null);
				return null;
			});
			var standing = CobbieTestSupport.rightClick(server, false);
			if (standing == net.minecraft.world.InteractionResult.SUCCESS) {
				throw new AssertionError("A plain right-click opened the panel too (" + standing + ")");
			}

			// ---- idle means idle ----
			server.computeOnServer(s -> {
				CobbieTestSupport.cobbie(s).orElseThrow().cancelTask("panel test is done with the job");
				return null;
			});
			try {
				server.waitFor(s -> CobbieTestSupport.cobbie(s)
						.map(c -> c.statusWord().equals("idle"))
						.orElse(false), 300);
			} catch (Throwable t) {
				throw new AssertionError("Standing still with nothing to do, the tag said \""
						+ server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
								.map(c -> c.statusWord()).orElse("gone")) + "\" instead of idle", t);
			}
			try {
				server.waitFor(s -> CobbieTestSupport.cobbie(s)
						.map(c -> c.getCustomName() != null && c.getCustomName().getString().endsWith("idle"))
						.orElse(false), 100);
			} catch (Throwable t) {
				throw new AssertionError("The nameplate never said idle; it reads \""
						+ server.computeOnServer(s -> CobbieTestSupport.cobbie(s)
								.map(c -> String.valueOf(c.getCustomName())).orElse("gone")) + "\"", t);
			}
			context.waitTicks(20);
			java.nio.file.Path idleShot = context.takeScreenshot("cobbie-idle-tag");
			System.out.println("[gametest] screenshot: " + idleShot);

			// ---- what he actually said, as the client received it ----
			List<String> cobbieLines;
			synchronized (HEARD) {
				cobbieLines = HEARD.stream().filter(line -> line.contains("[Cobbie]")).toList();
			}
			List<String> narrated = cobbieLines.stream()
					.filter(line -> !line.contains("Right now:") && !line.contains("Connecting"))
					.toList();
			if (narrated.size() < 2) {
				throw new AssertionError("Working through one step produced fewer than two lines; he said: "
						+ cobbieLines);
			}
			for (String line : cobbieLines) {
				if (Narrator.mentionsCoordinates(line)) {
					throw new AssertionError("He read out a position: " + line);
				}
			}

			System.out.println("[gametest] panel ok: status=\"" + status + "\" steps=" + steps
					+ " items=" + items.stream().filter(s -> !s.isEmpty()).map(s -> s.getItem().toString()).toList()
					+ " idle-tag=idle lines=" + narrated);
		}
	}
}
