# Cobbie 0.87.0 — the map matches the world

Artifact: `cobbie-0.87.0.jar`.

`docs/PLAYER-CAPABILITIES.md` is the inventory of what a player can do and what Cobbie can do. Its third
column was written at 0.18.1 against a survey of the jar and then never updated, so at 0.86 it still said
`no` to stairs, slabs, doors, signs, wall torches, containers, furnaces, tilling, planting, bone meal,
buckets, milking, shearing, leads, name tags, breeding, taming, item frames, levers, buttons, note blocks,
TNT, villager trading, enchanting, anvil, brewing, smithing, loom, cartography, grindstone, XP and hunger.

All of that shipped and was proved in game between 0.20 and 0.86. A map that is wrong in that direction is
worse than no map: it hides the real gaps behind work that is already done, and it sends the next slice at
a finished capability.

## What the audit was

All **150 rows** read against `master`, not against the release notes. For every row, either a `file:line`
for the code path that does the thing *and* the in-game class that proves it, or `no` with what is
missing. Where the code does it but nothing proves it, the row says `untested` rather than done.

**108 of the 150 rows changed.** Thirty that said `no` now read `yes`, seven that said `partial` do too,
five more are `yes, by code only`, and ten that claimed nothing are now `untested` because the code
half-does them.

| | 0.86.0 | 0.87.0 |
|---|---|---|
| `yes`, proved by a named in-game class | 11 | **84** |
| `yes, by code only` | — | **9** |
| `partial` | 14 | **13** |
| `untested` | — | **10** |
| `no` | 71 | **31** |
| `n/a`, incidental, answered from evidence | — | 3 |

