# Cobbie 0.57.0 — the harness can say which plan answered which ask

Artifact: `cobbie-0.57.0.jar`. **219 unit tests green**; harness run twice with the gateway up.

## The attribution fix

The harness scored `lastPlan()`, which is the last plan **anywhere** — and a failed plan makes the brain ask the
model to comment, producing a follow-up plan that replaced the one being scored. That is how the same code
measured 11/14 and then 6/14 within ten minutes.

`TaskRunner` now keeps the plans it has created, and the harness reads `planAfter(base)` — the first plan created
after the ask was sent — printing its id and request with every line. A **self-check** refuses the ratio when an
ask's answer is a follow-up, and says how many asks it covered instead:

```
VerbChoice UNATTRIBUTED: 2 of 14 asks were answered by a FOLLOW-UP plan … the ratio below covers the 12
attributable asks.
```

An ask that produces *no* plan is an answer ("he said he cannot"), not an attribution failure, and it is scored.

## The measurement, both figures

The prompt change from 0.56.0 — tell the model to pass a description through rather than translate it into an id
— was held back until the harness could be trusted with the number.

| Run | Score | Describe asks |
|---|---|---|
| **before** the guidance | 9 right, 5 wrong of 14 (1 hijacked) | `equip(iron_pickaxe)`, `equip(iron_pickaxe)`, `anvil(iron_sword)` — every description collapsed to an id |
| **after** the guidance | 9 right, 5 wrong of 14 (2 hijacked) | `anvil(my best sword)` — the description survives into the tool call |

**The score did not move, and that is the finding.** The guidance works at the language level — the words reach
the action as words, which is exactly what it was for — but the anvil verb then failed on its own merits,
because **only `equip` and `use` consult the resolver**. A description arriving at `anvil`, `grind`, `smith`,
`brew` or `trade` is still handed to code that expects an id.

So the next slice is to wire the resolver into the rest of the item verbs, and the measurement to beat is this
one: the description arrives, and then something resolves it.

## The line worth keeping

**The model preferring an id is not a bug in the model.** It is what a model does when a tool signature asks for
an id; it is doing what it was told. If descriptions are what we want, the prompt has to say so and the harness
has to be able to see whether it did — which is what this release built.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- Two harness runs with `COBBIE_MODEL=pooled-deepseek` through the key-pool gateway, `--no-build-cache
  --rerun-tasks`, 14 measurable asks each, transcripts quoted above.
