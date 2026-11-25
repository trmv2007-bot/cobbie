# Cobbie 0.77.2 — an instruction that cannot be satisfied is refused, not announced

Artifact: `cobbie-0.77.2.jar`. **Full registry: 62 passed, 2 failed of 64** — the two being the brain-gated
`Smoke` and `Chat`. Unit tier green.

Every verb refuses sensibly on its own. Nothing had asked what happens when an instruction is satisfiable only by a
*combination*, or when one ingredient in that combination is absent and cannot be made. The rule is the same two
endings as the rest of the suite — **he finds the chain, or he names the missing link** — and the third outcome, a plan
announced that cannot complete, is the failure this slice exists to catch.

## The three cases, before and after

First run, as the finding:

```
1. "make me a torch" with coal and sticks, no table: COMPLETED — ... craft minecraft:torch [done) x1 — crafted 4 minecraft:torch (1x minecraft:torch); torches now 4
2. "make a bed" with wool and planks, no table:      COMPLETED — ... craft minecraft:white_bed [done) x1 — crafted 1 minecraft:white_bed; beds now 1
3. "make a bed" with wool and no planks, no logs:    refusal="null", plans 2 -> 3, last line "couldn't make that — i have no recipe for minecraft:bone and none in my bag"
```

Case 3 is two defects in one line: **a plan was announced** (`plans 2 -> 3`) for an instruction that cannot be
satisfied, and the reason it eventually gave was **bone** for a bed.

The diagnostic print of the game's own table is the explanation:

```
minecraft:dye_white_bed <- [[minecraft:white_dye], [minecraft:orange_bed, minecraft:magenta_bed, ...]]
minecraft:white_bed     <- [[minecraft:white_wool] x3, [minecraft:oak_planks, minecraft:spruce_planks, ...] x3]
```

A bed has two recipes. The planner tried the dye route first — white dye, bone meal, bone — and reported the first
*reason tried* rather than the recipe he was half-ready for. He was carrying the wool; what he was short of was planks.

After:

```
3. "make a bed" with wool and no planks, no logs: refusal="I can't make that — I have no recipe for minecraft:oak_log and none in my bag", plans 122 -> 122, last line "made it"
[gametest] summary: 1 passed, 0 failed of 1 — {CobbieUnsatTest=ok}
```

No plan, and the missing link named is a **log** — the bottom of plank → log, which is the thing the owner can go and
get.

## The two fixes

- **The reason reported is the one worth acting on.** `CraftChain` now keeps the failure from the option whose
  ingredient slots his bag most covers, instead of the first option tried. A bed's direct recipe fits 3 of 3 wool
  slots; the dye route fits none. `CraftChainTest` reproduces that choice exactly, and a second test pins that an item
  with no recipe at all still says so rather than nothing.
- **An unsatisfiable craft is refused before a plan exists.** `offerPlan` asks `unsatisfiableCraft` first: a plan made
  entirely of crafts whose chain cannot be satisfied never reaches the runner, so there is no promise in the chat and
  no project that appears only to die. Smelting is left to its own step (its target is the ore, not the result) and any
  mixed plan is judged step by step, which is why `CobbieCraftTest`'s permission-refusal case still behaves exactly as
  before — craft with the switch off is *feasible* and still fails at its step with the permission message.

## A finding, not a defect: he needs no crafting table

Case 2 made a bed with **no crafting table at all**, and a torch with none either, because this mod works the recipe
directly rather than through a grid. The brief expected a 3×2 table route; there is no such requirement in the code,
and inventing one would have been a station rule the game does not have. It is recorded here instead.

## The fixture faults on the way, because they were mine

- **Case 3 first inherited case 2's bed** — "I already have 1 white_bed" is a *true* answer to "make a bed", so the
  fixture had to empty his bag before asking. A multi-case fixture is reset between cases or the next case answers a
  different question.
- **`CobbiePersistenceTest`'s in-flight save raced the plan** — *"He woke into a save with a project in flight and said
  nothing"*, once in a long run and never alone. My first hardening made it permanent: a twenty-block walk in a normal
  world has no path, so the plan died before the write and the save legitimately carried nothing. It is now three short
  reachable legs, long enough that a save cannot outrun them, with a post-save liveness check that retries instead of
  asserting, and a failure message that says whether the save carried the marker or the marker was carried and he
  stayed silent. Three consecutive runs green.

## Evidence

```
$ ./gradlew runClientGameTest            (whole registry, no filter)
[gametest] summary: 62 passed, 2 failed of 64 — {CobbieSmokeTest=FAILED, CobbieChatTest=FAILED, ... CobbieUnsatTest=ok, CobbiePersistenceTest=ok}
```

**64 is the registry count, counted from `CobbieGameTests`** — the 63 in the standing list plus `CobbieUnsatTest`. A
`-PcobbieTests` filter is a subset and proves nothing about the classes it omits, which is how two reds survived a
whole day of filtered "full" runs.

## Arithmetic versus observed

- **Arithmetic:** 64 registry entries; 62 passed of 64; unit tier green (`cleanTest test`).
- **Observed:** every `[unsat]` and `[gametest]` line above is copied from the run logs, before and after.
- **Not verified:** the two brain-gated classes, which need a model and are red in every run tonight.
