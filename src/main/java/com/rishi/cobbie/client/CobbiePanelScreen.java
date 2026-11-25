package com.rishi.cobbie.client;

import net.minecraft.client.Minecraft;

import com.rishi.cobbie.net.CobbiePanelPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Shift-right-click on Cobbie, as a screen instead of a paragraph in chat.
 *
 * <p>Three things a player actually wants when they poke him: what he is doing, what the job has
 * left in it, and what is in his bag — with real item icons and counts, because "carrying:
 * minecraft:iron_pickaxe x1" in a chat line tells you much less than a pickaxe in a slot.
 *
 * <p>His model is drawn with the same vanilla helper a player's inventory uses, so the avatar turns
 * with the mouse and is the actual entity being rendered, not a picture of one. Everything on the
 * panel comes from the server snapshot sent with the click; the screen never asks the world for
 * numbers the client does not own (his inventory only exists server-side).
 */
public class CobbiePanelScreen extends Screen {

	private static final int PANEL_W = 344;
	private static final int COLS = 9;
	private static final int SLOT = 18;

	// The palette: one dark panel, one amber accent, three greys. Nothing else.
	private static final int PANEL = 0xF00F1218;
	private static final int BORDER = 0xFF2E3542;
	private static final int HEADER = 0xFF171B23;
	private static final int SLOT_BG = 0xFF1E232E;
	private static final int SLOT_EDGE = 0xFF394152;
	private static final int ACCENT = 0xFFFFC24B;
	private static final int TEXT = 0xFFE6E9EF;
	private static final int MUTED = 0xFF8B93A1;

	private static final int STATUS_DONE = 0xFF57D07A;
	private static final int STATUS_FAILED = 0xFFFF6B6B;
	private static final int STATUS_RUNNING = 0xFFFFC24B;
	private static final int STATUS_PENDING = 0xFF6F7A8C;

	private final CobbiePanelPayload data;

	private int panelX;
	private int panelY;
	private int panelH;
	private int avatarX;
	private int avatarY;
	private int avatarW;
	private int avatarH;
	private int jobX;
	private int jobY;
	private int gridX;
	private int gridY;
	/** Bag rows actually drawn, and slots the window had no room for. */
	private int shownRows = 1;
	private int hiddenSlots = 0;
	private int nameBudget = 120;
	private int levelOffset = 90;

	public CobbiePanelScreen(CobbiePanelPayload data) {
		super(Component.literal(data == null ? "Cobbie" : data.name()));
		this.data = data;
	}

