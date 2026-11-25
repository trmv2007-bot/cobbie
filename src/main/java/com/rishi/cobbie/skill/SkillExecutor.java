package com.rishi.cobbie.skill;

import com.rishi.cobbie.action.ActionIntent;
import com.rishi.cobbie.action.ActionVocabulary;
import com.rishi.cobbie.config.CobbieConfig;
import com.rishi.cobbie.entity.CobbieEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs a learned {@link Skill} through the same {@link com.rishi.cobbie.task.TaskRunner} as any
 * other plan — there is no second execution path, which is what makes skill results verifiable.
 *
 * <p>Stored steps are position-relative wherever possible (the learner records them that way), so a
 * skill learned next to a tree replays sensibly next to the next tree. Absolute steps are replayed
 * literally, and every verb is re-checked against the owner's live permissions before anything
 * happens: a skill saved while mining was allowed cannot mine after mining is switched off.
 */
public final class SkillExecutor {

	/** Marker prefix on the plan request, so the completion pump can credit the skill. */
	public static final String REQUEST_PREFIX = "skill: ";

	private SkillExecutor() {}

	public record Outcome(boolean started, String message) {}

	/** @return whether the skill is now running, and the sentence to show the owner. */
	public static Outcome run(CobbieEntity cobbie, String rawName) {
		if (cobbie == null) return new Outcome(false, "no companion to run that");
		Optional<Skill> found = SkillRegistry.get(rawName);
		if (found.isEmpty()) {
			return new Outcome(false, "I do not know a skill called \"" + rawName + "\"");
		}
		Skill skill = found.get();
		if (!skill.enabled()) {
			return new Outcome(false, "\"" + skill.name() + "\" is switched off (/cobbie skills on "
					+ skill.name() + ")");
		}
		if (skill.steps().isEmpty()) {
			return new Outcome(false, "\"" + skill.name() + "\" has no steps");
		}
		if (cobbie.isBusy()) {
			return new Outcome(false, "I am busy: " + cobbie.currentTaskDescription());
		}

		BlockPos origin = anchorFor(cobbie, skill);
		if (origin == null) {
			return new Outcome(false, "\"" + skill.name() + "\" needs " + anchorId(skill)
					+ " nearby and I see none — I would be swinging at nothing");
		}
		List<BlockPos> gathered = gatherTargets(cobbie, skill, origin);
		int gatherStep = 0;
		List<ActionIntent> intents = new ArrayList<>();
		for (ActionStep step : skill.steps()) {
			String verb = ActionVocabulary.normalise(step.action());
			if (verb == null) {
				return new Outcome(false, "\"" + skill.name() + "\" contains a verb I cannot do: "
						+ step.action());
			}
			if (!CobbieConfig.permits(verb)) {
				return new Outcome(false, "my owner has not allowed \"" + verb + "\", so I cannot run "
						+ "\"" + skill.name() + "\"");
			}
			int x = step.relative() ? origin.getX() + step.x() : step.x();
			int y = step.relative() ? origin.getY() + step.y() : step.y();
			int z = step.relative() ? origin.getZ() + step.z() : step.z();
			if (gathered != null && ActionVocabulary.MINE.equals(verb)) {
				// A gather replays against the pile that is actually here. The offsets record the shape
				// of the pile it was learned on, and the next pile is a different shape — replaying the
				// learned geometry mined one block and swung at air for the rest. The operation is
				// "take N of this block", so each step takes the next nearest one; where the learned
				// spot still holds the right block it is used first, so an unchanged pile replays
				// exactly as it was learned.
				BlockPos target = gatherStep < gathered.size() ? gathered.get(gatherStep) : null;
				if (target == null) {
					return new Outcome(false, "\"" + skill.name() + "\" wants " + anchorId(skill)
							+ " and I can only see " + gathered.size() + " of them");
				}
				gatherStep++;
				x = target.getX();
				y = target.getY();
				z = target.getZ();
			}
			intents.add(new ActionIntent(verb, step.target(), x, y, z,
					step.data() == null ? "" : step.data()));
		}

		String refusal = cobbie.offerPlan(REQUEST_PREFIX + skill.name(), intents);
		if (refusal != null) {
			return new Outcome(false, refusal);
		}
		return new Outcome(true, "running \"" + skill.name() + "\" (" + intents.size()
				+ " steps, reliability " + Math.round(skill.reliability() * 100) + "%)");
	}

