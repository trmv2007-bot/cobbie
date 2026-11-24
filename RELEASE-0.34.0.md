# Cobbie 0.34.0 — a finished brew stopped leaving him stuck

Artifact: `cobbie-0.34.0.jar`. **178 unit tests green**, `CobbieBrewTest` green with a new stage.

A defect of mine from 0.28.0, found by measuring something else.

## What was wrong

`collectBrewed` returned its outcome without clearing the step in flight. Every other verb ends
through `clearActive()`; brewing was the one that did not. So a batch that reported success left the
entity still pointing at a job that no longer existed, and the **next** step of any plan paid for it:

```
cobbie put this enchanted book on my sword -> walk-to(), anvil(iron_sword)
  walk-to [failed) x1  — lost track of the brewing
  anvil iron_sword [failed) x2  — still busy with walk-to
```

The first step was handed the previous step's stale outcome, and the second was refused because the
first was still nominally running. Both failures are one root cause, and it is not in the plan.

## The fix

`endBrew(outcome)` clears the job and the step together, and every terminal path in `brewTick` and
`collectBrewed` goes through it — the successful take, the stand vanishing, the reagent consumed
with nothing brewed, the timeout, and the no-world case. Five returns, one exit.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.34.0.jar`.

**In game:** `CobbieBrewTest` gained a third stage, because "the brew worked" never covered "and he
is free afterwards". After the awkward potions land, a fresh plan must be **accepted** — a refusal
here reads `He was still busy after a finished brew: …` — and must reach `COMPLETED`:

```
[gametest] brew ok: 3 water bottles -> 3 awkward potions in a real stand, one nether wart spent, the
           stand emptied; a reagent the stand will not take was refused and cost nothing; and a plan
           right after a brew ran instead of hitting a job that had already finished
```

The regression is the one that bit: a plan whose step follows a brew.

**Not verified:** nothing beyond that shape. The fix removes state on the way out; it cannot make a
brew succeed that did not before, and no other verb was touched.

## Why this is its own release

It is a production defect, not a prompt or perception change, and folding it into either would have
made it impossible to say which change moved the verb-choice score. The stale step also explains one
grind attempt in the same session answering *"that didn't work — still busy with walk-to"* before it
gave up and only talked.
