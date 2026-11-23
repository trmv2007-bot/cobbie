package com.rishi.cobbie.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What shift-right-click sends the client so it can draw Cobbie's panel instead of pasting a chat
 * dump: who he is, what he is doing, the job and its steps, and the real contents of his bag.
 *
 * <p>His inventory only ever exists on the server, so it has to travel. The stacks go over the
 * inventory-aware stream codec — the same one the game uses for container screens — which means the
 * client draws precisely what he is carrying, tool wear and counts included.
 *
 * <p>Small on purpose: at most a job's steps and one bag, and the screen opens from a click, so
 * there is no reason to poll it.
 */
public record CobbiePanelPayload(
		int entityId,
		String name,
		String status,
		String job,
		List<String> steps,
		List<Integer> stepStates,
		List<ItemStack> items,
		int heldIndex) implements CustomPacketPayload {

	/** Which drawn slot is the one in his hand, or -1 when he is carrying nothing there. */
	public boolean isHeld(int index) {
		return heldIndex >= 0 && heldIndex == index;
	}

	/** 0 pending, 1 running, 2 done, 3 failed, 4 skipped — the screen colours each one. */
	public static final int PENDING = 0, RUNNING = 1, DONE = 2, FAILED = 3, SKIPPED = 4;

	public static final CustomPacketPayload.Type<CobbiePanelPayload> TYPE =
			new CustomPacketPayload.Type<>(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("cobbie", "panel"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CobbiePanelPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, CobbiePanelPayload::entityId,
					ByteBufCodecs.STRING_UTF8, CobbiePanelPayload::name,
					ByteBufCodecs.STRING_UTF8, CobbiePanelPayload::status,
					ByteBufCodecs.STRING_UTF8, CobbiePanelPayload::job,
					ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CobbiePanelPayload::steps,
					ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), CobbiePanelPayload::stepStates,
					ItemStack.OPTIONAL_LIST_STREAM_CODEC, CobbiePanelPayload::items,
					ByteBufCodecs.VAR_INT, CobbiePanelPayload::heldIndex,
					CobbiePanelPayload::new);

	public CobbiePanelPayload {
		name = name == null || name.isBlank() ? "Cobbie" : name;
		status = status == null ? "idle" : status;
		job = job == null ? "" : job;
		steps = steps == null ? List.of() : List.copyOf(steps);
		stepStates = stepStates == null ? List.of() : List.copyOf(stepStates);
		items = items == null ? List.of() : List.copyOf(items);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
