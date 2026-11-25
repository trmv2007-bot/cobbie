# Cobbie 0.60.0 — what he costs a server, measured

Artifact: `cobbie-0.60.0.jar`. **219 unit tests green**, `CobbiePerfTest` added. **No production code changed**,
and that is the finding.

Rishi's first two complaints were *"he is taking too much time to do anything"* and *"my PC gets laggy"*. The model
round trips were fixed and the brain moved off his GPU, but the mod has grown per-tick work since and nobody had
measured it.

## The numbers

Server tick duration, five samples per phase, median reported — the same world with and without him:

| Phase | µs/tick | samples |
|---|---|---|
| no companion | 616.9 | 534 594 617 662 778 |
| him idle (assistive) | 631.5 (+14.7) | 587 596 632 643 645 |
| him idle (creative, initiative allowed) | 642.0 (+25.2) | 566 629 642 649 663 |
| him working (a two-step mining plan) | 536.7 (−80.1) | 488 500 537 662 679 |
| after he is killed | 451.8 (−165.0) | 433 441 452 465 508 |

**His tick cost is below the drift of this measurement.** The ranges overlap completely and the sign flips: working
reads *cheaper* than nobody there, and the post-kill baseline is 165 µs below the pre-spawn one because the server's
average trends down over a session as chunks settle. A single reading here cannot tell a cost from noise, and the
honest answer is the one that was worth establishing: **nothing measurable at this resolution, so nothing to fix
and nothing to optimise blind.** What he does cost at this resolution is *not more than about 100 µs a tick*, which
is under 0.2% of a 50 ms tick.

## Model calls per minute

| Phase | calls/min |
|---|---|
| idle, assistive | **0.0** |
| idle, creative (initiative allowed) | **0.0** |
| working (two-step plan) | 2.4 |

**The initiative path asks the brain nothing** — it is deterministic from perception, as designed, so a companion
left standing still costs zero round trips. The 2.4 while working is the plan's own turns, and it is the number to
watch if "taking too long" ever comes back.

## Where the per-tick work actually is

| What | Interval | World scan? |
|---|---|---|
| initiative scan | 100 ticks (5 s) | yes — radius 6 around him, bounded |
| absorb experience | 4 ticks | a 1.5-block entity query |
| nameplate / status | 10 ticks | no |
| navigation stop when idle | 20 ticks | no |
| climb assist | 6 ticks, only while climbing | no |
| skipped-item cleanup | 600 ticks | no |
| perception snapshot | on demand, not per tick | — |

The only recurring world scan is the initiative's, once every five seconds, bounded to six blocks. That is the one
place a longer interval would cost nothing behaviourally, and it is already cheap enough that the measurement cannot
see it.

## What this release does not establish

- **Client frame time.** This machine has no screen capture and the probe reads the server, so the renderer, the
  panel and the audio sink are unmeasured. If "laggy" ever comes back, that is the half nobody has a number for.
- **The comparison is sequential, not interleaved.** The drift above (451 → 617 across one session) is larger than
  the effect, so the next version of this probe should alternate phases rather than run them in a row.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Perf`, `--no-build-cache --rerun-tasks`, numbers above; the probe prints and
  never asserts, so it can be re-run against any build without pretending to be a gate.
