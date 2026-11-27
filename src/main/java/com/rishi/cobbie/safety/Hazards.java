package com.rishi.cobbie.safety;

import com.rishi.cobbie.entity.CobbieEntity;
import com.rishi.cobbie.memory.OperationalMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The acts that can cost an owner a building, and the one rule that governs them.
 *
 * <p>Priming TNT, setting something alight and pouring lava are the only three things he can do in one
 * click that destroy what somebody else made, and until this class existed nothing stood between him and
 * them: {@code CobbieTailTest} proved he would prime TNT, and it proved it eight blocks from the owner.
 * A companion who does what he is told with a flint and steel is a hazard with good manners.
 *
 * <p><b>The rule, in one sentence:</b> he never primes TNT, sets a fire or pours lava within
 * {@link #RADIUS} blocks of the owner, the owner's bed or respawn point, or a place the owner named that
 * he remembers. That is the whole policy — one radius, one list of acts, one list of things protected —
 * and it holds whoever asked, because a fire beside your own house is a fire beside your own house
 * whether it was your idea or his. An owner who wants it done walks the work outside the radius, which is
 * also the honest way to say "not here".
 *
 * <p>Nothing here decides anything about a person: it answers "may he do this act at this square" and
 * hands back the line to say when the answer is no, so the refusal names what was refused and how far it
 * was from the thing being protected.
 */
public final class Hazards {

	/** How far from the owner, their bed, their respawn or a remembered build these acts are refused. */
	public static final int RADIUS = 16;

	/**
	 * How close his own unprompted tidying stops: eight blocks from the owner, their respawn point or a
	 * place they named.
	 *
	 * <p>A fire carries sixteen blocks and a broken block does not: what a pickaxe costs a build is the
	 * build itself, so the radius is the width of a room rather than the width of a blast. One number for
	 * the owner, his respawn and what he named, because they are one answer to "where his things are".
	 */
	public static final int BUILD_RADIUS = 8;

	/** The sentence a refusal ends with, so the reason is stated rather than implied. */
	public static final String WHY = "I keep fire, TNT and lava " + RADIUS
			+ " blocks clear of you, your bed and anything you have built that I remember";

	/** How far above and below the act a bed still counts as the bed it would burn. */
	private static final int BED_HEIGHT = 4;

	/** One bed is enough to refuse for; the nearest is the one the line names. */
	private static final int BEDS_TO_KEEP = 8;

	/** The acts, spelled as the thing he would be doing so the refusal reads as a sentence. */
	public enum Act {
		TNT("prime that TNT"),
		FIRE("set that alight"),
		LAVA("pour that lava");

		private final String phrase;

		Act(String phrase) {
			this.phrase = phrase;
		}

		public String phrase() {
			return phrase;
		}
	}

	/** Something the guard protects: what it is in the owner's words, and where it is. */
	public record Protected(String what, int x, int y, int z) {}

	private Hazards() {}

	/**
	 * The act this click would be, or null when it is an ordinary one.
	 *
	 * <p>The list is deliberately the items themselves: flint and steel and a fire charge are the two
	 * things that light, and a lava bucket is the one thing that pours a hazard. Reading it off the stack
	 * rather than off the verb means a new path to the same item cannot slip past the guard.
	 *
	 * @param clicked the block being clicked, or null when the item was used in the air
	 */
	public static Act actOf(ItemStack held, BlockState clicked) {
		if (held == null || held.isEmpty()) return null;
		if (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) {
			return clicked != null && clicked.is(Blocks.TNT) ? Act.TNT : Act.FIRE;
		}
		if (held.is(Items.LAVA_BUCKET)) return Act.LAVA;
		return null;
	}

	/**
	 * The refusal line, or null when the act is allowed here.
	 *
	 * <p>Pure arithmetic over plain coordinates: a test can hand it a list of places and check the
	 * distance, the wording and the "nearest wins" rule without a world.
	 */
	public static String refusal(Act act, int x, int y, int z, List<Protected> around) {
		if (act == null || around == null || around.isEmpty()) return null;
		long limit = (long) RADIUS * RADIUS;
		Protected nearest = null;
		long best = Long.MAX_VALUE;
		for (Protected place : around) {
			if (place == null) continue;
			long dx = (long) x - place.x();
			long dy = (long) y - place.y();
			long dz = (long) z - place.z();
			long distance = dx * dx + dy * dy + dz * dz;
			if (distance > limit || distance >= best) continue;
			best = distance;
			nearest = place;
		}
		if (nearest == null) return null;
		int blocks = (int) Math.max(1L, Math.round(Math.sqrt((double) best)));
		return "I will not " + act.phrase() + " " + blocks + " block" + (blocks == 1 ? "" : "s") + " from "
				+ nearest.what() + ": " + WHY;
	}

	/** Everything the guard protects near this square, and the refusal line if the act is too close. */
	public static String refusalFor(CobbieEntity body, Act act, BlockPos at) {
		if (body == null || act == null || at == null) return null;
		return refusal(act, at.getX(), at.getY(), at.getZ(), protectedPlaces(body, at));
	}

	/**
	 * What the owner built, as far as he can know it: their respawn point and every place they named.
	 *
	 * <p>This is the list the tidying guard uses, and the owner's own body is deliberately not on it. A
	 * companion who will not pick up stone within eight blocks of where his owner is standing has stopped
	 * being useful at exactly the moment he is being watched; what he must not do is rearrange a thing that
	 * was built. Burning is a different question and a different list — {@link #anchors}.
	 */
	public static List<Protected> structures(CobbieEntity body) {
		List<Protected> out = new ArrayList<>(1);
		if (body == null) return out;
		ServerPlayer owner = body.owner();
		if (owner != null) {
			ServerPlayer.RespawnConfig config = owner.getRespawnConfig();
			if (config != null && config.respawnData() != null
					&& config.respawnData().dimension().equals(body.level().dimension())) {
				out.add(spot("your respawn point", config.respawnData().pos()));
			}
		}
		UUID ownerId = body.getOwner();
		if (ownerId != null) {
			String here = String.valueOf(body.level().dimension().identifier());
			try {
				for (OperationalMemory.Entry entry : OperationalMemory.entries(ownerId)) {
					if (entry.kind() != OperationalMemory.Kind.PLACE || !entry.at()) continue;
					if (entry.dimension() == null || !entry.dimension().equals(here)) continue;
					out.add(new Protected("the place you call " + entry.subject(),
							entry.x(), entry.y(), entry.z()));
				}
			} catch (Throwable ignored) {
				// An unreadable note is one thing the guard cannot protect; the owner and their respawn
				// above still are, and refusing all tidying because a file is broken would stop him working.
			}
		}
		return out;
	}

	/**
	 * The owner's anchors in this dimension: themselves, their respawn point, and every place they named.
	 *
	 * <p>Split from {@link #protectedPlaces} because this half is cheap and the bed search is not: a caller
	 * asking about a thousand squares has to be able to ask once and measure.
	 */
	public static List<Protected> anchors(CobbieEntity body) {
		List<Protected> out = new ArrayList<>(4);
		if (body == null) return out;
		ServerPlayer owner = body.owner();
		if (owner != null && owner.level() == body.level()) {
			out.add(spot("you", owner.blockPosition()));
		}
		out.addAll(structures(body));
		return out;
	}

	/** Whether a square is inside {@link #BUILD_RADIUS} of anything in {@code anchors}. Pure arithmetic. */
	public static boolean nearOwnersWork(List<Protected> anchors, BlockPos at) {
		if (anchors == null || anchors.isEmpty() || at == null) return false;
		long limit = (long) BUILD_RADIUS * BUILD_RADIUS;
		for (Protected place : anchors) {
			if (place == null) continue;
			long dx = (long) at.getX() - place.x();
			long dy = (long) at.getY() - place.y();
			long dz = (long) at.getZ() - place.z();
			if (dx * dx + dy * dy + dz * dz <= limit) return true;
		}
		return false;
	}

	/**
	 * The places that make an act dangerous here: the owner himself, his bed, his respawn point, and the
	 * places he named.
	 *
	 * <p>The bed is found by looking rather than by being told, because a bed is a block and the game will
	 * not say whose it is — the radius is what turns "a bed nearby" into "the bed this would burn". The
	 * remembered places are {@link OperationalMemory.Kind#PLACE} entries in the dimension he is standing
	 * in, which is what "anything built by the owner that he remembers" can honestly mean: he cannot see
	 * who placed a block, so he uses what the owner said.
	 */
	private static List<Protected> protectedPlaces(CobbieEntity body, BlockPos at) {
		List<Protected> out = anchors(body);
		if (body.level() instanceof ServerLevel level) {
			int beds = 0;
			for (BlockPos pos : BlockPos.betweenClosed(at.offset(-RADIUS, -BED_HEIGHT, -RADIUS),
					at.offset(RADIUS, BED_HEIGHT, RADIUS))) {
				if (!level.getBlockState(pos).is(BlockTags.BEDS)) continue;
				out.add(spot("a bed", pos));
				if (++beds >= BEDS_TO_KEEP) break;
			}
		}
		return out;
	}

	private static Protected spot(String what, BlockPos pos) {
		return new Protected(what, pos.getX(), pos.getY(), pos.getZ());
	}
}
