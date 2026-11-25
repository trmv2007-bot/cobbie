# Cobbie 0.75.2 — the follow freeze that would not reproduce

Artifact: `cobbie-0.75.2.jar`. **25 of 25 rounds closed the gap in one session.** No production code changed.

`CobbieFollowTest` failed once, in one full-suite run, with:

```
CobbieFollowTest FAILED after 33225ms: java.lang.AssertionError: Cobbie did not close distance: before=14.142135620117188 after=14.142135620117188
```

Bit-identical over thirty seconds. That is not a walk that failed — a failed path still nudges him a few centimetres,
and the distance would move — it is a body that never started. Following is the thing he does constantly, so the
question is why a route that exists does not execute.

## What this slice is

`CobbieFollowProbeTest`: the fixture and the terrain of the failing test, looped twenty-five times in **one** session
rather than twenty-five worlds, so the state that might be causing it accumulates the way it did in the suite it
failed in. Same normal-preset world, same creative/peaceful settings, the owner teleported twelve blocks out on both
axes, and it measures whether he closes two blocks inside the same thirty-second window the failing test gives him.

On the first freeze it **stops and prints**: his job and its status, his plan and its id, the navigator's own answer
(`isDone`, `path`, `targetPos`), `inWall`, `onGround`, `inWater`, `passenger`, both positions, **the owner's chunk
loaded and entity-ticking state**, his last spoken line, and his position once a second for the ten seconds before the
freeze. Those are the four suspects the printout separates: no path and nothing re-asked for one; the twelve-tick
repath throttle landing in a gap; a body that cannot stand where it is; or a path that exists with something other
than the navigator holding him.

## The result: it did not reproduce

```
[probe] round 1, before the walk: t=29 dist=14.14 pos=162, 76, 162 navDone=true navTarget=none onGround=true inWater=false ownerChunkLoaded=true | following=true busy=false status="idle" job="none" plan=none latestPlanId=-1 navDone=true navPath=none navTarget=none inWall=false onGround=true inWater=false passenger=false pos=(162.5, 76.0, 162.5) owner=(172.5, 75.89044745325997, 172.5) ownerChunkLoaded=true ownerChunkSimulation=true said=""
[probe] round 5 of 25 closed the gap
[probe] round 10 of 25 closed the gap
[probe] round 15 of 25 closed the gap
[probe] round 20 of 25 closed the gap
[probe] round 25 of 25 closed the gap
[probe] follow: 25 of 25 rounds closed the gap in one session
[gametest] summary: 1 passed, 0 failed of 1 — {CobbieFollowProbeTest=ok}
```

**A one-in-thirty intermittent that will not appear in twenty-five repetitions of its own fixture is itself worth
knowing.** It narrows the cause: it is not the fixture, not the terrain pair, not the twelve-block geometry, and not
anything that accumulates over rounds in a live world. What is left is the *suite* context — fifty-odd classes before
it, each with its own world — which no per-class probe can reproduce.

## The one code path that fits, named as a hypothesis

`followOwnerTick` has exactly one early return that does nothing and says nothing:

```java
if (!level().isLoaded(owner.blockPosition())) return;
```

If the owner's chunk is not loaded, the follow loop asks for nothing, sets no target, narrates nothing, and retries
every twelve ticks forever — which is the observed fingerprint exactly: no path, no narration, no movement, distance
frozen to the millimetre. It is **not confirmed**: no run produced a freeze, and this slice changes no behaviour on
the strength of a guess. The probe's printout reports `ownerChunkLoaded` and `ownerChunkSimulation` as its first two
fields for whoever catches it next.

## Its own harness faults, fixed before believing the negative

Two failures along the way were mine, and both would have made a green meaningless:

- **A `-D` iteration count never reached the client.** Gradle's `-D` goes to the daemon; a run asked for three rounds
  and did twenty-five. The count now reads `COBBIE_FOLLOW_ITERATIONS` from the environment, which the forked client
  does inherit.
- **The loop killed him.** Round 21 failed with `no companion to reset`, and the server log said it plainly: *"hit the
  ground too hard"*. Twenty rounds of hill walking with no regeneration ends in a fall death — the loop's fault, not
  the body's. Health is restored at each reset now, and the first attempt that looked like a real red was a fixture.

## Arithmetic versus observed

- **Arithmetic:** 25 rounds of 25, counted by the probe; the class count for the run came from the harness summary
  line (`1 passed, 0 failed of 1`).
- **Observed:** both `[probe]` run logs above are copied from `build/follow-probe-*.log`.
- **Not verified:** the freeze itself. No run reproduced it, so there is no before-and-after for a fix, and the chunk
  hypothesis above is unconfirmed. The probe is registered last in the suite, so every future full run now includes
  twenty-five follow rounds and will print its state the first time it catches one. The unit tier was not re-run:
  nothing in `src/main` or `src/test` changed.
