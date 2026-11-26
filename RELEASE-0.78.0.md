# Cobbie 0.78.0 — a step re-checks the world before it acts

Artifact: `cobbie-0.78.0.jar`.

Every verb in this mod re-checks the world when it starts: `mine` re-reads the block, `take` re-resolves the
container, `craft` re-plans the chain against the bag. What nothing did was ask *whether the step was still the
step its plan was made for*. That check existed exactly once, at plan adoption — so a plan adopted into a world
that then changed ran on against the old world, and a step that could no longer happen was retried at the same
coordinates, for the same target, until its retry budget ran out.

The second opinion named this as the highest-value gap left and it was right about the shape: our tests proved
verbs succeed in a world built to agree with them. None proved that a step notices when the world stops agreeing.

## The rule

One predicate, two moments. `Executor.checkStep(intent)` answers what the world says about a step's
preconditions, and it is asked when a plan is adopted and again **at the moment each step is about to run**. One
rule behind both, so the question and the answer cannot drift apart — the failure mode the audit called *"a
condition written for one caller, applied to all of them"*, run the other way.

Four answers, and the difference between the last two is the whole point:

| verdict | means | what the runner does |
|---|---|---|
| `READY` | he looked, and the thing the step needs is as the plan left it | run it |
| `SATISFIED` | the objective already holds | at adoption: a truthful completion. At a step's turn: the work went out from under him, so say so |
| `BROKEN` | the precondition no longer holds; `changed` says what in words | one bounded replan, else stop naming what changed |
| `UNKNOWN` | he could not look — an unloaded chunk, a container that answers nothing | run the step and let it fail with its own reason |

`UNKNOWN` is deliberately its own answer rather than either of the others. *"I cannot see it"* is not evidence
that it changed and must never be read as evidence that it is done; folding it into `READY` would be the
allowlist miss this project keeps re-learning, and folding it into `BROKEN` would abandon plans over a chunk
border.

## "Satisfied" means two different things, and that is the design

`CobbieLifecycleTest` forced this, and it is the one place my first reading of the brief was wrong. Its stage 3
requires a plan whose objective the world finished **before adoption** to close as COMPLETED — *"nothing left to
do — the world had already done that"*. Its stage 2 requires a plan whose target is removed **after adoption** to
abandon with a reason. Both are "the block is air now"; the difference is only *when* he looked.

So the verdict is not the decision. At adoption, `SATISFIED` means there is nothing to do yet. At a step's turn
it means the plan promised this work and somebody else did it — which he must not claim, because *"all done"*
about work he did not do is the one failure this project exists to prevent. He stops and says the log went.

```
[lifecycle] impossible: mine that far log -> FAILED — failed — mine that far log: 0/1 steps done, 1 failed (step 1 (mine) there is nothing left at 1 -60 5 to break)
[lifecycle] stale: COMPLETED — "nothing left to do — the world had already done that" (2 of 2 objectives were already done, 0 failed)
```

## The replan is one verb wide, on purpose

A replan may move the **coordinates** and nothing else: the runner refuses any substitute that changes the verb,
the target or the quantity, so a substitute cannot become a different instruction wearing the same step number.
That constraint is enforced in `TaskRunner`, not by convention in the entity.

Given that constraint, a substitute is only honest where the coordinate is a *place to act from* rather than the
*thing asked for*. There are exactly two such cases here:

- **`place`** onto a square somebody built in, when the block is still in his bag — put it one square over,
  beside before above, same height.
- **`mine`** with a named block, when somebody else broke the one he was sent for — there is another oak log
  eight blocks away, and the step names which one it moved to.

Everything else stops and names the change. A chest that was emptied is not re-sourced from the chest next door
and a destination that no longer fits is not quietly moved: those coordinates *are* the instruction, and
substituting them would be answering a question nobody asked.

```
[stale] 3 squatted square: COMPLETED — dirt now at 10, -60, -5 instead of 10, -60, -4, spent 1;
        log: retrying place (10 -60 -4 is minecraft:stone now, where i was going to build);
             replanned to place minecraft:dirt at 10 -60 -5 because 10 -60 -4 is minecraft:stone now,
             where i was going to build; place: placed minecraft:dirt at 10 -60 -5
```

That log line is the whole slice in miniature: the retry found the change, the replan moved the step, the
material was spent exactly once, and the plan says where it actually put the block.

## The arrival gate, because a walk is where the world gets to change

