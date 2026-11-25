# Cobbie 0.63.0 — a claim is a claim, and silence is not an answer

Artifact: `cobbie-0.63.0.jar`. **222 unit tests green** (3 new), harness re-run with the gateway up.

## What the probe established before anything changed

`CobbieClaimProbeTest` asked the exact question: *is `doing` set, or is the reply undeclared?* In a clean world the
ask **worked** — one model call, no repair, and the plan carried the description through:

```
ask "cobbie use my worst pickaxe": model calls 1 (NO repair call)
  plan: #1 cobbie use my worst pickaxe -> completed — equip my worst pickaxe [done] — equipped minecraft:iron_pickaxe
```

So there was no regression in the repair path and the resolver was fine. The failure in the harness was a **context**
effect: with earlier asks in the transcript the model answers *"Equipping my worst pickaxe — the worn one — now."*
and queues nothing. The receiving end is where the bug was.

## The rule that let it through

The honesty check fired on a hand-written verb list — *zooming, weaving, brewing, grinding, stripping* — and had no
**equipping**, no **switching**, no **applying**. A reply claiming action in one of those words was not treated as a
promise at all, so no repair ran and the claim was delivered as if the work had been queued. That is the silence this
project exists to avoid, arriving through a vocabulary gap.

The rule is now the **gerund**: any "-ing" word in a reply is a claim worth exactly one repair call. A list of verbs
cannot keep up with a model's phrasing, and the cost is bounded — one call, only when no action was queued and
nothing was declared. Three of the harness' own transcripts are now unit tests, so the rule cannot quietly narrow
again.

## And if the repair produces nothing, he says so

Previously a promise that survived its repair was delivered unchanged — a sentence about work that did not happen.
Now he adds one line: *"I could not turn that into a step — ask me again and I will try."* The ask produces an action
or a sentence saying what went wrong; never silence.

## The harness, after

```
"cobbie use my worst pickaxe"  →  #13 equip(my worst pickaxe)  →  completed
"cobbie equip my best pickaxe" →  equip(...)                   →  completed
"cobbie put the sharpness book on my best sword" → anvil(my best sword) → failed on the anvil's own merits
```

Ratio **9 right, 5 wrong of 14** (from 7–8 the two runs before), 4 follow-ups excluded. The attribution still slips
by one ask — the labels above are off by one because a plan created between two asks is claimed by the earlier one —
so the ratio remains a report and the per-ask lines are the evidence.

## Verification

- `cleanTest test`: **222 tests, 0 failed, 6 skipped** — `PromiseTest` covers the three phrasings the harness caught,
  ordinary conversation, and a refusal (which must not trigger a repair).
- `runClientGameTest -PcobbieTests=ClaimProbe` with the gateway up: the isolation result above.
- `runClientGameTest -PcobbieTests=VerbChoice`: 9/5 with the describe asks resolving.
