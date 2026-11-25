# Cobbie 0.46.0 — he checks before he promises, and three negatives that were measuring nothing

Artifact: `cobbie-0.46.0.jar`. **179 unit tests green**, `CobbieInitiativeTest` green in game with three
phases, each waiting a measured duration.

The slice the owner asked for before milestone 8's second half: **announcing and then discovering was
the exact shape we spent a week on**, and it is cheap to remove.

## One gate, used twice

An un-mineable block no longer becomes a project. `canStartMining` asks what the step itself will ask:

- **the drop gate** — `bestToolFor`/`toolRefusal`, extracted so a promise about a block and the attempt
  at it cannot become two opinions that drift apart;
- **somewhere to stand** — the existing `standingSpotNear`, the one `startMine` uses to walk to work;
- **room for what comes out** — the existing `fitsInInventory` on the real drops.

The chooser counts only blocks that pass, so a pile he cannot take is not a candidate and nothing is
said about it.

## The test was lying, and it was mine

The new negative — stone in sight, no pickaxe, creative, nothing may be announced — passed in a
14.5-second test while claiming forty-five seconds:

```java
server.waitFor(s -> s.getTickCount() % 20 == 0, 900);   // matches the FIRST tick divisible by 20
```

A predicate on the tick count does not request a duration, it matches the first tick that satisfies it,
which is a few ticks after the call. **The ASSISTIVE negative had been passing the same way since it was
written** — a green that observed about a second while claiming twenty, in the very test whose purpose
was to prove nothing happens. This is the family the crew rules warn about: a run that claims success
and observed nothing.

`CobbieTestSupport.elapsed(server, ticks)` records the tick it started at and waits until that many have
actually passed, and every negative now prints the count it measured rather than a number typed by hand.

## Evidence

```
[gametest] initiative: announced nothing with only un-mineable stone in sight over 900 ticks,
           though the gate was running
[gametest] initiative ok: started no project in ASSISTIVE over 400 ticks, then started
           "gathering the oak log nearby" unprompted in CREATIVE and it reached COMPLETED —
           completed — gathering the oak log nearby: 3/3 steps done
```

The third phase is what keeps the first two honest: the same run that declines to announce on stone
starts a project on logs and finishes it, so the gate demonstrably fires.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped** (the opt-in network tier).
- `CobbieInitiativeTest`: 1 passed, 0 failed of 1, 76 s, with `--no-build-cache --rerun-tasks`.
- One run was lost to a dropped device session and retried unchanged; the code was not touched between
  the failing run and the passing one.

## Still open

Repairing a damaged structure stays out until the owner marks a target — the owner's ruling, recorded in
#39. The second half of milestone 8, choosing *what* to build rather than which block to move, is next.
