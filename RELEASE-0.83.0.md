# Cobbie 0.83.0 — the blockstate, not just a block

Artifact: `cobbie-0.83.0.jar`.

Placement worked from the angle it was tested at, and it worked by *looking* like it worked. Every one of
these eight blocks was "placed": the block appeared in the square, the stack shrank by one, the plan
reported success. What the world held was a block written into the square by hand, which is not a
placement — it is a block that happens to be in the right coordinate.

| he was asked for | a player standing where he stood gets | he built |
|---|---|---|
| oak stairs | `facing=south, half=bottom, shape=straight` | `facing=north` — the mirror |
| a slab | `type=bottom` | `type=bottom`, by luck: the default is the same |
| a torch on a wall | `wall_torch[facing=south]` | a floor torch, `minecraft:torch` |
| a ladder on a wall | `ladder[facing=south]` | `facing=north` — the mirror |
| a door | both halves, `half=lower` and `half=upper`, one hinge | one block, `half=lower` alone |
| a bed | `part=foot` and `part=head`, facing the placer | one block, no head |
| a sign | `rotation=8`, pointed at the placer | `rotation=0`, the default, whatever he did |
| a chest | `facing=north`, facing the placer | `facing=south`, facing away from him |

## The defect: the state was written, not placed

`CobbieEntity.placeBlock` built the state itself — `block.defaultBlockState()` with one property set, the
horizontal facing, guessed from the companion's position — and wrote it with `serverLevel.setBlock`. The
game's placement rules were never asked. The guess was also the **opposite** of the game's answer for most
blocks, because the game's question is "which way is the player looking" and the guess was "which way is
the player", and those differ by 180°.

The first run of the new test, against the old code, said exactly that:

```
oak stairs: a player standing where he stood gets
minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false],
and he built minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false].
COMPLETED :: completed — place a block of minecraft:oak_stairs here: 1/1 steps done
```

A success, a block in the right square, and a stair pointing the wrong way. That is the same shape as the
farmland side-face bug — the clicked face differed from the face the probe used, the report said fine,
nothing grew — one layer down: here the *block* differs from what the world would have produced.

## The fix: one right-click, and the game answers

`FakeActor.placeAt` offers the square to the game as the clicks a player standing there would make:

1. the block itself, when it is something the game would replace (a pool of water, tall grass);
2. the ground under it, clicked on its top face — how nearly every block is placed;
3. each of the four sides, the one he is facing **first**, which is how a wall torch or a ladder gets
   attached;
4. the ceiling above, clicked downward.

The first click the game accepts is the placement, and the state it leaves behind is the claim. Nothing in
the mod writes a blockstate any more: `resolveState` survives only to answer "which block is this", and the
facing guess is deleted.

The evidence line changed with it. `place` used to rest on *"minecraft:oak_stairs is at 0 -60 6"* — true of
any state. It now rests on the state, properties and all: *"the world reads
minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false] at …"*.

## What the game decides, per block

| block | what the state carries, and where it comes from |
|---|---|
| stairs | `facing` from the placer's yaw, `half` from where the click landed in the face, `shape` from stair neighbours |
| slab | `type` from the click's height in the face — always `bottom` for a click at the middle of a face |
| torch | in 26.3 the floor/wall difference is two **blocks**, not a property: a floor torch carries no state, a wall torch carries only `facing`, aimed away from its wall |
| ladder | `facing`, aimed away from the wall it hangs on |
| door | `facing` and `hinge` from the placer, `half` on both blocks — the upper one is placed by the game, not by us |
| bed | `facing` from the placer, `part=foot` where he was asked to build and `part=head` one block further along that facing |
| sign | `rotation` from the placer's yaw; clicked on a wall it would be `oak_wall_sign` with a `facing` instead |
| chest | `facing` **toward** the placer and `type=single`, which is the opposite direction from the stair's |

## Every approach angle, and the answer that is not a rule

Four horizontal sides and from above, for stairs and for torches, each compared with what the owner — a real
player standing in the identical spot and clicking the identical face — gets:

- **the four side stances give the four different facings.** That is the evidence that the state is read off
  the world rather than defaulted, and it is asserted as such: four distinct values or the test fails.
- **eye height changes nothing.** Standing one block up on a step and looking down at the same square gives
  the same state as standing beside it, because a stair's facing comes from the yaw and its half from the
  click's height within the face — not from where the eye is. That is the honest answer to "does the approach
  angle matter", and it is asserted rather than described.
