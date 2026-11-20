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

		BlockPos origin = cobbie.blockPosition();
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
