# Cobbie 0.74.0 — he went through the doorway, not round it

Artifact: `cobbie-0.74.0.jar`.

The terrain course from 0.73.0 asserted that he **arrives** at the far end of the yard, and that was
weaker than it looked. The yard was open ground: an arrival proves he can walk 23 blocks in the
direction he was asked, and nothing about the door in the middle of it. A companion who walked round
every obstacle scored the same green as one who opened the door and went through.

Stage 1 now proves the crossing twice over, because either check alone can be satisfied by accident.

## The yard is walled

The wall on the door's plane is **33 blocks wide**, from the ground to nine blocks above the walkway,
with the closed oak door as its only gap. Going round the end costs 32 blocks more than the straight
line, past his `navigation_range` of 24 — the same width `CobbieDoorTest` uses for the same reason. The
ledge, gate, water channel and lip stay behind the wall, and the goal is still flat ground he can
stand on, not a point in the air.

## The trajectory is sampled

While he walks, his `blockPosition()` is read every 2 ticks — about 0.4 blocks of travel, well inside
one doorway — and the run requires at least one sample where:

- **x and z are the door's own**, and
- **y is the door's lower or upper half**,

and it records the door's `OPEN` state at that sample. The log prints the crossing sample, the door
state there, the first sample the door was seen open, and the total sample count, so the evidence is
in the run rather than only the verdict. A green means he occupied the door's block column, and a wall
across the lane means that column was the only way past it.

## What a green does not mean

**The rest of the course is still not proven obstacle by obstacle.** The ledge, gate, water and lip are
walked, but the only assertion about them is that he ends up at the far end alive with a valid stop
available; nothing samples whether he swam rather than walked the water bottom, or opened the gate
rather than climbed it. Stage 1 proves the doorway, not the other four.

**It also says nothing about climbing.** The course is all downhill after the first step, deliberately:
a mob can drop any distance and climb exactly one block, so a course that needs a two-block climb
would be testing the game's jump physics and not his navigation. Nobody should read a green here as
"he can climb".

## The rule being asserted, unchanged

Two outcomes are allowed: **he arrives**, or **he stops and names what stopped him**. Three failures are
each caught separately: still walking at the deadline (the loop), `blockPosition` at the goal with an
obstacle in the way (arrival from a distance), and stopped with nothing said. Health is printed at every
stage, and a dead companion at the goal fails.

## What the first run taught

The first version of the crossing check also required the door to be **open at the crossing sample**, and
it failed: he arrived, the sample was inside the door column, and the door read closed. That is not a
defect in him — vanilla `OpenDoorGoal` shuts the door the moment he is past it, so the sample often lands
one tick after it shut. The check now samples the door's state on every tick and requires it was **seen
open at some point during the walk** (it was built closed, so opening it is part of the claim), while the
state at the crossing is printed as evidence rather than required to be open.

## Verification

- `compileJava compileGametestJava`, `--no-build-cache --rerun-tasks`: green.
- `cleanTest test`: **238 tests, 0 failed, 6 skipped** (27 classes), parsed from
  `build/test-results/test/*.xml`.
- `runClientGameTest -PcobbieTests=Terrain`, `--no-build-cache --rerun-tasks`: **1 passed, 0 failed of 1**.
  Stage 1: `walked 90 samples, doorway crossed at 2, -59, 7 on sample 19 of 90, door OPEN=false at that
  sample, first seen OPEN on sample 21`, arrived at `3, -60, 25` at `20.0/20.0`. Stages 2–4 unchanged
  and green at `20.0/20.0` throughout.
