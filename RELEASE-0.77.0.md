# Cobbie 0.77.0 — the minecart half of riding, run instead of assumed

Artifact: `cobbie-0.77.0.jar`.

0.47.0's note says he rides a boat **or a minecart**, and that claim rested on a shared code path —
`boardCraft` accepts `AbstractBoat` and `AbstractMinecart` alike — with no run behind the cart. This
converts it into evidence, or would have named the gap if it failed. It passes.

## What the cart stage asserts

Three claims, each one a way the stage could pass while broken, and the same three the boat already
makes:

1. **He is a passenger of the cart**, asked of the cart's own `getPassengers()` containing him — never
   of the return value of `startRiding`, which can return true for a ride that does not survive the tick.
   The check also requires his `getVehicle()` to be *that* cart, so being in some other craft would not do.
2. **The cart carries him.** His own `getX()`/`getZ()` is sampled before and after; the cart has to move
   him at least one block, the same two-block-scale test the boat uses. A boolean cannot catch a
   passenger who falls out on the first tick of real movement, and a cart that never rolls cannot pass a
   position copy.
3. **He gets out** when asked for anything else — `follow` while riding, then his `getVehicle()` must be
   null. The same dismount path the boat proves.

## How the cart is moved, and the two fixtures that did not work first

The rail line is 17 `minecraft:rail[shape=east_west]` blocks in the flat world, with two
`powered_rail[shape=east_west]` blocks where the cart waits, unpowered until he is aboard. The cart is
`summon`ed onto the rail the way a player places one, and after boarding the powered rails are given
redstone blocks and the cart a single 0.4/tick push along the line.

Both of the earlier attempts failed on the fixture, not on him, and the assertion is what said so:

- The cart `setPos`'d a fraction above the track rolled **0.40 blocks** and stopped — one tick of motion,
  because it was never seated on the rail.
- With the cart seated (`y=-60.9375`, on the track) but the rails left at their default `north_south`
  shape, it moved **0.00**: a cart cannot follow track that is not laid under it.
- With east-west rail and power, it still moved **0.00** — a flat powered rail does not launch a cart
  from rest, which is the one piece of vanilla behaviour worth writing down.

The push plus the power is what works: the cart rolled **1.8 blocks** carrying him. The failure message
now names which failure it is — the cart's own position and passenger count beside his — because "the
cart moved but he stayed" was ambiguous when the truth was "the cart did not move at all".

"The cart moved but he stayed" and "the cart never moved" are the same lesson as the dead body at the
goal, one object down — an assertion that asks whether the call succeeded cannot tell them apart, which
is why the failure message prints the cart's own position and passenger count.

## What a green does not mean

**It says nothing about driving.** A fake player cannot board a craft, so steering is impossible by
construction and this is riding only: he is carried, he does not choose the route. The boat stage has
always had the same limit.

**1.8 blocks is motion, not a journey.** The assertion is that he moves with the cart at the boat's
scale, not that he rides a long line, survives a curve, or stays aboard through a collision.

**Nothing here covers other carts.** Chest, hopper, furnace and command-block minecarts are all
`AbstractMinecart` and would take the same path, but only the plain cart was run.

## Verification

- `compileJava compileGametestJava`, `--no-build-cache --rerun-tasks`: green.
- `cleanTest test`: **240 tests, 0 failed, 6 skipped** (27 classes), from `build/test-results/test/*.xml`.
- `runClientGameTest -PcobbieTests=Ride`, `--no-build-cache --rerun-tasks`: **1 passed, 0 failed of 1** —
  `ride ok: boarded the boat, was a passenger and was carried 2.0 blocks, then got out when asked to
  follow` and `ride ok: boarded the minecart, was a passenger and was carried 1.8 blocks along the rails,
  then got out when asked to follow`.

No production code changed: the cart half worked, so the release note's claim stands on a run now.
