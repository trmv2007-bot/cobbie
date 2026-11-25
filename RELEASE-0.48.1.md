# Cobbie 0.48.1 — the gate asked the wrong question, and it was mine

Artifact: `cobbie-0.48.1.jar`. **179 unit tests green**, `CobbieMineTest` green, `CobbieInitiativeTest`
green across all four phases.

A regression fix for 0.45.0, found by the owner: **`CobbieMineTest` was red on `master`** with

```
I need a proper tool for minecraft:stone and have none
```

in a world where he was holding nothing and breaking stone was the whole job.

## Two questions, not one

The 0.45.0 gate replaced "hardness above 1.5 needs a tool" with `requiresCorrectToolForDrops()`, which is
right about logs and wrong about instructions. Mine it asserted a rule that answers *"will I get the
drop?"* on a path where the owner asked *"will the block be gone?"*

- **`dropRefusal`** — the gate a **project** must pass. A project promises to gather, so stone
  bare-handed is not worth announcing: a hole and nothing in the bag.
- **`breakRefusal`** — the gate an **owner's instruction** must pass, and a weaker question on purpose.
  If the owner wants a tunnel through stone, an empty-handed break that yields nothing is exactly what
  he asked for, and the report already says `(nothing dropped)`. Hands still do not take obsidian apart.

Conflating them refused *"dig through that stone"* for want of a pickaxe nobody needed — in the one
place a player notices a lie, as a refusal that names a tool for a job that never wanted one.

## Evidence

```
[gametest] mine ok: block at 0, -60, 8 broke, final distance=2.52
[gametest] initiative: announced nothing with only un-mineable stone in sight over 900 ticks
[gametest] initiative ok: ... "gathering the oak log nearby" unprompted in CREATIVE → COMPLETED
[gametest] initiative: built on his own "building a cairn of cobblestone here" — 3 standing, 5 left
```

The initiative suite is the check that the split did not simply reopen the gate: stone with no pickaxe
is still not a project he announces, it is only not a refusal he makes when asked directly.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped**.
- `CobbieMineTest`: 1 passed of 1. `CobbieInitiativeTest`: 1 passed of 1, 209 s, both with
  `--no-build-cache --rerun-tasks`.
- The failing check on #42 was RubberDuck reporting `RuntimeError: Indexing failed (no_source_files)` —
  its own pipeline, with no comment on the code. It is not evidence either way, and is recorded as
  infrastructure rather than as a red.
