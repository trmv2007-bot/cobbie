# Cobbie 0.56.0 — the harness answered, and its answer was about the harness

Artifact: `cobbie-0.56.0.jar`. **219 unit tests green**; the verb-choice harness runs with a live brain and
reports, by design, rather than reddening.

The open sentence from 0.55.0 was "the harness score is waiting on a brain". The brain was there. This release
is what it said.

## What the harness measured, with the gateway up

Three asks that describe an item rather than naming it, added to the harness in the same round:

```
"cobbie put the sharpness book on my best sword"  wanted anvil  → anvil(iron_sword)   right verb, right item
"cobbie use my worst pickaxe"                     wanted use    → equip(iron_pickaxe) wrong verb
"cobbie equip my best pickaxe"                    wanted equip  → equip(iron_pickaxe) right verb, id not description
```

**The finding is not that the resolver failed — it is that the resolver never saw the words.** In both pickaxe
cases the model translated *"my worst pickaxe"* into `iron_pickaxe` before the plan was built, and the game was
then correct to ask *"more than one of them is named iron_pickaxe — which one?"*: the difference the owner meant
(the worn one, the fresh one) lives in the description and is lost in the id.

So the resolver works, and the route to it is what needs work. That is a prompt change, and **it is not in this
release**: the second run came back 6 right / 8 wrong with transcripts of `follow-up after results` — the
harness' `lastPlan` had captured the *follow-up* plans rather than the asks' own plans, so the run measured
something else. A prompt change whose effect I cannot attribute is exactly the tuning this project does not do.

## The harness' own defect, named

The score is computed from `lastPlan`, which is the last plan **anywhere** — and after a failed plan the brain
asks the model to comment, producing a follow-up plan that replaces it. So a run with failures cannot attribute
plans to asks, which is why 11/14 then 6/14 measured the same code at different moments. The fix is to record
the plan id created for each ask and read *that* plan, and it is the next slice's first job.

## Also in this release

`docs/AUDIT.md`'s pattern paragraph now carries the fourth instance, the one I made myself: the description path
was first built *beside* the id scan, so "diamond sword" spelled an id and the first of two swords was taken
silently; the fix was one resolver for both verbs, not a second path.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- Harness run with `COBBIE_PROVIDER`/`COBBIE_BASE_URL`/`COBBIE_MODEL=pooled-deepseek` from the live config and
  the key-pool gateway on `127.0.0.1:8787`: 14 measurable asks, three of them describing an item, transcripts
  quoted above.
- No product code changed: the diff is the harness' three new asks and one documentation paragraph.
