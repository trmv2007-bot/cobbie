# Cobbie 0.76.0 — he opens the gate, and the gate stage proves it

Artifact: `cobbie-0.76.0.jar`.

Stage 1 proved the **doorway** was crossed. The **gate** and the **water** in the same walk still only
proved he arrived: he could walk round the fence or wade the channel floor and the green looked
identical. Both now come off a sampled trajectory, and making the gate assertion honest exposed a gap
in the mod that had been invisible while walking round was possible.

## What each new assertion proves

**The gate**, exactly as the door works: a sample where he occupies the gate's own block column (same
x and z, y at the gate or one above it), plus the gate seen open at some point during the walk. It is
built closed, so opening it is part of the claim; the state at the crossing sample is recorded rather
than required, because he shuts the gate behind him the same way he shuts a door. The log prints the
crossing sample, the state there, the first sample it was seen open, and the sample count.

**The water**: every sample records `isInWater()` and his feet's y, against the surface y of the
channel's topmost water block. Two counts come out — samples in water, and samples in water at or above
the surface — and the log prints both with a representative sample of each. A body that stays below the
surface across a channel is wading or walking its floor, not swimming it.

## The gap this found: a closed fence gate was a wall to him

The first run with the fence walled off showed he never entered the gate column at all. The cause was
in vanilla, confirmed in the 26.3 mapped bytecode:

- `WalkNodeEvaluator.getPathTypeFromState` returns `PathType.FENCE` for a `FenceGateBlock` whose `OPEN`
  is false, so A* treats a closed gate as a fence and no route ever goes through one.
- `DoorInteractGoal`, the base of the only goal he had for this, checks `instanceof DoorBlock` and calls
  `DoorBlock.setOpen`. No vanilla goal opens a fence gate at all.

So a house with a door was a way through and a fenced lane was a wall, and the old course hid it by
leaving seven blocks of open ground on either side of the fence.

**The fix has two halves, and neither works alone.** `GateAwareNavigation` subclasses
`GroundPathNavigation` and installs a `WalkNodeEvaluator` subclass that overrides `getCachedPathType`
and `getPathType` — the two virtual entry points the evaluator's own neighbour search calls — and prices
a closed, unpowered gate as `DOOR_WOOD_CLOSED`, which he already routes through. `OpenFenceGateGoal`, at
the door goal's priority 3, opens the gate through the same path the game uses when a player does
(state, sound, `BLOCK_OPEN` event) and shuts it once he is clear.

- **No mixin.** A subclass reached through `createNavigation` changes only his own navigation; every
  other mob in the world still sees a closed gate as a fence.
- **A closed gate is not an open one.** The planner is told he *can* open it; nothing claims it is open.
  The stage's "seen open during the walk" evidence is unchanged.
- **A gate he cannot open stays a wall.** A redstone-powered gate is still priced as a fence and the goal
  ignores it, so the plan has no route and stops and names the obstacle instead of walking into a gate
  that will not budge.

## Course fixes, each measured rather than guessed

- **The fence and the channel are the width of the lane (33 blocks)**, not seven. With a seven-wide fence
  the first run showed the gate was never entered — he walked round the end, which is exactly what the
  old green had been measuring.
- **The channel is dug three blocks into the ground.** Sitting on top of it, the water spilled across
  the yard: the first run measured **24 wet samples with all but one outside the channel** (first wet at
  `11, -60, 16`), so "in water" was true a dozen blocks away and proved nothing. Dug in, its surface is
  level with the ground, it cannot flow out, and three deep is more than he can stand in. The raised lip
  went with it: the bank beyond is ordinary ground one block above the water, the single block he can
  climb.
- **The gate has headroom.** The three-tall fence fill had left fence blocks directly above the gate, so
  the opening was 1.5 blocks high and his body is 1.8 — nothing could walk through it. Two clear blocks
  is what a body needs and what a village gate has.

## What a green does not mean

**The gate sample does not prove he opened it by himself.** It proves he occupied the gate's column and
that the gate was seen open during the walk. With `OpenFenceGateGoal` in the build those are the same
thing in practice — nothing else opens a gate he is walking into — but the assertion is about the body
and the blockstate, not about which code changed the blockstate.

**The water assertion proves he was wet and at the surface, not how far or how well he swam.** Twelve
wet samples with eight at the surface over a three-block channel is swimming, not stamina.

**The rest of the course is still not proven obstacle by obstacle.** The ledge remains "he ended up past
it", and the door assertion is unchanged from 0.74.0.

**It still says nothing about climbing.** The course is all downhill after the first step on purpose: a
mob can drop any distance and climb exactly one block, so nothing here is evidence about a two-block
climb.

## Pre-existing reds, verified on a clean checkout

Three classes were already failing before this change, each reproduced on `origin/master` with my
changes stashed:

- `CobbieTerrainTest` stage 4: `Asked to walk into stone … he did not say he cannot stand there; he said
  "nothing left to do — the world had already done that"`.
- `CobbieWorldChangeTest`: `He stopped, but did not explain why; he said "all done"`.
- `CobbieWallRescueTest`: `… the line he said does not name the cause: "all done"`.

All three are the narrator's completion line no longer naming a cause, not pathfinding; 0.75.1 and 0.75.2
changed no production code. They are untouched here.

## Verification

- `compileJava compileGametestJava`, `--no-build-cache --rerun-tasks`: green.
- `cleanTest test`: **240 tests, 0 failed, 6 skipped** (27 classes), from `build/test-results/test/*.xml`.
- `runClientGameTest -PcobbieTests=Terrain`: stage 1 green —
  `walked 80 samples, arrived; door 2, -59, 7 on sample 19, first seen OPEN on sample 21; gate
  2, -60, 15 on sample 43, OPEN=false at that sample, first seen OPEN on sample 44; water 12 samples in
  water (first 2, -61, 19), 8 at the surface` — arrived `2, -60, 23` at `20.0/20.0`; then the
  pre-existing stage-4 red above.
- `runClientGameTest -PcobbieTests=Door,Follow,WorldChange,Climb,Terrain,WallRescue`:
  **3 passed, 3 failed of 6** — `Follow`, `Door` and `Climb` green; the three pre-existing reds as
  listed.
