# Cobbie 0.62.0 — the ratio experiment, taken and answered

Artifact: `cobbie-0.62.0.jar`. **219 unit tests green**; harness run twice with the gateway up.

## The experiment

0.61.0 found that the harness's pause between asks — a "40-tick" gap — was really 0–9 ticks, and that was a
plausible explanation for the plan drift nobody could attribute. With the pause fixed to what it claims, two runs:

| Run | Ratio | Follow-ups excluded |
|---|---|---|
| 1 | **8 right, 6 wrong** of 14 | 3 |
| 2 | **7 right, 7 wrong** of 14 | 2 |

**One ask of movement, so the ratio still moves and the harness stays a report.** The experiment was worth taking:
the pause was a real defect and is now correct, and the conclusion is earned rather than assumed. A single ask of
drift on a fourteen-ask suite with a stochastic model and follow-ups mixed in is what this instrument is; it is
not a score.

## What the describe asks do now, in both runs

```
run 1:  anvil(my best sword)          ← description reached the verb
        equip(best pickaxe)  COMPLETED ← description reached the verb and resolved
run 2:  anvil(<description>)          ← same
        equip(my best pickaxe) COMPLETED
```

The `equip` ask succeeds end to end in **both** runs — the words arrive, the resolver picks, the plan completes —
and the anvil ask reaches `anvil(<description>)` in both, failing afterwards on the anvil's own merits (no matching
book for that item), which is a different problem from resolution.

## A new, repeatable finding: claiming without doing

`"cobbie use my worst pickaxe"` produced **no action at all** in both runs, with the model answering perfectly:

> "Equipping my worst pickaxe — the worn one — now."

A reply that says it is doing something and queues nothing is discarded by the brain's honesty check, which is
correct — a companion must not narrate work it did not queue. But the effect is that the request is dropped even
though the model understood it. `"put this enchanted book on my sword"` did the same once in run 2. That is a
prompt/model behaviour, not the resolver, and it is the next thing worth fixing: the resolution path is fine and
never gets called.

## The shape we designed for, now measured

**6.0 model calls a minute while working, 0.0 idle, in both autonomy modes.** Thinking when asked, nothing when
not — which was the intention from the beginning and now has a number behind it rather than a hope.

## Also

`docs/AUDIT.md` now carries both halves of the vacuous-wait lesson: the pattern, and **why it survives its own fix**
— the same wait was reintroduced in two slices written after the fix, and neither guard could see it, because the
wrong helper is still the obvious thing to type. A check that runs by default is what catches it; making the shape
unwritable is what stops it; neither replaces the other.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- Two harness runs with `COBBIE_MODEL=pooled-deepseek` through the key-pool gateway, `--no-build-cache
  --rerun-tasks`, transcripts quoted above.
