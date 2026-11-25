# Cobbie 0.38.0 — following you through a portal

Artifact: `cobbie-0.38.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game classes green except the ones that need a live model.

The worst "he is not here" failure in the mod, and the same complaint as the water one with a wall in
it: you go through a portal and he stands at the frame.

```
[gametest] portal ok: walked into the portal with the owner in the nether, crossed, arrived carrying
           5 diamonds and level 7
```

## He walks; nothing teleports him

`ownerAnywhere()` finds you across dimensions, and when you are in another world he paths to the nearest
nether portal, steps in, and lets the game carry him — the game's timing, the game's arrival, and the
nether's hazards applying to him because he is really there. A portal block is not a node the
pathfinder will plan to, so he aims at the floor in front of the frame and then steps in.

If he cannot reach a frame, he says so **once**, plainly: *"I cannot find a portal to follow you
through — come back, or leave one open where I can reach it."*

## The real obstacle: an inert companion

A player leaving a level releases its entity-ticking chunks. A companion standing in the overworld is
then present, saved, and **not ticking** — and no walking design can fix that from inside a tick that
never runs. The probe proved it rather than assuming it:

```
[probe] xdim tick watches=0 t=800
```

The fix is a bounded chunk ticket in `CrossDimensionFollow`: while you are in another dimension and he
is not, his chunk is held open so he ticks and can walk. The bounds are the design:

- **Only while the dimensions differ.** Same world means no ticket, ever.
- **It expires** after thirty seconds — several times a walk to a visible frame — and then releases,
  tells him he could not get through, and stops.
- **Every exit releases it**: crossing, the owner coming back, the owner going offline, no longer
  following, and server stop.

And the first version of it could not work, which is worth recording: the registry was written from
*his* tick, so an inert companion could never be registered by the very mechanism meant to rescue him.
It is now scanned from the server thread once a second, so a companion ticking *before* you cross is
known afterwards.

## Two things the test asserts beyond the crossing

- **His bag and his level survive:** the test carries five diamonds and seven levels through and checks
  both on the other side. They live on the entity, so this should be free — it is asserted anyway.
- **No ticket leaks:** `CrossDimensionFollow.pending() == 0` after the crossing, so a held chunk fails
  the run rather than being discovered weeks later as a chunk that never unloads.

Memory needed no change: `CobbieMemory` is one file, not keyed per world, so it follows him across
dimensions for free.

## A build lesson that cost this slice two false readings

`gradle` reported `BUILD SUCCESSFUL` while the client ran **older classes** — twice, including once
after deleting the class output. Every probe run of this slice that printed nothing was the build
cache, not the mod. Every verification here was run with `--no-build-cache --rerun-tasks`, and that
should be the default for gametest verification in this repository: a false green is worse than a red.

## Verification

**Unit:** 178 green, 6 skipped. **In game:** full suite reported in the pull request; `CobbiePortalTest`
is new and green.

**Not verified:** the end-portal route (only the nether portal is searched or tested), the return trip
(the same code runs on the way back but no run has done it), and the "no portal in reach" sentence —
it is reached only when there is genuinely no frame within 24 blocks, which the test never sets up.
