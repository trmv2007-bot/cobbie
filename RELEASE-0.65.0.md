# Cobbie 0.65.0 — a plan does not survive a world change

Artifact: `cobbie-0.65.0.jar`.

A plan is a list of coordinates in one world. The moment the world changes under it, the plan is
stale, and the failure is a bad one to leave in: step after step fails with *"that area is not
loaded"*, which reads as him being thick rather than as the plan being out of date.

Now a world change ends the plan, says so plainly, and leaves him idle:

```
Cobbie: we changed worlds, so I stopped what I was doing
```

## What the engine already does, and what the guard is for

The audit named the gap by reading the code: no dimension-change hook, so a running plan keeps
coordinates from the old dimension. Two measurements say the picture is better and worse than that.

**A real crossing replaces the companion.** `TeleportTransition` — the mechanism a portal uses —
returns a *new entity instance* and removes the old one:

```
[probe] teleport returned same=false removed=true dim=the_nether busy=false job=none
```

The task queue does not come with the new instance, so on the portal path there is no stale plan left
to cancel. The audit's visible symptom cannot come from a stale plan inside the same entity; it comes
from plans that are carried across, or from paths where the instance survives.

**So the guard is a guard, not the mechanism.** `CobbieEntity.onWorldChanged()` ends a live plan and
says the line, and it is public for exactly that reason: a per-entity "what world was I in" field
cannot see a crossing that replaces the entity, so anything holding state that outlives the instance
can call it. His own tick calls it too, for every path where the instance does survive a change.

## Verification

`CobbieWorldChangeTest` asserts both facts in order, so a failure says which one broke:

```
[gametest] world change ok: the guard ended a plan with "we changed worlds, so I stopped what I was
           doing", and a real crossing left him at the_nether 8, 62, 8 busy=false with no plan to cancel
```

Asserting both facts is better than either alone. The guard half alone would pass on a build where
crossings destroy plans and the guard is dead code; the crossing half alone would pass on a build with
no guard at all. Together they pin the present behaviour — and **the second half fails the day a plan
does survive a crossing**, which is exactly the day the guard stops being the whole story. When that
test goes red it is not a flake and not something to loosen: it is telling you the mechanism changed.

The guard half is driven through `onWorldChanged()` deliberately: on this build a crossing destroys the
plan before any hook could see it, so driving the cancellation directly is the only honest way to test
it.

The first version of this test failed twice on its own setup, both times for a reason worth keeping:

- A 60-block walk is refused outright — *"is further than i will walk (24 blocks)"* — so the setup had
  no live plan to interrupt. It walks 18 blocks now.
- A mob teleported into an unloaded level is added to *nothing at all* ("not found in any level"), so
  the destination has to be loaded first, exactly as it is in play when the owner goes through ahead
  of him.

## Not verified

**The portal path end-to-end with a plan in flight.** The test crosses with a plan running and asserts
the plan is gone, but the crossing itself is driven directly rather than by standing in a lit portal;
`CobbiePortalTest` covers a real portal carrying him and is unchanged.
