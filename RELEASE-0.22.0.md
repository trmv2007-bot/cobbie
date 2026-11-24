# Cobbie 0.22.0 — the stonecutter, and what XP would cost

Artifact: `cobbie-0.22.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), `CobbieCraftTest` green in game.

## Stairs from one stone

A player asked for a stair out of stone does not lay six blocks out on a crafting table — they put one
on a stonecutter. He could not, because stonecutting was not part of his recipe table at all. It is now
a third station beside the hand and the furnace:

```
you: cut me a stair
Cobbie: crafted 1 minecraft:stone_stairs (1x minecraft:stone_stairs_from_stone_stonecutting)
```

- **The station has to exist.** A stonecutter recipe requires a `StonecutterBlock` within 12 blocks,
  found the same way a furnace is, and he says so plainly when there is none: *"I need a stonecutter
  for stone_stairs and there is none near me"*. Doing it in mid-air would have been easier and would
  have made the surface a lie.
- **No waiting.** Where a furnace step loads, waits and collects, a stonecutter step happens in one
  tick; the split in the planner is `needsFurnace()` versus `needsStonecutter()`.
- **The test can only pass through the stonecutter.** It gives him *exactly one* stone and asks for a
  stair — the crafting-table recipe wants six — so a green run is proof the stonecutting recipe was
  chosen and executed.

Specials that read a grid (repair, dye, banner) are next and are the harder half; this release is the
half that is honest today.

## What "Cobbie has XP" would mean — the decision, with costs

Enchanting, the anvil and mending are the three capabilities a player values most, and all three read
`Player.experienceLevel` and `Player.getXpNeededForNextLevel()`. A `PathfinderMob` has neither. There
are two shapes, and they are not close in cost or in what they mean:

**A. He earns his own XP** — a level and progress field on the entity, saved beside the bag.

- *Earning* has a faithful route: XP arrives as `ExperienceOrb` entities, and only a `Player`'s tick
  absorbs them. Replicating that is about forty lines — look for orbs within a block of his feet,
  take `getXpValue()`, add it, discard the orb, with the same pickup delay a player has. The numbers
  then come from the game's own orb values, not from a table I invent.
- *Spending* is the enchanting table, anvil and mending, all of which are `Player`-typed menus; the
  actor from 0.21.0 can drive them if his level is copied in before the call and back after.
- **Cost:** roughly one slice for XP, orbs, persistence and the panel line; a second for the
  enchanting table (which needs a lapis slot and a seed) and the anvil. Mending then comes almost free,
  because it hangs off XP pickup.

**B. He banks the owner's XP** — no field, no orbs; enchanting and the anvil spend the player's levels.

- **Cost:** less code, because there is nothing to earn.
- **What it means:** the owner pays invisibly from somewhere else in the world. Enchanting is the one
  interaction in the game where the resource *is* the player's progress, and lending it out silently is
  worse than not having it. Mending would also have nothing to draw on when the owner is elsewhere.

**Recommendation: A.** It is a day of work for XP itself and another for the stations, and it is the
difference between a companion who owns his gear and one who quietly spends yours. B is only worth it
if the answer to "should Cobbie be a separate little player" is no, and this release is not the place
to decide that.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game:** `CobbieCraftTest` — planks from logs, a pickaxe through the chain, and a stair from one
stone at the stonecutter, all in one run:

```
[gametest] craft ok: planks from logs, a pickaxe through the chain, a stair from one stone at the
           stonecutter (logs 5 -> 4)
```

**Not verified:** stonecutter recipes other than stairs — slabs and walls share the code path but no run
has cut one — and the "no stonecutter near me" refusal has not been exercised in game either.
