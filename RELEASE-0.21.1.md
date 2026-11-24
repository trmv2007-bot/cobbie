# Cobbie 0.21.1 — bare-handed use, and items that act on their own

Artifact: `cobbie-0.21.1.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks).

0.21.0 shipped the fake-player actor and proved it with seeds and bone meal, and its release note said
plainly that levers and buttons were untested. They are tested now, and the actor learned one thing a
real client click does that my first version did not.

## A right-click is two calls, not one

A player's single right-click is block-use first, and then — if the block had nothing to say — the
item's own behaviour. `useAt` did only the first half. It now does both, in that order, and the actor
is **aimed** at the block (yaw and pitch computed from its eye) rather than reusing Cobbie's facing,
because a client's click carries the direction the player is looking.

## Bare-handed use, proven

```
[gametest] use ok: sowed wheat on farmland, bone meal took age 0 -> 2; a bare hand toggled a lever and
           pressed a button; a throwing use left his bag and the actor's hand untouched; no phantom
           player data in the save
```

A lever is a latch, so the test waits for it to open. A button is momentary — it un-presses itself after
a second — so the test watches the press while it happens rather than checking afterwards, which is the
difference between a test that can pass and a test that cannot.

## Items that act on their own: a bucket

Filling a bucket in 26.3 is **not** a block interaction. `BucketItem` has no `useOn`; it overrides
`use(Level, Player, InteractionHand)` and casts a ray from the player's eye (`getPlayerPOVHitResult`
with a fluid clip), then picks the fluid up through `BucketPickup`. That is why the actor is aimed from
its own eye rather than reusing Cobbie's facing, and why the two-call ordering above matters.

My first attempt failed, and the failure was mine rather than the actor's: the test placed the water
three blocks from where he had been *before* walking to a lever, so by the time he arrived the water
had been given time to be out of reach and out of shape. A temporary probe in a clean world showed the
mechanism working first time — `useItem result=Success … heldItemTransformedTo=1 minecraft:water_bucket`
— which moved the investigation from "FakePlayer cannot fill buckets" to "the test is placing its water
badly". The stage now places a 3×3 pool beside him at the moment of use:

```
a bucket came back as a water bucket
```

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game:** the lever and button stages are new in `CobbieUseTest`, which is green as a whole —
planting, bone meal, lever, button, throwing-use cleanup and no-player-data-in-save.

**Not verified:** flint and steel, shears, hoes, bows and potions — none of which have been exercised —
and use with nothing to aim at. Fire and water *placement* are the next two worth having, because they
are how a companion helps with a build rather than only with a farm.

The lesson I would keep from this one: when a path "does not work", a five-line probe in a clean world
tells you whether the mechanism or the test is wrong, and it is much faster than a second guess.