	/** The panel floats over a live world, like a container screen: nobody wants the game to stop. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		int steps = data.steps().size();
		int jobH = 24 + Math.max(1, steps) * 11;
		int bodyH = Math.max(avatarHeight(), jobH);

		// Rows are capped by the window, not by the bag: the panel is the only way to close it besides Esc,
		// and a panel taller than the screen puts its own Done button out of reach. The rules live in
		// PanelLayout so the unit tier can check every window size without a client.
		String levelLine = levelLine();
		PanelLayout layout = PanelLayout.compute(data.items().size(), steps, height, font.width(data.name()),
				font.width(levelLine), PANEL_W - 28 - pillWidth() - 12);
		shownRows = layout.shownRows;
		hiddenSlots = layout.hiddenSlots;
		nameBudget = layout.nameBudget;
		levelOffset = layout.levelOffset;
		panelH = layout.panelH;
		panelX = (width - PANEL_W) / 2;
		panelY = Math.max(12, (height - panelH) / 2);

		avatarX = panelX + 14;
		avatarY = panelY + 38;
		avatarW = 96;
		avatarH = avatarHeight();
		jobX = panelX + 122;
		jobY = panelY + 38;
		gridX = panelX + 14;
		gridY = panelY + 34 + bodyH + 16;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(panelX + PANEL_W - 74, panelY + panelH - 28, 60, 20).build());
	}

	private static int avatarHeight() {
		return 104;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// No extractBackground here: the frame has already blurred the world behind an open screen,
		// and asking for a second blur in one frame is an error, not a darker background.

		// panel, header strip and border
		graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, PANEL);
		graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 28, HEADER);
		graphics.outline(panelX, panelY, PANEL_W, panelH, BORDER);
		graphics.fill(panelX, panelY + 28, panelX + PANEL_W, panelY + 29, ACCENT);

		// The name is whatever the owner chose — up to twenty-four characters — so everything after it is
		// placed from its measured width rather than a constant. A fixed x put the level line under a long
		// name, which is the kind of overlap nobody sees in a screenshot of a short one.
		String levelLine = levelLine();
		int pillLeft = panelX + PANEL_W - 14 - pillWidth();
		String drawnName = font.plainSubstrByWidth(data.name(), nameBudget);
		graphics.text(font, Component.literal(drawnName), panelX + 14, panelY + 9, ACCENT);
		statusPill(graphics, pillLeft, panelY + 8);
		// His own level, on the header line: the one thing a person would otherwise have to ask about.
		graphics.text(font, Component.literal(levelLine), panelX + levelOffset, panelY + 10, MUTED);

		avatar(graphics, mouseX, mouseY);
		job(graphics);
		bag(graphics, mouseX, mouseY);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	// ---- header ----

	private int pillWidth() {
		return font.width(data.status()) + 16;
	}

	private String levelLine() {
		return "LV " + data.xpLevel() + " · " + data.xpProgress() + "/" + data.xpNeeded();
	}

	private void statusPill(GuiGraphicsExtractor graphics, int x, int y) {
		int w = pillWidth();
		int colour = switch (data.status()) {
			case "idle" -> MUTED;
			case "thinking…" -> ACCENT;
			case "working" -> ACCENT;
			default -> TEXT;
		};
		graphics.fill(x, y, x + w, y + 12, 0xFF1F242E);
		graphics.outline(x, y, w, 12, colour);
		graphics.text(font, Component.literal(data.status()).withStyle(ChatFormatting.WHITE),
				x + 8, y + 2, colour);
	}

	// ---- left: him ----

	private void avatar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(avatarX, avatarY, avatarX + avatarW, avatarY + avatarH, SLOT_BG);
		graphics.outline(avatarX, avatarY, avatarW, avatarH, SLOT_EDGE);

		LivingEntity body = clientEntity();
		if (body != null) {
			// The vanilla inventory helper: the figure turns toward the cursor and is lit properly.
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,
					avatarX + 8, avatarY + 8, avatarX + avatarW - 8, avatarY + avatarH - 6,
					34, 0.0625f, mouseX, mouseY, body);
		} else {
			graphics.centeredText(font, Component.literal("Cobbie"), avatarX + avatarW / 2,
					avatarY + avatarH / 2 - 4, MUTED);
		}

		String where = data.items().isEmpty() ? "empty-handed" : "carrying " + itemCount() + " item(s)";
		graphics.centeredText(font, Component.literal(where), avatarX + avatarW / 2,
				avatarY + avatarH + 6, MUTED);
	}

	private LivingEntity clientEntity() {
		if (minecraft == null || minecraft.level == null) return null;
		Entity found = minecraft.level.getEntity(data.entityId());
		return found instanceof LivingEntity living ? living : null;
	}

	private int itemCount() {
		int n = 0;
		for (ItemStack stack : data.items()) {
			if (stack != null && !stack.isEmpty()) n += stack.getCount();
		}
		return n;
	}

	// ---- right: the job ----

	private void job(GuiGraphicsExtractor graphics) {
		int x = jobX;
		int y = jobY;
		graphics.text(font, Component.literal("WHAT I'M DOING").withStyle(ChatFormatting.BOLD), x, y, MUTED);
		y += 13;
		String title = data.job().isBlank() ? "Nothing right now — tell me what to do." : data.job();
		y = graphics.textWithWordWrap(font, Component.literal(title), x, y, PANEL_W - 136, TEXT);
		y += 5;

		List<String> steps = data.steps();
		if (steps.isEmpty()) return;
		for (int i = 0; i < steps.size(); i++) {
			int state = i < data.stepStates().size() ? data.stepStates().get(i) : CobbiePanelPayload.PENDING;
			int colour = stateColor(state);
			graphics.fill(x, y + 1, x + 3, y + 8, colour);
			String line = (i + 1) + ". " + steps.get(i);
			graphics.text(font, Component.literal(line), x + 9, y, state == CobbiePanelPayload.DONE ? MUTED : TEXT);
			y += 11;
		}
	}

	private static int stateColor(int state) {
		return switch (state) {
			case CobbiePanelPayload.RUNNING -> STATUS_RUNNING;
			case CobbiePanelPayload.DONE -> STATUS_DONE;
			case CobbiePanelPayload.FAILED -> STATUS_FAILED;
			case CobbiePanelPayload.SKIPPED -> STATUS_PENDING;
			default -> STATUS_PENDING;
		};
	}

	// ---- bottom: his bag ----

	private void bag(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, Component.literal("WHAT I'M CARRYING").withStyle(ChatFormatting.BOLD),
				gridX, gridY - 12, MUTED);

		List<ItemStack> items = data.items();
		int drawn = Math.min(items.size(), shownRows * COLS);
		for (int i = 0; i < drawn; i++) {
			int col = i % COLS;
			int row = i / COLS;
			int x = gridX + col * SLOT;
			int y = gridY + row * SLOT;
			graphics.fill(x, y, x + SLOT - 2, y + SLOT - 2, SLOT_BG);
			// The slot in his hand gets the accent, so "what is he actually holding" is a glance.
			graphics.outline(x, y, SLOT - 2, SLOT - 2, data.isHeld(i) ? ACCENT : SLOT_EDGE);

			ItemStack stack = items.get(i);
			if (stack == null || stack.isEmpty()) continue;
			graphics.item(stack, x + 1, y + 1);
			graphics.itemDecorations(font, stack, x + 1, y + 1);

			if (mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2) {
				graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
			}
		}
		if (hiddenSlots > 0) {
			graphics.text(font, Component.literal("+" + hiddenSlots + " more"), gridX,
					gridY + shownRows * SLOT + 2, MUTED);
		}
	}

	/** Lay the panel out for a given window, so a test can measure it without opening it on a screen. */
	public void layoutFor(int width, int height) {
		this.width = width;
		this.height = height;
		this.init();
	}