- **a torch on the floor is one answer for everyone**, because the face that gets clicked is the same face
  for everyone; **a torch on a wall is four answers**, one per side, because the attachment comes from the
  face.

## Stations and containers from any side

A chest, a furnace, an anvil, a stonecutter and a crafting table, each used while he stands on a different
side of it, and after walking to a second position. What is asserted is the **contents of the container** and
**what came out of the station**, read off the world.

The chest and furnace are the real thing: a round trip of twenty cobblestone from four sides and then put
back from seven blocks away, two ingots smelted from two sides. The anvil takes one book per side and the
sword's Sharpness climbs 1 → 2 — and getting that far found a second defect, below.

### A used anvil is not an anvil he could find

An anvil chips when it is used, and `findAnvil()` looked for `minecraft:anvil` by name. So the second job —
from the east, at an anvil ten blocks away that he had just worked at — answered **"I need an anvil and there
is none near me"** about the chipped anvil standing two blocks away. The three anvils (`anvil`,
`chipped_anvil`, `damaged_anvil`) are one class in the game, which is the question worth asking:
`instanceof AnvilBlock`. Found by the second side of the anvil case, which is what the second side is for.

And the claim a menu block must not make: a right-click's answer from a chest, a furnace, a table or an anvil
is a **menu**, and a menu is a screen for a player. The actor opens it into nothing, so `use` used to report
*"used my hand at …"* with an unchanged world behind it. It now refuses with the reason — *"a
minecraft:crafting_table only opens a menu, which I cannot read — tell me to take from it, put something in
it, or use the verb that does the job"*.

## Evidence

| class | what it reads off the world |
|---|---|
| `CobbieBlockStateTest` (registry 73) | 10 placements — stairs, slab, torch on the floor and on a wall, ladder, both halves of a door, both halves of a bed, a sign, a chest, and a dirt control — each compared property-by-property with the owner's own right-click, and each property asserted by name |
| `CobbieApproachTest` (registry 74) | 14 placements: stairs from four sides and from above, a torch on the floor from all five, and a torch hung on a wall from each of the four sides |
| `CobbieStationTest` (registry 75) | chest round trip from four sides then from a second position after walking; two smelts from two sides; an anvil from two sides with the Sharpness level climbing; stairs cut from two sides; pickaxes made at a table from two sides; `use` on a menu refused with its reason |

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | 301 tests, 0 failed |
| `runClientGameTest -PcobbieTests=BlockState,Approach,Station --no-build-cache --rerun-tasks` | 3 passed of 3; the blockstate class run against the 0.82.0 placement path fails on its first case, which is how the defect was proved |
| `runClientGameTest --no-build-cache --rerun-tasks` (whole suite, exact pushed tree) | **73 passed, 2 failed of 75** — `CobbieGameTests` registers 75 classes, was 72 |
| the two reds | `CobbieSmokeTest` and `CobbieChatTest`, both "never reached a connected brain"; re-run on pristine `master` at `afff0a4` they fail identically (0 passed, 2 failed of 2), because the run's `config/cobbie.json` points at a provider with no key |
| `CobbieFollowTest` | failed once inside the first whole-suite run ("did not close distance", 14.14 → 14.14) and passed alone (14.34 → 12.18) and in the second whole-suite run — a terrain-timing flake, not this slice |

## What could not be built honestly, and what stands in for it

- **A slab on the top half.** `type=top` needs a click in the *upper part* of a side face, and his aim is the
  middle of a face — as is a player's, when the click is what the verb carries. So every slab he places is a
  bottom slab, and the test asserts that rather than pretending the top half is reachable.
- **The stonecutter and the crafting table are gates and scenery, not menus.** A stonecutter step requires
  one within reach and then produces the recipe's output; ordinary crafting is done by hand from the recipe
  table. So "using the stonecutter from the north side" has no face that could be got wrong, and the
  assertion is the output in his bag from every side. This is a design choice of the mod, not a defect of
  this slice, and it is written down rather than dressed up as interaction testing.
- **A bed's sleeping.** `use` on a bed answers a real player by starting to sleep; the actor has nowhere to
  sleep and nothing happens. It is not a menu (so the new refusal does not catch it) and it is left named
  here rather than special-cased: a companion that sleeps is a new verb, and this slice adds none.
