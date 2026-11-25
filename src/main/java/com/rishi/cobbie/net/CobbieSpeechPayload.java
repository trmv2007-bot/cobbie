package com.rishi.cobbie.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One line he said, sent to the players who could hear it, so their own machine can speak it.
 *
 * <p>Synthesis belongs to the listener, not the server: the voice is a local engine on the machine
 * with the speakers, a dedicated server has no voices at all, and a player with voice turned off
 * should never hear it. So the server sends the sentence and nothing else — no audio crosses the
 * wire.
 */
public record CobbieSpeechPayload(String line) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CobbieSpeechPayload> TYPE =
			new CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(
					"cobbie", "speech"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CobbieSpeechPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, CobbieSpeechPayload::line,
					CobbieSpeechPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
