package com.rishi.cobbie.action;

/**
 * What one success claim rests on — the observation, not the intent.
 *
 * <p>Two kinds, and no third, because there are only two honest ones: something in the world was read
 * after the change ({@link World}), or the verb makes no claim about the world at all ({@link OwnAct} —
 * he spoke, he waved, he waited). A station's menu answering "yes", a boolean a method handed back, or
 * the plan step arriving where it was walking to are <b>not</b> in this list: those are the shapes that
 * produced four false successes in one week, and the difference between them and {@link World} is
 * exactly whether anything was re-read.
 *
 * <p>Both records require a sentence saying what was seen, and refuse a blank one. That is the whole
 * point of the type: a success now has to be able to answer "what did you see?" with something specific
 * enough to put in the report, and "it worked" is not an answer.
 */
public sealed interface ActionEvidence {

	/** A fact read off the world after the change: the block is air, the sign holds these words, the bag grew by two. */
	record World(String observed) implements ActionEvidence {
		public World {
			if (observed == null || observed.isBlank()) {
				throw new IllegalArgumentException("world evidence must say what was seen");
			}
		}

		@Override
		public boolean aboutTheWorld() {
			return true;
		}
	}

	/** His own act, with nothing in the world to read: speech, a wave, a flag he set on himself. */
	record OwnAct(String observed) implements ActionEvidence {
		public OwnAct {
			if (observed == null || observed.isBlank()) {
				throw new IllegalArgumentException("an act must at least say what he did");
			}
		}

		@Override
		public boolean aboutTheWorld() {
			return false;
		}
	}

	static World world(String observed) {
		return new World(observed);
	}

	static OwnAct ownAct(String observed) {
		return new OwnAct(observed);
	}

	/** The sentence saying what was seen. Never blank, for either kind. */
	String observed();

	/** Whether this claim was checked against the world, which is what every world verb has to be able to say. */
	boolean aboutTheWorld();
}