A check at the step's turn only catches a change between adoption and the first tick of the step. The dangerous
interval is the walk: `begin` is called, forty ticks pass, and *then* he touches the thing. So the same check runs
again at the hand-over — the moment he is within reach and about to act — for any step he walked somewhere **else**
to do something. On a walk the arrival is the proof, and asking whether he can stand where he is standing answers
nothing, so a pure `walk-to` is exempt.

Only a non-ready verdict is acted on there, and only as a failure naming the change: *"the chest at 2 -60 -6 has no
coal in it now"* is a better report than *"there was nothing to take"* after a walk that a player watched.

## A rescue is evidence, never the whole story

The guard that lifts him out of a block fixes a symptom, and a symptom is not a diagnosis. It now logs the
offending position, the plan id and request, the step and its attempt count, the walk repaths already spent, a
running rescue count and the recovery destination — and the position goes into the plan's own log, through the
cancel reason, so the plan that put him there carries the evidence too. A rescue that only ever appears in the
process log is a rescue nobody reading the plan will see.

## What this does not cover

- **Craft.** A missing ingredient is *already* reported at execution time: the chain is re-planned against the bag
  on every attempt and refuses with the bottom of the chain (`CraftChainTest`, `CobbieCraftTest`, `CobbieUnsatTest`
  all still green). A second feasibility rule in `checkStep` would be two answers to one question, which is the
  shape the audit warns about most.
- **The owner moving.** `give` resolves the owner at execution time already; there is no step whose coordinates
  name him, so there is nothing here to invalidate.
- **A whole-plan replan.** When a step must stop, the rest of the plan is abandoned rather than re-planned — every
  later step was planned against the world that just changed. Re-planning the plan needs the model, and that is
  the next slice, not this one.

## Evidence

Four mutations in one world, one class, each asserting the world afterwards and the line he said
(`CobbieStalePlanTest`, registered as entry 65 of `CobbieGameTests`):

```
[stale] stopped after a walk whose destination was filled in: FAILED — "i stopped — 2 -60 10 is minecraft:stone now, so i cannot stand there any more" (attempts 0, retries 0)
[stale] stopped after a chest emptied on the way to it: FAILED — "i stopped — the chest at 2 -60 -6 has no coal in it now" (attempts 1, retries 1)
[stale] 3 squatted square: COMPLETED — dirt now at 10, -60, -5 instead of 10, -60, -4, spent 1
[stale] stopped after a log somebody else broke: FAILED — "i stopped — there is nothing left at 0 -60 -4 to break" (attempts 1, retries 1)
[stale] done: one destination filled, one chest emptied, one square squatted and replanned, one log broken by somebody else
```

The first case's *attempts 0* is the discriminating fact, not a detail: the old code refused that walk inside
`startWalk`, which means an attempt. Zero attempts means the step was judged before it was handed over. Cases 2
and 4 show *attempts 1* — the walk happened, the arrival gate caught the change, and the step's next turn
abandoned instead of walking again.

The three failure modes are asserted separately, because they are three defects: **looping** (attempt count against
the budget, and the plan settling inside a tick bound — a loop never settles), **claiming completion** (status is
FAILED, and nothing after the causal line says "all done"), and **silent stop** (the reason is in the
`RecentLines` window, not only in a field). His health is printed at every stage, as the terrain course does.

The rescue's new line, from the same run:

```
Cobbie wall rescue #1: inside a block at 2 -60 2 (no plan in flight); recovery destination 1 -60 2
```

## Arithmetic versus observed

- **Arithmetic:** unit tier `cleanTest test` — 259 tests, 0 failed, 6 skipped, 30 classes (252 before this slice,
  +7 in `StaleStepTest`). In-game registry, whole run, `--no-build-cache --rerun-tasks` — **63 passed, 2 failed of
  65**, the two being `CobbieSmokeTest` and `CobbieChatTest`. 65 is the registry count in `CobbieGameTests`: 64
  before this slice plus `CobbieStalePlanTest`.
- **Observed:** every `[stale]` and `[lifecycle]` line above is copied from a run log.
- **Not verified:** the two brain-gated classes (`Smoke`, `Chat`) need a model and are red in every run tonight;
  nothing in this slice touches them.
- **Partly verified:** the wall-rescue line printed for real this run —
  `Cobbie wall rescue #1: inside a block at 2 -60 2 (no plan in flight); recovery destination 1 -60 2` — so the
  position, the count and the destination are observed. The plan fields read `no plan in flight` because
  `CobbieWallRescueTest` seals him with nothing running; they have not yet been seen populated under a live plan.
