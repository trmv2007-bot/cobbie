package com.rishi.cobbie.entity;

import com.rishi.cobbie.Cobbie;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The one case where a companion has to be kept alive by force: you crossed a portal and he has not.
 *
 * <h2>Why this exists</h2>
 * A player leaving a level releases its entity-ticking chunks. A companion standing in the overworld
 * is then still in the world and still saved, but he is <em>not ticking</em> — and nothing decides to
 * walk anywhere inside a tick that never happens. Walking to a portal cannot fix that from the entity
 * side, so the chunk has to be held open for as long as the walk is pending.
 *
 * <h2>What bounds it</h2>
 * <ul>
 *   <li><b>Only while the dimensions differ.</b> Same world means no ticket, ever — the follow loop is
 *       already doing the right thing there.</li>
 *   <li><b>A deadline.</b> Thirty seconds is several times a walk to a visible frame. Past it the ticket
 *       is released and he says he could not get through, rather than holding a chunk open forever.</li>
 *   <li><b>Every exit.</b> Crossing, dying, being removed, the owner coming back, and server stop all
 *       release it, not just the happy path.</li>
 * </ul>
 *
 * <p>Kept in one static table so that a companion whose chunk is not loaded can still be reached by the
 * server tick: he cannot report from a tick that does not run, so the registry is written whenever he
 * does tick and read from the server thread afterwards.
 */
public final class CrossDimensionFollow {

	/** How long a pending crossing may hold a chunk open. */
	private static final int TICKET_TICKS = 600; // thirty seconds

	private record Watch(UUID owner, ResourceKey<Level> level, BlockPos at, long expiresAt, boolean told) {
		Watch withTold(boolean value) {
			return new Watch(owner, level, at, expiresAt, value);
		}
	}

	private static final Map<UUID, Watch> WATCHES = new HashMap<>();

	private CrossDimensionFollow() {}

	/** Called from his own tick, whenever he is ticking: this is what makes him findable later. */
	public static synchronized void remember(CobbieEntity cobbie) {
		if (cobbie.getOwner() == null || !cobbie.isFollowing()) {
			WATCHES.remove(cobbie.getUUID());
			return;
		}
		WATCHES.put(cobbie.getUUID(),
				new Watch(cobbie.getOwner(), cobbie.level().dimension(), cobbie.blockPosition().immutable(),
						-1, false));
	}

	/** Called once per server tick, from outside any entity tick. */
	public static synchronized void tick(MinecraftServer server) {
		if (WATCHES.isEmpty()) return;
		Iterator<Map.Entry<UUID, Watch>> it = WATCHES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Watch> entry = it.next();
			UUID cobbieId = entry.getKey();
			Watch watch = entry.getValue();

			ServerPlayer owner = server.getPlayerList().getPlayer(watch.owner());
			if (owner == null) {
				it.remove();                       // owner offline: nothing to follow
				continue;
			}
			ServerLevel hisLevel = server.getLevel(watch.level());
			if (hisLevel == null) {
				it.remove();
				continue;
			}

			Entity found = hisLevel.getEntity(cobbieId);
			boolean inSameWorld = owner.level().dimension().equals(watch.level());
			if (inSameWorld || (found != null && found.level().dimension().equals(owner.level().dimension()))) {
				release(hisLevel, watch, cobbieId);
				it.remove();
				continue;
			}

			long now = server.getTickCount();
			if (watch.expiresAt() < 0) {
				watch = new Watch(watch.owner(), watch.level(), watch.at(), now + TICKET_TICKS, false);
				entry.setValue(watch);
			}
			if (now > watch.expiresAt()) {
				release(hisLevel, watch, cobbieId);
				it.remove();
				tellOnce(owner, "I could not get through to you — I am still on this side. Come back, or"
						+ " leave a portal where I can reach it.");
				continue;
			}
			// Hold the chunk he stands in open, so he ticks and can walk to the frame himself.
			hisLevel.setChunkForced(watch.at().getX() >> 4, watch.at().getZ() >> 4, true);
			if (found == null && !watch.told()) {
				entry.setValue(watch.withTold(true));
				Cobbie.LOGGER.info("Cobbie: holding his chunk open while the owner is in another dimension");
			}
		}
	}

	/** Release everything, for a server stop or a test teardown. */
	public static synchronized void releaseAll(MinecraftServer server) {
		if (WATCHES.isEmpty()) return;
		for (Map.Entry<UUID, Watch> entry : WATCHES.entrySet()) {
			ServerLevel level = server.getLevel(entry.getValue().level());
			if (level != null) release(level, entry.getValue(), entry.getKey());
		}
		WATCHES.clear();
	}

	/** Forget one companion: it died, was removed, or stopped following. */
	public static synchronized void forget(UUID cobbieId, MinecraftServer server) {
		Watch watch = WATCHES.remove(cobbieId);
		if (watch == null || server == null) return;
		ServerLevel level = server.getLevel(watch.level());
		if (level != null) release(level, watch, cobbieId);
	}

	private static void release(ServerLevel level, Watch watch, UUID cobbieId) {
		level.setChunkForced(watch.at().getX() >> 4, watch.at().getZ() >> 4, false);
	}

	private static void tellOnce(ServerPlayer owner, String line) {
		owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[Cobbie] §f" + line));
	}

	/** How many crossings are being held open; for a test to assert that nothing leaks. */
	public static synchronized int pending() {
		return WATCHES.size();
	}
}
