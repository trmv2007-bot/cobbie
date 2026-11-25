# Cobbie 0.76.0 — the gate and the water are sampled, and the gate does not open

Artifact: `cobbie-0.76.0.jar`.

Stage 1 proved the **doorway** was crossed. The **gate** and the **water** in the same walk still only
proved he arrived: he could walk round the fence or wade the channel floor and the green looked
identical. Both now have a sampled trajectory that has to show the property.

**Stage 1 is red with today's product, and that is the finding.** He cannot pass a closed fence gate.
The assertions are not weakened to hide it. Details below.

## What each new assertion proves

**The gate**, exactly as the door works: a sample where he occupies the gate's own block column (same
x and z, y at the gate or one above it), plus the gate seen open at some point during the walk. It is
built closed, so opening it is part of the claim; the state at the crossing sample is recorded rather
than required, because vanilla shuts a door or gate the moment he is past it. The log prints the
crossing sample, the state there, the first sample it was seen open, and the sample count.

**The water**: every sample records `isInWater()` and his feet's `blockPosition().getY()`, against the
surface y of the channel's topmost water block. Two counts come out — samples in water, and samples in
water at or above the surface — and the log prints both plus a representative sample of each. A body
that stays below the surface across a channel is wading or walking its floor, not swimming it.

## The course changes, and what the first runs measured

All three are fixes to the course, not relaxations of the assertion:

- **The fence and the channel are now the width of the lane (33 blocks)**, not seven. With a seven-wide
  fence the first run's trajectory showed what the old green had been: the gate was never entered at
  all, because walking round the end was free. Same for the water.
- **The channel is dug three blocks into the ground.** It used to sit on top of it, with water two
  blocks above the surrounding ground, and the first run measured what that does: **24 wet samples
  with all but one outside the channel** — the water had spilled across the yard, so "he was in water"
  was true a dozen blocks from the channel and proved nothing. Dug in, its surface is level with the
  ground, it cannot flow out, and three deep is more than he can stand in. The raised lip on the far
  side is gone with it: the bank beyond is ordinary ground one block above the water, which is the
  single block he can climb.
- **The gate has headroom.** The three-tall fence fill left fence blocks directly above the gate, so
  the opening was 1.5 blocks high and his body is 1.8 — nothing could walk through it. Two clear blocks
  is what a body needs and what a village gate has.

## The finding: a closed fence gate is a wall to him

With the gate the only way past the fence he stops in front of it and never occupies its column:

```
[gametest] terrain: walked 700 samples, did not arrive; door 2, -59, 7 on sample 19, OPEN=false at that
sample, first seen OPEN on sample 21; gate never in its block column; water 0 samples in water
[gametest] stage 1, at the far end: alive, health 20.0/20.0 at 2, -60, 14 onGround=true
AssertionError: He never reached the end of the course. He is 2, -60, 14 onGround=true alive at
20.0/20.0; he said "open plains, sun is out".
```

He walks through the door, reaches the fence at z=15, and stays at z=14 for the whole deadline without
naming the gate — the "still walking" failure this class exists to catch. The cause is in vanilla, not
in the geometry, confirmed in the mapped bytecode of 26.3:

- `WalkNodeEvaluator.getPathTypeFromState` returns `PathType.FENCE` for a `FenceGateBlock` whose
  `OPEN` is false, so his planner prices a closed gate as a fence and will never route through it.
- `DoorInteractGoal`, the base of the only goal he has for this (`OpenDoorGoal`), checks
  `instanceof DoorBlock` and calls `DoorBlock.setOpen`. No vanilla goal opens a fence gate.

So the gate stage cannot pass until the mod opens fence gates itself. A scratch build with **only the
course's gate forced open** proves the assertions themselves work:

```
[gametest] terrain: walked 80 samples, arrived; door 2, -59, 7 on sample 19, OPEN=false at that sample,
first seen OPEN on sample 21; gate 2, -60, 15 on sample 43, OPEN=true at that sample, first seen OPEN on
sample 1; water 18 samples in water (first 2, -61, 19), 8 at the surface (first 2, -61, 19)
[gametest] stage 1, at the far end: alive, health 20.0/20.0 at 2, -60, 23 onGround=true
```

That scratch build is not what is committed: the shipped course keeps the gate closed.

## What a green does not mean

**A green at the gate would not mean he opened it.** The sample proves he occupied the gate's column and
that the gate was seen open; with the gate forced open it proves the body can pass. Whoever fixes the
gate should keep the closed-gate course, because that is the only version that tests opening.

**A green in the water proves he was wet and at the surface, not how far or how well he swam.** Eight
samples at the surface over a three-block channel is swimming, not a demonstration of stamina or of
finding a bank.

**The rest of the course is still not proven obstacle by obstacle.** The ledge is still only "he ended
up past it", and the door assertion is unchanged from 0.74.0.

**It still says nothing about climbing.** The course is all downhill after the first step on purpose: a
mob can drop any distance and climb exactly one block, so nothing here is evidence about a two-block
climb.

## Pre-existing, not from this slice

Master's Terrain class already fails **stage 4** (a destination inside stone) before this change:

```
AssertionError: Asked to walk into stone at 4, -60, 39 he did not say he cannot stand there; he said
"nothing left to do — the world had already done that"
```

A clean checkout of `origin/master` (0.75.0) reproduces it, so it is either 0.75.0's stale-objective
work or flakiness in that stage. It is untouched here; with stage 1 failing first it is not reached.

## Verification

- `compileJava compileGametestJava`, `--no-build-cache --rerun-tasks`: green.
- `cleanTest test`: **240 tests, 0 failed, 6 skipped** (27 classes), from `build/test-results/test/*.xml`.
- `runClientGameTest -PcobbieTests=Terrain`, `--no-build-cache --rerun-tasks`: **0 passed, 1 failed of 1**
  — the gate finding above, reproduced twice on the shipped code.
- Scratch run with the gate forced open (not committed): **1 passed, 0 failed of 1**, with the evidence
  quoted above.
