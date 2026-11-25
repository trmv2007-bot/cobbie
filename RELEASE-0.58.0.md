# Cobbie 0.58.0 — one resolver, five verbs, and the hand

Artifact: `cobbie-0.58.0.jar`. **219 unit tests green**; `Anvil`, `Grind`, `Smith`, `Brew`, `Use`, `Interact`
and `Describe` all green in game.

## One resolver, called by every verb

`anvil`, `grind`, `smith` and `brew` now resolve their items through the same `pick(...)` that `equip` and `use`
use — one place that turns words into a stack, with a per-verb predicate for what that verb would accept:

- **anvil**: the work item (enchantable, not a book) and the book (enchanted, with stored enchantments);
- **grind**: the item with enchantments to strip;
- **smith**: the base and the addition, as the table defines them;
- **brew**: the reagent;
- **equip**, **use**: as before.

Ambiguity is a question on every one of them, because there is no second path to behave differently. That was
the point of the wiring: the audit's shape — a condition written for one caller, applied to all of them — is what
made "diamond sword" mean the first sword in one verb and *which one?* in another.

**`trade` is deliberately not wired**, and the reason is not convenience: its costs come from the villager's
offer, not from the owner's words. *"Buy pumpkin pie"* names the **offer**; there is nothing for a description
to resolve against.

## Each verb kept its own sentence

The first version of the shared resolver answered every empty-handed refusal with one generic line, and two
suites went red on it: an unenchanted pickaxe at the grindstone must say *"I have no enchanted …"*, and
cobblestone at a smithing table must say *"nothing the table would upgrade"*. The filter decides *which* stacks
count; the verb still owns *what to say* when none do. A shared code path is not a shared vocabulary.

## The red on master was mine, and it was the hand

`CobbieInteractTest` failed with *"A fishing cast was not refused with the reason … I have no fishing_rod with
me"*. Cause: 0.55.0 made `use` resolve through the bag, and a stack that a previous step **raised into his hand**
is not in the bag — so a rod he was holding read as not carried. What he holds is something he carries: `use`
now checks the hand first, and the resolver sees the hand as a candidate too.

The test asserting an exact sentence is not fragile here — it is the only reason anyone noticed, and the
behaviour it checks (refuse the cast, leave no bobber) is right.

## The harness, measured and still not trustworthy

Three describe asks with the wiring in place:

```
"cobbie put the sharpness book on my best sword"  → anvil(best sword)   ← the description reached the verb
"cobbie use my worst pickaxe"                     → —                   ← the line scored was the previous ask's follow-up
"cobbie equip my best pickaxe"                    → —                   ← same
```

**The anvil line is the evidence this release has**: the description survives into the tool call *and* now
resolves. The other two could not be attributed: the transcripts show ask N reading the plan id created by ask
N−1's follow-up, which means a plan created between two asks can be claimed by the wrong one. The self-check
reported it (3 of 14 unattributed) rather than hiding it, which is what it is for — but the ratio it still
printed is not a number I would defend.

So: the resolver's route is proven by seven in-game classes that call it directly with descriptions, and the
harness needs its per-ask window tightened before its score means anything again. That is the next measuring
job, not another feature.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Anvil,Grind,Smith,Brew,Describe,Interact,Use`: 7 passed of 7,
  `--no-build-cache --rerun-tasks`.
- `tools/audit-bug-families.py` run before the push: 40 hits, all previously judged shapes — no new family.
