# Cobbie 0.73.0 — terrain he has to actually walk

Artifact: `cobbie-0.73.0.jar`.

Every other in-game class proves a verb on ground built to be easy. What a player hits in their first
hour is the compound case — a wall with a door in it, a fence with a gate, a ledge, a river — where the
pathfinder finds a route and the body still has to walk it. **A* succeeding and the body arriving are
two different claims**, and until now only the first had ever been tested.

This adds `CobbieTerrainTest`: one course, walked end to end, and then the same idea gone wrong.

## The course

One `walk_to` plan for the whole thing: a raised walkway, a wall with a closed door, a two-block drop
off the end of it, a fence with a closed gate, a three-wide water channel, and a one-block lip out to
the far side.

Then three adversarial stages, each with the same rule:

1. **The gate shuts behind him** and a second closed door is ahead. Arriving means he kept going and
   opened the door in front of him rather than turning back to where he came from — the case that
   "walked the way he came" would otherwise pass.
2. **A two-block drop whose landing is a cactus.** Arriving unhurt means the route took the safe
   landing. A stop with a reason is allowed. **Arriving hurt is not** — it is checked against his
   health, not against his position alone.
3. **Water three deep.** Not a wade along the bottom: swimming is asserted as a fact about the body —
   in water *and* at the surface — because arriving at the far bank alone would pass whichever way he
   managed it.

## What a green does not mean

**The course is all downhill after the first step, deliberately.** A mob can drop any distance and
climb exactly one block, so a course that needs a two-block climb would be testing the game's jump
physics and not his navigation. A green here says he walks, opens, drops, swims and climbs a single
step on terrain that is not a clean corridor. It does **not** say he can climb, and nobody should read
it that way later.

## The rule being asserted

Two outcomes are allowed: **he arrives**, or **he stops and names what stopped him**. Three failures
are each caught separately, because each would otherwise slip through as a pass:

- **still walking after the deadline** — the loop;
- **`blockPosition` at the goal** with an obstacle in the way — a claim of arrival from a distance;
- **stopped with nothing said** — the silent death, which is the other half of the same defect.

## Status

Written, compiled, registered, and **not yet run**: the machine has no headroom for a client. Three
attempts on the previous slice died on `malloc` and this one is waiting for VRAM (the preflight needs
about 2500 MiB and the card has been swinging between 600 and 2600 as its owner uses it). The class is
one run, not three, so a window costs a single client start.

Nothing in it changes production code: it is a test and its registration.
