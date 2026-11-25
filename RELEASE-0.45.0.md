# Cobbie 0.45.0 — his own projects, and the tool gate that was lying

Artifact: `cobbie-0.45.0.jar`. **179 unit tests green**, `CobbieInitiativeTest` green in game, with
both halves of the evidence: nothing started in ASSISTIVE, and an unprompted project in CREATIVE that
finished.

First slice of milestone 8, the creative-autonomy half of the brief.

## A project is a plan

He picks a project from what he can see, and hands it to **the same `TaskRunner`** every owner
instruction goes through. No second execution path:

```java
Project(String request, String line, List<ActionIntent> steps)
```

That is the whole design decision, and it is why the rest comes free. A project that promises work
with no steps is a contradiction the code can see, because it *is* a plan. Terminal state, per-step
retries, the abandonment reason, and the panel row are the plan's, already built and already tested.

Choosing is deterministic from perception — no model call to decide, none per block:

- **gather** three or more logs or stone he can reach (radius 6, one below to two above his feet)
- else **flatten** three or more ground-level rubble blocks to grass level

Safe by construction: never lava or fire, never a block with a block entity (a chest is somebody's
storage, not rubble), never anything he cannot reach. At most three steps.

## When he may start one

Every condition is a separate permission with a reason, in `initiativeTick`:

- **creative autonomy only**, and only when **idle** — ASSISTIVE stays assistive;
- **`initiative_allowed`**, a switch of its own, default on. "Explore as you like" and "start work
  while I am standing here" are different permissions and are not the same flag;
- **owner quiet for ten seconds.** Starting a project in the middle of the owner's sentence is worse
  than waiting;
- **one at a time**, with a cooldown after the last one settles.

## Interruption

`offerPlan` cancels a running project the moment the owner asks for anything, with the reason *"the
owner asked me for something else"*, and the owner's plan takes its place. A project that cannot start
or cannot finish reports once and clears — it never sits in the panel as a stalled row.

## What the fix in the mining gate was for

The first run of the test failed, correctly:

```
failed — gathering the oak log nearby: 0/3 steps done, 3 failed, 3 retries.
Details: mine [failed) x2 — I need a proper tool for minecraft:oak_log and have none
```

That was not a fixture problem. `mineBlock` refused **any** block with hardness above 1.5 when his
hands were empty — so an oak log, which a bare hand takes in survival and which breaks in creative
outright, was refused as if it were obsidian. The gate asked the wrong question. What he loses without
a tool is the **drop**, so the condition is now `requiresCorrectToolForDrops()` (stone yields nothing
bare-handed; a log yields itself), and the old hardness rule only outside creative. This affected
owner instructions — *"chop those trees"* — exactly as much as initiative.

## Evidence

```
[gametest] initiative ok: started no project in ASSISTIVE over 20s, then started
           "gathering the oak log nearby" unprompted in CREATIVE and it reached COMPLETED —
           completed — gathering the oak log nearby: 3/3 steps done.
           Details: mine [done) x1 — mined minecraft:oak_log at 3 -60 4, stored 1 item(s) |
                    mine [done) x1 — mined minecraft:oak_log at 4 -60 4, stored 1 item(s) |
                    mine [done) x1 — mined minecraft:oak_log at 5 -60 4, stored 1 item(s)
```

The owner stands still, says nothing, and does not touch him; the negative runs first in the same
world with the same waiting, and asserts **zero** plans over twenty seconds.

## Dropped, with the reason

The brief's third project kind — **repair a damaged built structure** — is not here. Nothing he
perceives distinguishes "damaged" from "the owner's build as intended", and a guessed detector would
pull blocks out of somebody's house. Gathering and flattening can be wrong about a block; they cannot
be wrong about somebody's front wall. This is a deliberate deviation from the approved list, for the
owner to overrule.

## Known limitation

An initiative project can still fail on a precondition he only discovers by trying — no pickaxe, a
target that turns out to be out of reach. It reports the reason once and clears, per the contract, but
he does not yet check "can I actually do this" before announcing it.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped** (the opt-in network tier).
- `CobbieInitiativeTest`: 1 passed, 0 failed of 1 — with `--no-build-cache --rerun-tasks`.
- Nothing in this slice was verified without the game; the tool-gate change was verified by the
  project that used it.
