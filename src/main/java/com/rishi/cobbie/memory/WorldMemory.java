package com.rishi.cobbie.memory;

import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.OperationalMemory.Entry;
import com.rishi.cobbie.memory.OperationalMemory.Kind;
import com.rishi.cobbie.memory.OperationalMemory.Scored;
import com.rishi.cobbie.memory.OperationalMemory.Source;
import com.rishi.cobbie.station.Containers;
import com.rishi.cobbie.task.TaskPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The half of memory that touches the world: what is worth writing down as it happens, and what the
 * world says when a memory is checked.
 *
 * <h2>The world wins</h2>
 * A memory is a hint about where to look, never evidence for a claim. Three of the five kinds can be
 * re-read — a container's contents, a block's state, whether a place still has ground under it — and
 * every check has three outcomes, each acted on rather than logged:
 *
 * <table>
 *   <tr><th>the world says</th><th>what happens</th></tr>
 *   <tr><td>the same thing</td><td>the memory stands</td></tr>
 *   <tr><td>something else</td><td>the memory is <b>corrected</b> in place, never duplicated</td></tr>
 *   <tr><td>nothing is there</td><td>the memory is <b>dropped</b></td></tr>
 * </table>
 *
 * A square that is not loaded, or a memory from another dimension, is not a contradiction — it is a
 * question he cannot ask from here — so the memory is left alone rather than deleted for being out of
 * sight. An {@link Kind#OUTCOME} is history and a {@link Kind#PREFERENCE} is the owner's word; neither
 * is a fact about the world, so no read confirms or contradicts them and they are never treated as one.
 *
 * <p>Checks happen at both ends: the entries he is about to hand to the model are verified before they
 * are used, and a sweep every {@link #SWEEP_TICKS} corrects the rest so {@code /cobbie memory} does not
 * show him things that stopped being true.
 */
public final class WorldMemory {

	/** How many memories reach one prompt. Three good ones beat forty, and this is the fewest that can still say "and also". */
	public static final int MAX_SHOWN = 4;
	/** How often the whole store is re-read against the world. Twenty seconds. */
	public static final int SWEEP_TICKS = 400;
	/** How many different items one container fact names before it says "and more". */
	private static final int CONTENT_KINDS = 3;

	private WorldMemory() {}

	// ================= the sentence he is given =================

	/**
	 * The remembered lines that bear on this instruction and this situation, within the store's budget.
	 * "" when nothing does.
	 */
	public static String promptBlock(CobbieEntity body, UUID owner, String instruction) {
		try {
			if (body == null || owner == null || !CobbieConfig.isMemoryEnabled()) return "";
			if (!(body.level() instanceof ServerLevel level)) return "";
			BlockPos at = body.blockPosition();
			String situation = instruction + " " + (body.currentTaskDescription() == null ? "" : body.currentTaskDescription());
			// Rank more than he will show so a memory the world contradicts can be skipped without
			// handing the model one line fewer than there is room for.
			List<Scored> ranked = OperationalMemory.rank(owner, situation,
					level.dimension().identifier().toString(), at.getX(), at.getY(), at.getZ(), MAX_SHOWN * 3);
			List<Entry> shown = new ArrayList<>();
			for (Scored candidate : ranked) {
				if (shown.size() >= MAX_SHOWN) break;
				Entry checked = check(body, candidate.entry());
				if (checked != null) shown.add(checked);
			}
			return OperationalMemory.render(shown);
		} catch (Throwable t) {
			return "";
		}
	}

	// ================= what gets written down =================

	/** The owner's own statement: a place when it has coordinates, his word when it does not. */
	public static void learnFromOwner(CobbieEntity body, UUID owner, String subject, String fact,
			boolean at, int x, int y, int z) {
		if (body == null || owner == null || !CobbieConfig.isMemoryEnabled()) return;
		String dimension = body.level() == null ? "" : body.level().dimension().identifier().toString();
		OperationalMemory.remember(owner, at ? Kind.PLACE : Kind.PREFERENCE, subject, fact, dimension,
				x, y, z, at, Source.OWNER);
	}

	/**
	 * A container he has just used, read back from the world rather than from what he meant to do.
	 * Called after a take or a put has already been verified against the bag.
	 */
	public static void observeContainer(CobbieEntity body, BlockPos pos) {
		try {
			if (body == null || pos == null || !CobbieConfig.isMemoryEnabled()) return;
			UUID owner = body.getOwner();
			if (owner == null || !(body.level() instanceof ServerLevel level)) return;
			Container container = Containers.resolve(level, pos, body.owner(), false);
			if (container == null) return;
			OperationalMemory.remember(owner, Kind.CONTAINER, Containers.nameOf(level, pos),
					"holds " + contents(container), level.dimension().identifier().toString(),
					pos.getX(), pos.getY(), pos.getZ(), true, Source.SEEN);
		} catch (Throwable t) {
			// A memory that cannot be written is not worth failing a verb over.
		}
	}

	/**
	 * A station he has just worked at. An anvil chips and then dents, and which of the three it is now
	 * is a durable fact about that anvil that only the block itself can answer.
	 */
	public static void observeStation(CobbieEntity body, BlockPos pos) {
		try {
			if (body == null || pos == null || !CobbieConfig.isMemoryEnabled()) return;
			UUID owner = body.getOwner();
			if (owner == null || !(body.level() instanceof ServerLevel level) || !level.isLoaded(pos)) return;
			BlockState state = level.getBlockState(pos);
			OperationalMemory.remember(owner, Kind.BLOCK, stationName(state), stateWord(state),
					level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(), true, Source.SEEN);
		} catch (Throwable t) {
			// same as above: never let bookkeeping break the work
		}
	}

	/** What a job did and why it stopped. The reason is the half worth keeping. */
	public static void recordOutcome(CobbieEntity body, UUID owner, TaskPlan plan) {
		try {
			if (body == null || owner == null || plan == null || !CobbieConfig.isMemoryEnabled()) return;
			String request = plan.request() == null ? "" : plan.request().trim();
			if (request.isEmpty()) return;
			boolean done = plan.status() == TaskPlan.Status.COMPLETED;
			String reason = plan.failureReason() == null ? "" : plan.failureReason().trim();
			if (reason.isEmpty() && plan.abortReason() != null) reason = plan.abortReason().trim();
			String fact;
			if (done) {
				fact = "worked, " + plan.doneCount() + " step(s)";
			} else if (!reason.isEmpty()) {
				fact = "failed — " + reason;
			} else {
				fact = "failed";
			}
			String dimension = body.level() == null ? "" : body.level().dimension().identifier().toString();
			OperationalMemory.remember(owner, Kind.OUTCOME, request, fact, dimension, 0, 0, 0, false, Source.DONE);
		} catch (Throwable t) {
			// as above
		}
	}

	// ================= the owner's surface =================

	public static String listing(UUID owner) {
		return OperationalMemory.listing(owner);
	}

	public static boolean forget(UUID owner, int index) {
		return OperationalMemory.forget(owner, index);
	}

	/** Re-read every memory against the world. Bounded by the store's own cap, so this is a cheap pass. */
	public static void sweep(CobbieEntity body) {
		try {
			if (body == null || !CobbieConfig.isMemoryEnabled()) return;
			UUID owner = body.getOwner();
			if (owner == null) return;
			for (Entry entry : OperationalMemory.entries(owner)) {
				check(body, entry);
			}
		} catch (Throwable t) {
			// a sweep that throws must not take his tick with it
		}
	}

	// ================= checking one memory =================

	/**
	 * Re-read this memory against the world.
	 *
	 * @return the memory as it stands now — corrected if the world disagreed — or null when the world
	 *         says there is nothing there and the memory has been dropped
	 */
	private static Entry check(CobbieEntity body, Entry entry) {
		if (entry == null) return null;
		if (!entry.at() || entry.kind() == Kind.OUTCOME || entry.kind() == Kind.PREFERENCE) return entry;
		if (!(body.level() instanceof ServerLevel level)) return entry;
		UUID owner = body.getOwner();
		if (owner == null) return entry;
		if (!level.dimension().identifier().toString().equals(entry.dimension())) return entry; // not checkable from here
		BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
		if (!level.isLoaded(pos)) return entry;                                               // out of sight is not a contradiction
		return switch (entry.kind()) {
			case CONTAINER -> checkContainer(body, level, owner, pos, entry);
			case BLOCK -> checkBlock(level, owner, pos, entry);
			case PLACE -> checkPlace(level, pos, owner, entry);
			default -> entry;
		};
	}

	/** A container is where it was and holds what it now holds; the fact is refreshed from the slots. */
	private static Entry checkContainer(CobbieEntity body, ServerLevel level, UUID owner, BlockPos pos, Entry entry) {
		Container container = Containers.resolve(level, pos, body.owner(), false);
		if (container == null) {
			retire(owner, entry);
			return null;
		}
		String name = Containers.nameOf(level, pos);
		String fact = "holds " + contents(container);
		if (name.equals(entry.subject()) && fact.equals(entry.fact())) return entry;
		return OperationalMemory.remember(owner, Kind.CONTAINER, name, fact, entry.dimension(),
				pos.getX(), pos.getY(), pos.getZ(), true, Source.SEEN);
	}

	/** A station is still that station, and still in the state it was in. */
	private static Entry checkBlock(ServerLevel level, UUID owner, BlockPos pos, Entry entry) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			retire(owner, entry);
			return null;
		}
		String name = stationName(state);
		if (!name.equals(entry.subject())) {
			// A different block stands there now, so this is not the anvil he remembered.
			retire(owner, entry);
			return null;
		}
		String word = stateWord(state);
		if (word.equals(entry.fact())) return entry;
		return OperationalMemory.remember(owner, Kind.BLOCK, name, word, entry.dimension(),
				pos.getX(), pos.getY(), pos.getZ(), true, Source.SEEN);
	}

	/** A place he cannot stand at is not a place he can go to. */
	private static Entry checkPlace(ServerLevel level, BlockPos pos, UUID owner, Entry entry) {
		if (hasGround(level, pos)) return entry;
		retire(owner, entry);
		return null;
	}

	/** Something is at the square, or within three blocks of the feet: ground, a floor, a named block. */
	private static boolean hasGround(ServerLevel level, BlockPos pos) {
		for (int drop = 0; drop <= 3; drop++) {
			BlockPos probe = pos.below(drop);
			if (!level.isLoaded(probe)) continue;
			if (!level.getBlockState(probe).isAir()) return true;
		}
		return false;
	}

	private static void retire(UUID owner, Entry entry) {
		OperationalMemory.forget(owner, entry);
	}

	// ================= wording =================

	/** What a container holds, as a short phrase. "nothing" is a fact worth keeping too. */
	static String contents(Container container) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack == null || stack.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			counts.merge(id, stack.getCount(), Integer::sum);
		}
		if (counts.isEmpty()) return "nothing";
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> {
			if (!a.getValue().equals(b.getValue())) return Integer.compare(b.getValue(), a.getValue());
			return a.getKey().compareTo(b.getKey());
		});
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sorted.size() && i < CONTENT_KINDS; i++) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(sorted.get(i).getValue()).append(' ').append(sorted.get(i).getKey());
		}
		if (sorted.size() > CONTENT_KINDS) sb.append(" and ").append(sorted.size() - CONTENT_KINDS).append(" more");
		return sb.toString();
	}

	/** "anvil", "crafting table", "grindstone" — the noun a memory hangs on. */
	static String stationName(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (state.getBlock() instanceof AnvilBlock) return "anvil";
		return path.replace('_', ' ');
	}

	/** The durable part of a station's state: an anvil's three states, or which block it is. */
	static String stateWord(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (state.getBlock() instanceof AnvilBlock) {
			if (path.contains("chipped")) return "chipped";
			if (path.contains("damaged")) return "damaged";
			return "intact";
		}
		return path.replace('_', ' ');
	}
}
