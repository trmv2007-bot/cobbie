# Cobbie 0.48.0 — he decides what to build

Artifact: `cobbie-0.48.0.jar`. **179 unit tests green**, `CobbieInitiativeTest` green in game across
four phases in one world.

Second half of milestone 8. The first half let him choose *which block*; this one lets him choose
**what** — and the answer is deliberately small enough to be honest about.

## A cairn, from what he carries

The third project kind is a cairn: three blocks stacked on flat ground out of material in his bag,
cobblestone first, then stone, oak planks, dirt. It is the first project whose shape is a decision rather
than a block he noticed:

```
[gametest] initiative: built on his own "building a cairn of cobblestone here" —
           3 cobblestone standing, 5 left in the bag
```

Eight carried, three standing, five left: he spends what he places. A marker, not a structure — anything
larger would need a site survey to be honest about what counts as finished, which is the same trap the
dropped repair kind sits in.

Choosing stays deterministic and free: no model call to pick the material, the shape or the site. The
site search takes flat ground within two blocks, with something underneath and nobody standing in the
space he would fill.

## The support question, which was the real bug

The first four runs produced no project at all, and the probe — rather than a fifth theory — showed why:

```
cobblestone=8 permitsPlace=true creative=true allowed=true choose=null |
dy0 canPlace=true | dy1 canPlace=false | dy2 canPlace=false
```

The second and third blocks failed the placement gate on **support**, because at choosing time the block
underneath is air: it is the block *his own plan* places first. A plan that checks its future steps
against the world as it stands can never propose anything taller than one block.

So the gate now distinguishes two questions:

- `canPlace` — the world must hold this up (the base of the cairn, every ordinary placement);
- `canPlaceOnOwn` — **my plan** holds this up (each block above the base), where occupancy and bodies
  still count and only support is taken as promised.

Both run through one `placementRefusal`, so a promise and an attempt are still a single rule.

Also fixed: the chooser gave up on a material when the *first* flat site was blocked — his own feet, or
the owner standing there — instead of trying the next site over.

## The negatives had to be re-ordered, not just re-timed

`2400` ticks of cooldown follow every finished project. The new phases were packed inside it, so the
stone negative and the ASSISTIVE negative were not merely slow — they were **vacuous**, passing on a
gate that could not fire. The same flaw the elapsed-ticks fix addressed one layer down.

The order is now the two negatives first, while no cooldown is live, then the two project phases with
the cooldown waited out in measured ticks between them, and the logs placed only after the stone
negative — with them in the world earlier, that phase had a project to start and proved nothing.

## Evidence

```
[gametest] initiative: announced nothing with only un-mineable stone in sight over 900 ticks,
           though the gate was running
[gametest] initiative ok: started no project in ASSISTIVE over 400 ticks, then started
           "gathering the oak log nearby" unprompted in CREATIVE and it reached COMPLETED —
           completed — gathering the oak log nearby: 3/3 steps done
[gametest] initiative: built on his own "building a cairn of cobblestone here" —
           3 cobblestone standing, 5 left in the bag
```

The gather phase also asserts the logs are **gone** afterwards, not that a status string says completed.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped** (the opt-in network tier).
- `CobbieInitiativeTest`: 1 passed, 0 failed of 1, 210 s, `--no-build-cache --rerun-tasks`.
- Four failed runs preceded it; three were the fixture or the test's ordering and are described above,
  and the fourth was the support bug the probe found.

## Still open

Repairing a damaged structure waits on an owner-marked target, as the owner ruled. Milestone 9, learned
skills, is next.
