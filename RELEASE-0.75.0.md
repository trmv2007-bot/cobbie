# Cobbie 0.75.0 — a reload says what it lost

Artifact: `cobbie-0.75.0.jar`. **240 unit tests, 0 failed, 6 skipped.** `Lifecycle` and `Persistence` green in
game, 2 passed of 2.

A plan is memory. Its steps, its retries and how far it had got live in the runner and nowhere else, so a
world reload ends it — and until now it ended **in silence**. The owner came back to a companion standing
still with no word about the work that had been running, which is indistinguishable from a companion who
finished it, or one who forgot the owner entirely.

There are two halves to the fix and the second one is the one that matters.

## The line, once

`CobbieInterrupted` is saved with the entity: the request of the project that is in flight, written in
`addAdditionalSaveData` and cleared the moment the runner has nothing running and nothing waiting behind it.
A save taken while a project is moving therefore carries the name of that project and nothing else does.

On load, a non-blank marker sets a single pending flag and the flag is honoured on a tick — never in the read
method, which runs before he is in a world and before anyone is there to hear him:

```
[Cobbie] I lost track of what I was doing before the world reloaded — ask me again
```

He does not resume anything: there is nothing to resume. The marker is cleared as soon as the line is spoken,
so a save taken afterwards has nothing to say and a later login is quiet.

## The negative is the half that matters

A line that fires on every login is worse than no line at all: it is noise, and the owner learns to skip it —
the family `docs/AUDIT.md` calls *a rule whose miss is silent*, inverted. So the assertions come in the order
the rule deserves:

```
[gametest] persistence ok: same uuid, same owner, stone intact at 7, and a reload with nothing in flight was silent
[gametest] wake-up ok: "I lost track of what I was doing before the world reloaded — ask me again", nothing resumed, marker cleared
[gametest] wake-up is once-only: the next login said ""
```

The third line is a third world opened from a save taken *after* the line was said: the fact that he spoke
does not come back with it.

## A project that goes stale while it waits

The sixth lifecycle failure, and the quietest: the plan was right when it was made and the world moved on
before it started. Offered while he was busy, queued, and by the time it was adopted its first objective had
already been achieved by the world.

The fix is asked once, at adoption, through a new `Executor.alreadySatisfied(intent)` — the runner stays
world-free and the entity answers for `mine`, `place` and `walk-to`, the three verbs whose goal is a world
state that can be read back. A satisfied objective is marked done with the reason, and a plan made only of
those closes as COMPLETED:

```
[lifecycle] stale: COMPLETED — "nothing left to do — the world had already done that" (2 of 2 objectives were already done, 0 failed)
```

**Asking at adoption and not per step is the load-bearing detail.** `mine` on a block that was never there
is still an impossible step and still fails — the class's second scenario holds that, unchanged:

```
[lifecycle] impossible: FAILED — failed — mine that far log: 0/1 steps done, 1 failed, 1 retries. Details: mine minecraft:oak_log [failed) x2 — there is nothing at 1 -60 5 (and it did not re-announce)
```

Same observation, two different rules, separated only by *when* the question is asked. A target that
disappears after the plan starts is attempted and fails; a target that was already gone when the plan started
was the world doing the work. `TaskRunnerTest` pins both halves.

The test does not assert that steps exist: zero work is the correct amount of work for a project with
nothing to do. It asserts the status, the sentence, and the block states — the log the world removed is still
gone, and the post he was asked to place is still exactly the oak log it was. He touched neither.

## Found by the run

The first in-game run of this slice caught a defect the code review did not: the stale plan was recorded as a
**learned skill**, `get-the-log-and-the-post-sorted`, built out of two steps nobody performed. A log with
nothing in it, remembered for replay. `learnFromPlan` now refuses any plan that closed on already-done
objectives. The junk skill that run wrote into the game-test config also explains a red on the next run —
CREATIVE autonomy practised it as its first project and spent the 2400-tick cooldown before the interruption
scenario could start — which is its own small argument for the same guard.

## What is arithmetic and what was observed

- **Arithmetic:** 240 unit tests, 0 failures, 6 skipped, counted from the JUnit XML; 2 passed of 2 in game,
  with the class names in the summary line.
- **Observed:** every line above is a `[gametest]` or `[lifecycle]` line copied from
  `build/run/clientGameTest/logs/latest.log` of the run that ended green.
- **Not verified:** the line on a *real* client — the gametest's owner is a synthetic player, so "the chat
  reached a person" is asserted as "the owner-facing send happened and the entity recorded the line". And the
  marker was exercised across in-process world opens (`TestWorldSave.save()/open()`), not across a client
  restart.
