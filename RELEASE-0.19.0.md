# Cobbie 0.19.0 — doors, steps, danger, and a furnace

Artifact: `cobbie-0.19.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game client classes for everything below are green — `Door`, `Container`,
`Craft`, `Follow`, `Reach`, `Swim`, `Explore`.

This is the first two slices of "everything a player can do". The map is in
`docs/PLAYER-CAPABILITIES.md` (~150 capabilities, each with the game's own API, what he had, and what
it would take). The order is deliberate: the two structural blockers come first, because a companion
who can smelt iron and open a chest behaves like a player far more than one with thirty new verbs.

## Doors are a way through, not a wall

A house used to be solid ground to his pathfinder. Now `setCanOpenDoors(true)`, a closed wooden door
costing 2 to path through, and an `OpenDoorGoal`, so he opens it himself.

The test is the honest version of that claim: a 33-block stone wall with one closed door, and a walk
to the far side. Walking around costs far more than the door, and he goes through it.

## He can take the step a player takes

Two things made him a stranger on stairs and hillsides:

- `Attributes.STEP_HEIGHT` was the mob default of 0.6, while `WalkNodeEvaluator` plans one-block rises
  (`max(1.125, step height)`). The pathfinder was planning steps his body could not take. It is 1.0 now.
- `setCanPathToTargetsBelowSurface(true)`. Without it the navigator silently lifts any target below
  the surface to the top of its column, so "dig down to that ore" walked him over the spot instead of
  into it.

## Danger is priced

Lava and fire 24, damaging blocks 16, cautious blocks 6, trapdoors 8. Only water had a cost before, so
following was happy to route him across fire and open pits.

## Swings are paced, and a fight is a fight

`Attributes.ATTACK_SPEED` is registered — a mob cannot even read it otherwise, which is why the
cooldown was a flat 16 ticks for a fist and a netherite sword alike — and the delay is derived from it,
so a sword is faster than an axe the way it is in a hand. `attackTick` also no longer finalises the
action after the first hit; "kill that zombie" used to end the moment one swing landed.

## Containers, without a menu

Chests (including a double chest through the merged `Container`), barrels, shulker boxes, and the
owner's ender chest. New verbs `take` and `put`, behind `containers_allowed` (default **off**), with
the ender chest additionally behind `ender_chest_allowed` because it is your private storage.

- Written against the `Container` interface, not his nine-slot bag. The 36-slot inventory is a
  separate migration and this code does not have to be rewritten for it.
- Every transfer is verified from both sides: the bag total and the container total are counted
  before and after, and a mismatch is a failure, not a shrug.
- A lit furnace is not emptied: `take` refuses its fuel slot and its un-smelted input, because a
  companion who takes your burning coal because you said "take the iron" is worse than one who waits.
- The model writes item names in every shape — `cobblestone`, `minecraft:cobblestone`, `sticks` — and
  they all resolve.

## Iron is reachable now

Smelting is part of the craft chain, not a separate chore. Ask for an iron pickaxe and the plan is
"smelt three raw iron, then make the pickaxe"; the furnace steps are loaded, waited on and collected
by the same job that does the hand-crafting, and the wait is the game's own furnace ticking rather
than a loop in his tick.

```
you: cobbie, smelt that iron
Cobbie: making an iron ingot
Cobbie: made it
        result — craft iron_ingot: 1/1 steps done — crafted 2 minecraft:iron_ingot
        (2x minecraft:iron_ingot_from_smelting_raw_iron)
```

Two bugs this work found in the recipe selection, both of which would have hit a player:

- A recipe whose grid **consumes what it makes** was accepted as a way to make that item. The
  transmute family reads its input's own components, so assembling it against an empty grid produced
  nonsense and it shadowed the furnace recipe: "making minecraft:iron_ingot would need
  minecraft:iron_ingot". Such a candidate is now rejected outright.
- Furnace recipes were only consulted when the crafting table came up empty, so one unsatisfiable
  hand recipe hid the smelting route entirely. Both are always offered now and the planner backtracks.

Permissions are separate switches, on purpose: `crafting_allowed` for hand crafting, `containers_allowed`
for chests and furnaces. A chain that needs a furnace says so at the furnace step, which is where the
refusal is the true reason:

```
couldn't make that — I need a furnace to make minecraft:iron_ingot and I am not allowed to use one —
/cobbie permit containers on
```

## The container ring, said plainly

**His bag is nine slots.** Emptying a chest into it will overflow to the ground, and the 30-second
blacklist on items he failed to pick up makes that feel like a bug — the pile sits there and he
ignores it. That is not a container bug and this release does not paper over it: the 36-slot
inventory with a hotbar and offhand is the next piece of work, and the container code was written
against `Container` so it does not have to change when the bag does.

`take` does stop when the bag is full rather than dropping things, and `put` stops when the container
is full. It is only a *large* transfer that lands on the floor.

## Verification — what was observed, and what was not

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. New since 0.18.1: an iron pickaxe plans
a furnace step, and the plan executes against a simulated bag.

**In game (`./gradlew runClientGameTest`), flat world, this PC, on this build:**

```
[gametest] door ok: walked through the closed door to 0 -60 16
[gametest] container ok: chest round-trip of 16 cobblestone, then 2 iron ingot(s) smelted from raw
           ore; completed — craft iron_ingot: 1/1 steps done — crafted 2 minecraft:iron_ingot
           (2x minecraft:iron_ingot_from_smelting_raw_iron)
[gametest] craft ok: planks from logs, then pickaxe through the chain (logs 5 -> 4)
[gametest] 7 of 7: Follow, Reach, Swim, Explore, Door, Craft, Container
```

The seven-class run was on the build immediately before the permission split; `Craft` and `Container`
were re-run green after it, and the split touches only the crafting permission check. I would rather
say that than imply one clean sweep.

**Not verified, worth saying plainly:**

- **Only a chest and a furnace were exercised.** Barrels, shulker boxes and the ender chest share the
  same `Container` code path and are untested in game; the ender chest in particular hands back the
  *owner's* inventory and has never been opened in a run.
- **Smelting was tested with raw iron and coal.** Blast furnaces and smokers are the same block entity
  family and should work, but no run has used one, and nothing smelts a stack of anything.
- **A double chest was tested only as two single chests adjacent** — the test places one chest block,
  so the merged-container path has not been observed.
- **Doors were tested standing in the doorway of a wall.** Iron doors (which need a button), gates,
  trapdoors and doors in a real house are not covered.
- **Swimming across an ocean, riding anything, and every item-use capability remain unbuilt** — they
  are phases 2 and 3 in the capability map.
