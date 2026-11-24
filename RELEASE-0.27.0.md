# Cobbie 0.27.0 — ladders and vines

Artifact: `cobbie-0.27.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game classes green except the ones that need a live model.

The first slice of travel, and the same complaint as the water one, one dimension over: you go up, he
does not follow.

```
[gametest] climb ok: went from y=-60 to -56.5 up a ladder
```

## Why he could not climb

Two reasons, and both had to go:

- **The pathfinder has no climb node.** There is no `PathType` for a ladder or a vine, so a target above
  one is not "a long path" — it is not a path at all. His walk-to gave up, and following gave up with it.
- **A mob standing at a ladder does not climb it.** A player climbs by holding forward against the
  rungs; nothing in a `PathfinderMob`'s movement does that.

## What he does now

- **A climb assist.** While he is on a climbable (`BlockTags.CLIMBABLE`, so ladders, vines and
  scaffolding) he gets a player's upward push, or downward when his goal is below, and he is held
  against the rungs rather than drifting off them.
- **A walk-to no longer abandons a target above a ladder.** When the navigator gives up but he is beside
  a climbable and the goal is above him, the step stays alive and the climb assist does the work; the
  path is re-tried once he is higher. The step deadline still bounds it, so this cannot hang.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.27.0.jar`.

**In game:** `CobbieClimbTest` is new and green. A three-wide, five-tall wall with a ladder on its face
and a walk to the top of it: he goes from y=-60 to -56.5 and stands on the wall.

**Not verified, worth saying plainly:**

- **Vines and scaffolding have not been climbed in a run.** They are in the same tag and take the same
  path, but the test builds a ladder.
- **Climbing while following you** is the case that motivated this and is *not* what the test proves —
  the test drives a walk-to. Following uses the same assist through the navigation target, but no run
  has watched him follow an owner up a ladder.
- **Going down** is one line of the assist and has not been exercised.
- **Portals are next**, and are the worse version of this failure: today he parks at the frame while you
  are in another dimension.
