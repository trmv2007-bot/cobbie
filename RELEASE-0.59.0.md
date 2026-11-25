# Cobbie 0.59.0 — the harness reports, and it says so

Artifact: `cobbie-0.59.0.jar`. **219 unit tests green**; harness run with the gateway up.

## The window is bracketed now

Each ask is bounded explicitly: the plan id is noted before the ask is sent, the harness waits until a plan
*appears* after that mark, and scores the first one. If a second plan turns up inside the same window it is a
follow-up and is **excluded, with its id printed**, rather than being left for the next ask to claim. A plan that
appears while no ask is pending is reported as a **stray** — a finding about the brain's follow-ups, not an
attribution problem.

That last part is new behaviour and it works: this run printed

```
VerbChoice FOLLOW-UP: a second plan (12) appeared inside the ask's window; excluded rather than scored
VerbChoice UNATTRIBUTED: 6 of 14 asks were answered by a FOLLOW-UP plan … the ratio below covers the 8
attributable asks
```

## The ratio still moves, so it is a report and not a measure

Four runs on the same code, with the gateway up and `pooled-deepseek` pinned:

| Run | Ratio |
|---|---|
| 0.56.0 | 11 right, 3 wrong of 14 |
| 0.57.0 before/after the prompt guidance | 9 / 5 both times |
| 0.58.0 with the resolver in five verbs | 7 / 7 |
| 0.59.0 with the bracketed window | 6 / 8 |

Some of that spread is real change — the fixture and the code moved — but not all of it: the describe asks still
show one plan of drift between the ask and the plan scored (`"equip my best pickaxe"` reading the *previous* ask's
plan id), which means a plan created during the trailing waits of the previous iteration is being claimed. Until
that is closed, **the ratio is a report, not a measure**, and the evidence this project stands on is:

- seven in-game classes that call the resolver directly with descriptions (`Anvil`, `Grind`, `Smith`, `Brew`,
  `Interact`, `Use`, `Describe`), all green;
- the per-ask lines, which are readable even when the total is not.

The one line that matters from this run is still the same one:

```
"cobbie put this enchanted book on my sword"  → anvil(my best sword)   ← a description reached the verb and resolved
```

## Two judgement calls, written down before a cleanup flattens them

- **`trade` is not wired to the resolver, and that is correct**: its costs come from the villager's offer, not
  from his bag. *"Buy pumpkin pie"* names the offer; there is nothing to resolve.
- **Each verb keeps its own refusal sentence.** The shared path decides *which stacks count*; the verb decides
  *what to say when none do*. Two suites went red when a shared path answered with one generic line, and a later
  consolidation will be tempted to do exactly that again. A shared code path is not a shared vocabulary.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- Harness with `COBBIE_MODEL=pooled-deepseek` through the key-pool gateway, `--no-build-cache --rerun-tasks`.