	// ---- test surface: what the screen actually shows ----

	/** The status shown in the pill. */
	public String shownStatus() {
		return data.status();
	}

	/** The step labels the panel draws, in order. */
	public List<String> shownSteps() {
		return data.steps();
	}

	/** The stacks the panel draws, in slot order. */
	public List<ItemStack> shownItems() {
		return data.items();
	}

	/** The level line the panel draws, for the gametest. */
	public String shownLevelLine() {
		return "LV " + data.xpLevel() + " · " + data.xpProgress() + "/" + data.xpNeeded();
	}

	/** Which drawn slot is in his hand, or -1. Asserted by the panel gametest. */
	public int shownHeldIndex() {
		return data.heldIndex();
	}

	/** Where the header's name ends, in pixels: the level line must start after it. */
	public int shownNameRight() {
		String levelLine = "LV " + data.xpLevel() + " · " + data.xpProgress() + "/" + data.xpNeeded();
		int pillLeft = panelX + PANEL_W - 14 - pillWidth();
		int room = pillLeft - 12 - (panelX + 14) - font.width(levelLine) - 12;
		return panelX + 14 + font.width(font.plainSubstrByWidth(data.name(), Math.max(24, room)));
	}

	/** Where the header's level line starts. */
	public int shownLevelLeft() {
		return shownNameRight() + 12;
	}

	/** The panel's bottom edge, which must sit inside the window. */
	public int shownPanelBottom() {
		return panelY + panelH;
	}

	/** Bag rows drawn, and slots the window had no room for. */
	public int shownRows() {
		return shownRows;
	}

	public int shownHiddenSlots() {
		return hiddenSlots;
	}
}
