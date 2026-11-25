# Cobbie 0.75.1 — the test that asked the wrong plan

Artifact: `cobbie-0.75.1.jar`. **`Lifecycle` green in isolation**, `51 passed, 2 failed of 53` in the full suite at
this slice's head, the two failures being the brain-gated `Smoke` and `Chat`.

This release changes no production code. It fixes the test that was scoring the wrong project, which is the reason
0.75.0's first full-suite run went red.

## The defect

`CobbieLifecycleTest`'s impossible-project scenario read `lastPlan()` after `awaitPlanSettled` — whatever plan was
newest — and asserted that it had not completed:

```
CobbieLifecycleTest FAILED after 46483ms: java.lang.AssertionError: An impossible project reported completion:
completed — looking around a bit: 2/2 steps done. Details: walk-to [done) x1 — arrived at -37 -60 9 |
walk-to [done) — already done — nothing left to do
```

`looking around a bit` is an **explore project he starts for himself** when idle, one tick in four hundred under
creative autonomy. The impossible plan failed, he went idle, an explore project started and finished inside the
window, and the test scored that instead. His impossible plan had failed exactly as it has always failed:

```
[lifecycle] impossible: mine that far log -> FAILED — failed — mine that far log: 0/1 steps done, 1 failed,
1 retries. Details: mine minecraft:oak_log [failed) x2 — there is nothing at 1 -60 4 (and it did not re-announce)
```

It is the harness-attribution lesson one layer down: **reading the newest thing is not reading the thing you asked
about.** A long-lived world is where it shows — plan ids began at 102 in the full run, so the explore gate had had
far more chances to fire than in a 35-second class run, which is why the isolated run kept passing while the full
suite kept failing.

## The fix

Every scenario now finds the plan **it** created, by id, and asserts on that plan's own record — the shape the
re-announcement loop in the same scenario already used:

- scenario 1 captures the plan id whose request starts `gathering the oak log nearby` and reads its status from
  `planRecord(id)`;
- scenario 2 takes the plan it offered (`mine that far log`), waits on *its* status reaching a terminal value, and
  asserts the re-announcement on ids after that plan — not after "the last plan";
- scenario 3 does the same for `get the log and the post`, and takes the `TaskPlan` itself through `planAfter(id - 1)`
  for the assertions that need more than the record.

The rules themselves are untouched. The impossible project still has to fail; the stale one still has to close as
`COMPLETED — "nothing left to do — the world had already done that"`; nothing is asserted about how many steps a plan
has.

## A second flaky precondition, fixed while proving it

With the identity fixed, scenario 1 went red for an unrelated reason — *"No initiative project started"* — and the
plans it printed were two explore projects that had walked him 30 blocks from the pile before initiative could choose
it. With the logs outside the 6-block initiative radius, `Initiative.choose` found nothing for the whole window. The
class now turns `explore` off for itself. An explore project is a competing self-start, not what the interruption
scenario tests.

## Evidence

The run that caught it, at `0e60bd9`:

```
[gametest] summary: 50 passed, 3 failed of 53 — {... CobbieLifecycleTest=FAILED}
```

The full suite after the fix, same 48-name filter:

```
[gametest] summary: 51 passed, 2 failed of 53 — {CobbieSmokeTest=FAILED, CobbieChatTest=FAILED, ...}
[gametest] CobbieLifecycleTest ok (64431ms)
```

Both remaining failures are the brain-gated pair, and both name the brain rather than a behaviour:

```
CobbieSmokeTest FAILED after 25796ms: java.lang.AssertionError: Cobbie never reached a connected brain; nameplate stayed "§eCobbie §8· §7idle" (is Ollama running with the configured model?)
CobbieChatTest FAILED after 32794ms: java.lang.AssertionError: Cobbie never reached a connected brain; nameplate was "§eCobbie §8· §7idle"
```

## What one run got wrong in between

An earlier full run of this same fix showed `CobbieFollowTest=FAILED` — *"did not close distance: before=14.142135620117188
after=14.142135620117188"*, bit-identical, so he did not move at all in thirty seconds of a hilly normal world. That
class runs **before** this one and nothing in this slice touches it; it passed in the run before and in the run after,
and in isolation on the same tree (`distance before=14.142135620117188 after=11.98672103881836`, `1 passed of 1`). It is
recorded rather than explained: a follow path that fails to solve once is a real intermittent and belongs in its own
slice, not in a note claiming it was nothing.

## Arithmetic versus observed

- **Arithmetic:** one run at 50/53, one at 51/53, both counted from the harness summary line — 53 classes because the
  48-name filter also matches `FarmProbe`, `TradeProbe`, `RedstoneProbe`, `DecorProbe` and `AnvilEnd`.
- **Observed:** every `[gametest]`, `[lifecycle]` and assertion line above is copied from the run logs.
- **Not verified:** the unit tier was not re-run for this slice — nothing in `src/main` or `src/test` changed, only a
  gametest class — and the brain-gated pair was never exercised with a live model.
