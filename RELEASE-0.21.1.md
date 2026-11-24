# Cobbie 0.21.1 — bare-handed use, and what a bucket needs

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

## The bucket did not work, and here is why it is a separate job

Filling a bucket in 26.3 is **not** a block interaction any more. `BucketItem` has no `useOn`; it
overrides `use(Level, Player, InteractionHand)` and casts a ray from the player's eye
(`getPlayerPOVHitResult` with a fluid clip), then picks the fluid up through `BucketPickup`. My first
attempt gave the actor the eye ray and it still did not fill, so something in that path is not satisfied
by a `FakePlayer` — reach, clip context, or a guard I have not found yet. That is now a known, isolated
piece of work rather than a claim in a release note.

I took the bucket out of this release rather than leaving a red stage in it. **Everything that moves
an item between the world and the hand through the *block* path works; the items that act on their own
through the *eye* path are unproven.**

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game:** the lever and button stages are new in `CobbieUseTest`, which is green as a whole —
planting, bone meal, lever, button, throwing-use cleanup and no-player-data-in-save.

**Not verified:** buckets, flint and steel, shears, hoes, bows, potions, and use with nothing to aim at.
Fire and water placement are the next two worth having, because they are how a companion helps with a
build rather than just a farm.