The old column also had no vocabulary. It mixed `yes`, `no`, `partial`, `partial, for free`,
`incidental`, `structurally impossible` and a bare `\`, which is how a stale document defends itself: a
row that cannot be classified cannot be wrong. The new one has four words and each is defined at the top
of the file.

## The rows that were lying loudest

Every status below names the code and the class that proves it — this table is a sample of thirty.

| capability | 0.86.0 said | what the code does | proved by |
|---|---|---|---|
| Stairs, slabs, doors, beds, signs, wall torches | `no` | `placeBlock` hands the square to `FakeActor.placeAt`, which offers the game the clicks a player standing there would make and keeps the first it accepts — two halves of a door, a bed's head, a wall torch, a stair's half, a sign's rotation | `CobbieBlockStateTest`: each block placed twice, by Cobbie and by the owner, and the two blockstates must agree property by property |
| Container read and write | `no` | `Containers.resolve` returns the real `Container` behind a chest, barrel, shulker or furnace; `takeFrom`/`putInto` move named things and report the count that moved | `CobbieContainerTest`, `CobbieStationTest` (four sides, and after walking) |
| Furnace, smelting, iron | `no` | `startSmelt` fills input and fuel and waits on the block entity's own cook time, and `craft` plans the smelt itself | `CobbieContainerTest`: raw iron and coal in, an ingot out, then an iron pickaxe without being told the steps |
| Enchanting | `no` | `enchantFromBag` drives a real `EnchantmentMenu` through `FakeActor.enchant` and pays his own levels and lapis | `CobbieEnchantTest`: the charge, the lapis, and a refusal with the numbers when short |
| Anvil, brewing, smithing, loom, cartography, grindstone, stonecutter | `no` | one verb each, all reading their success off the station or off what he carries | `CobbieAnvilTest`, `CobbieAnvilEndTest`, `CobbieBrewTest`, `CobbieSmithTest`, `CobbieLoomTest`, `CobbieCartographyTest`, `CobbieGrindTest`, `CobbieStationTest` |
| Hunger, eating, milk, potions | `no — Cobbie has no hunger by design` | his own `FoodData`, ticked by `FoodData.tick` on an actor whose bar, health and effects are his | `CobbieHungerTest`: nutrition, saturation, the sprint gate, starvation, both regen rates, milk clearing poison, a potion healing |
| XP and levels | `no` | orbs absorbed the way a player absorbs them, the player level curve, saved with the body | `CobbieExperienceTest` |
| Villager trading | `no` | `tradeWithVillager` prices the offer, moves the costs through the merchant's own container and reads the result out | `CobbieTradeTest`: goods arrived, emeralds spent, the villager counted the trade, an unaffordable trade quotes its price |
| Lever, button, repeater, note block | `no` | `use` on the block, through `ItemStack.useOn` | `CobbieRedstoneTest` |
| Sprinting, jumping, stepping up, doors | `no` | `setSprinting` gated on the game's own food rule, `STEP_HEIGHT 1.0`, `OpenDoorGoal`/`OpenFenceGateGoal`, lava and fire priced as walls | `CobbieHungerTest`, `CobbieDoorTest`, `CobbieTerrainTest`, `CobbieHazardEscapeTest` |
| Boats and minecarts, portals | `no` | `boardCraft` rides as a passenger and verifies the craft carries him; `followThroughPortal` walks him in and `CrossDimensionFollow` brings him through with his bag | `CobbieRideTest`, `CobbiePortalTest` |

## Nine abilities that had code and no proof

The honesty rule this document now follows is that a capability with no test is not done — it is
`untested`. Nine rows were exactly that state and were cheap to settle, so `CobbieTailTest` settles them
against the game: **tilling with a hoe, milking a cow, naming a pig, taming a wolf, a music disc in a
jukebox, shearing a full beehive, a water bucket, a cow spawn egg, and priming TNT with flint and steel**.
All nine ride the two paths the rest of the suite already trusts — `ItemStack.useOn` on a block,
`Player.interactOn` on a mob — and all nine now have a case.

```
[gametest] tail ok: tilled dirt, milked a cow, named a pig, tamed a wolf,
played a disc, sheared a full hive, placed water, hatched a spawn egg, primed TNT
```

Two of them taught something worth recording, because both were the test being wrong first:

- **The beehive's comb drops at the hive, and his pickup reaches 1.6 blocks.** The first version asserted
  the comb was in his bag, which he had never had to do — a player walks into the drop. The case now
  asserts the hive gave it up: in his bag, or on the ground beside the hive.
- **A wolf needs several bones.** The tame roll is the game's own chance per bone, so the case hands him
  eight and stops when the wolf stops being wild, rather than expecting one to be enough.

## The three things that block parity now

The original survey's three — everything takes a `Player`, he never touches a `BlockEntity`, nine slots is
not an inventory — are all closed: `actor/FakeActor.java` is a cached never-spawned `FakePlayer`,
`station/Containers.java` reads and writes block entities, and the bag defaults to 45 slots.

The three that replace them are narrower and one of them is a person's taste rather than an API:

1. **A right-click is one call; a bow is two.** Every remaining item gap is a hold and a release:
   `ItemStack.releaseUsing`, the bow's charge, the crossbow's loaded state, the trident's throw. Everything
   that is a single click already works, which is why the list of `no`s is now short.
2. **His body is a mob's body.** No sneaking, no crawling, no air supply, no elytra, no sleeping, no
   steering a saddled animal. `Player` state and `EntityDimensions`, not verbs.
3. **He does not choose.** He mines with the cheapest tool that harvests the block and refuses to attack a
   cow because `isLegalTarget` is hostiles-only. Both are policy, and both read as capability gaps from
   outside.

## The remainder, ordered

The document now ends with the gaps that are genuinely open, ordered by what a player would notice first.
The first one is the only one that is a real hole rather than a nicety: **he will not attack an animal**,
so a companion cannot feed himself without the owner killing for him. Then block-breaking still pops the
block in a single call, bows and crossbows are missing their release, and he cannot steer what he rides.

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | **325 tests, 0 failed, 6 skipped** — unchanged from 0.86.0, as it should be: no production code was touched |
| `./gradlew runClientGameTest -PcobbieTests=Tail --no-build-cache --rerun-tasks` | **1 passed, 0 failed of 1** — nine abilities, no assertion skipped |
| `CobbieGameTests` registered classes | **82**, one more than `master`'s 81 |
| the whole 82-class suite | **not re-run.** This slice changes a document and adds one test class; there is no production change for a full run to catch. The preflight reported no brain at 11434, so `CobbieSmokeTest` and `CobbieChatTest` would have failed for that reason alone, exactly as in the 0.86.0 record |

## What this slice deliberately did not do

- **No capability was started from zero.** Every row that reads `no` is either structural or named in the
  remainder; none of it is begun.
- **The `untested` ten are named, not quietly promoted.** Splash and lingering potions, snowballs and eggs,
  ender pearls (two rows), saplings, a picked-up water bucket, lava, sheared leaves and taming a cat. Each
  is a cheap case in `CobbieTailTest` or `CobbieBlockStateTest`, and until one exists the row says so.
- **TNT is still unguarded.** `CobbieTailTest` proves he will prime it, and he will do it beside the
  owner's house. The guard is in the remainder, not in this slice.