	/**
	 * The blocks a gather skill should take this time, nearest first: the learned spot when the right
	 * block is still there, then the nearest unclaimed ones of the same material. Null when the skill is
	 * not a gather, so an ordinary procedure keeps its recorded geometry.
	 */
	private static List<BlockPos> gatherTargets(CobbieEntity cobbie, Skill skill, BlockPos origin) {
		String anchor = anchorId(skill);
		if (anchor == null || !(cobbie.level() instanceof net.minecraft.server.level.ServerLevel level)) {
			return null;
		}
		long mineSteps = skill.steps().stream()
				.filter(st -> ActionVocabulary.MINE.equals(ActionVocabulary.normalise(st.action())))
				.count();
		if (mineSteps < 2) return null;   // one block is not a pile worth re-targeting

		List<BlockPos> chosen = new ArrayList<>();
		BlockPos learned = cobbie.blockPosition();
		for (ActionStep step : skill.steps()) {
			if (!ActionVocabulary.MINE.equals(ActionVocabulary.normalise(step.action()))) continue;
			BlockPos asLearned = step.relative()
					? origin.offset(step.x(), step.y(), step.z())
					: new BlockPos(step.x(), step.y(), step.z());
			if (matches(level, asLearned, anchor) && !chosen.contains(asLearned)) {
				chosen.add(asLearned);
			}
		}
		BlockPos feet = cobbie.blockPosition();
		List<BlockPos> nearby = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-ANCHOR_RADIUS, -3, -ANCHOR_RADIUS),
				feet.offset(ANCHOR_RADIUS, 3, ANCHOR_RADIUS))) {
			if (matches(level, pos, anchor) && !chosen.contains(pos)) nearby.add(pos.immutable());
		}
		nearby.sort((a, b) -> Double.compare(feet.distSqr(a), feet.distSqr(b)));
		for (BlockPos pos : nearby) {
			if (chosen.size() >= mineSteps) break;
			chosen.add(pos);
		}
		return chosen;
	}

	private static boolean matches(net.minecraft.server.level.ServerLevel level, BlockPos pos, String anchor) {
		if (!level.isLoaded(pos)) return false;
		String ask = anchor.contains(":") ? anchor : "minecraft:" + anchor;
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
				.getKey(level.getBlockState(pos).getBlock()).toString();
		return id.equals(ask);
	}

	/** The block a skill was learned against, when its steps were anchored to one. */
	private static String anchorId(Skill skill) {
		for (ActionStep step : skill.steps()) {
			String found = parsedAnchor(step);
			if (found != null) return found;
		}
		return null;
	}

	private static String parsedAnchor(ActionStep step) {
		if (step == null || step.data() == null) return null;
		for (String token : step.data().toLowerCase(Locale.ROOT).split("[\s;,+]+")) {
			if (token.startsWith("anchor=")) return token.substring("anchor=".length());
		}
		return null;
	}

	/**
	 * Where to stand the skill up again. A skill learned from blocks re-anchors to the nearest block it
	 * was learned against, so replaying "gather oak logs" next to a different tree works; a skill with no
	 * anchor replays from where he stands. Null means the anchor it needs is not in sight, which is a
	 * refusal rather than a swing at nothing.
	 */
	private static BlockPos anchorFor(CobbieEntity cobbie, Skill skill) {
		String anchor = anchorId(skill);
		if (anchor == null) return cobbie.blockPosition();
		if (!(cobbie.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
		String ask = anchor.contains(":") ? anchor : "minecraft:" + anchor;
		BlockPos feet = cobbie.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-ANCHOR_RADIUS, -3, -ANCHOR_RADIUS),
				feet.offset(ANCHOR_RADIUS, 3, ANCHOR_RADIUS))) {
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
					.getKey(level.getBlockState(pos).getBlock()).toString();
			if (!id.equals(ask)) continue;
			double d = feet.distSqr(pos);
			if (d < bestD) {
				bestD = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** How far he will look for the block a skill was learned against. */
	private static final int ANCHOR_RADIUS = 8;

	/**
	 * Called by the completion pump. A skill is credited or blamed once, when its plan actually
	 * settled — not when it was started.
	 */
	public static void recordForPlanRequest(String request, boolean success) {
		if (request == null || !request.startsWith(REQUEST_PREFIX)) return;
		String name = request.substring(REQUEST_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
		SkillRegistry.recordOutcome(name, success);
	}

	/** One-line-per-skill text for chat and the prompt. */
	public static String describeAll() {
		List<Skill> all = SkillRegistry.all();
		if (all.isEmpty()) return "no skills learned yet";
		StringBuilder sb = new StringBuilder();
		for (Skill s : all) {
			if (sb.length() > 0) sb.append('\n');
			sb.append(s.enabled() ? "  " : "  [off] ").append(s.name())
					.append(" — ").append(s.description())
					.append(" (").append(s.successes()).append(" ok / ").append(s.failures())
					.append(" failed, ").append(Math.round(s.reliability() * 100)).append("%)");
		}
		return sb.toString();
	}
}
