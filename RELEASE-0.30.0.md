# Cobbie 0.30.0 — items that act on a mob, and the one that cannot

Artifact: `cobbie-0.30.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and every in-game class green except the ones that need a live model.

Shears, a lead, and flint and steel. Plus an honest no for the fishing rod, because the probe said
its line cannot belong to him.

```
you: use the shears on that sheep
Cobbie: used minecraft:shears on the sheep
```

## How it works

A player's right-click on a mob is the same gesture as a right-click on a block, so it is the same
verb: `use` with the three coordinates zero and `data` naming the mob — *"use my shears, data=sheep"*.
That reaches `Player.interactOn` through the actor from 0.21.0, so shearing, milking and name tags are
the game's own behaviour rather than a reimplementation of each.

Flint and steel needed no code at all. It already worked through `use`, because a lit fire is the
item's own use-on-block; what was missing was any proof of it, and the test now reads the block above
the one he clicked.

## Three answers from the probe, all of which changed the design

Every one of these was an open question, so the first run of this slice was a probe that printed what
the actor did instead of asserting anything. All three findings are in the test's own header.

**`Player.interactOn` from a never-spawned player shears a sheep properly.** `result=Success`, the
sheep is woolly no longer, two wool items on the ground. That is why shearing went in at all.

**A lead handed to that player leashes the animal to a ghost.** The probe found the cow's holder to be
`FakePlayer [Cobbie]` with `holderIsInWorld=false` — a player standing perfectly still in a world he
is not in, which drags the animal to a fixed point forever. So a lead is tied to **Cobbie himself**
with vanilla leash physics: the animal walks with him because he walks. That is a deliberate
deviation from what vanilla does with a lead, it is the behaviour a companion actually wants, and the
test asserts the holder is him so it cannot drift back.

**A fishing bobber belonging to him dies on its first tick.** One bobber on the cast, none forty
ticks later. `FishingHook.tick` discards itself when `getPlayerOwner()` resolves to nobody, and the
actor is not in the server's player list. The first version of this test caught the consequence:
the rod was *not* refused, and `use` reported `used minecraft:fishing_rod` on a cast that evaporated.
The refusal is now on every path, and it reads:

> I cannot fish — a fishing line belongs to a player and I am not one

Fishing is the one capability in this slice that is **not** delivered. Reimplementing the bite timer,
the reel and the loot roll by hand would be a companion pretending to fish; the doc rates it low
anyway, and it stays unimplemented rather than faked.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.30.0.jar`.

**In game:** `CobbieInteractTest` is green, and each claim is read back off the world:

```
[gametest] interact ok: sheared a sheep and kept the wool; leashed a cow to himself rather than to a
           player who is not in the world; refused a fishing cast with the reason and left no bobber
           behind; lit a fire with flint and steel
```

The sheep is not merely "the call returned success": `readyForShearing()` is false afterwards and the
wool is in his bag or within reach of it. The cow's leash holder is the companion entity itself and
the lead left his bag. No `FishingHook` exists anywhere near him after the refusal. The fire is the
block above the one he used, read from the world.

**Not verified:** milking a cow, name tags, brushing, feeding and shearing a mooshroom all go through
the same `interactOn` path and none of them has been run. A lead on a fence post is untouched: vanilla
ties the animals to the post through *the player's* leashes, and the actor holds none. Flint and steel
on a TNT block primes it through the same call and no run has done it — deliberately, because an
explosion in the flat test world costs more than the claim is worth.
