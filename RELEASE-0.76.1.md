# Cobbie 0.76.1 — "already done" only when the world says so

Artifact: `cobbie-0.76.1.jar`. **245 unit tests, 0 failed, 6 skipped.** `CobbieAlreadyDoneTest` and
`CobbieLifecycleTest` green in game; the full registry is `59 passed, 4 failed of 63`, and two of those four are the
brain-gated pair while the other two are a pre-existing defect named at the end.

## The defect

A walk aimed at **solid stone** was reported as satisfied, so the plan closed as
*"nothing left to do — the world had already done that"* about a destination no body can occupy. That is a **false
completion claim**, which is the one failure this project exists to prevent, and it was mine: the check added in
0.75.0 answered yes for a walk when he was *near* the target, and being near a spot is not standing in it.

## The rule, and the whole audited set

`alreadySatisfied` may answer **yes only where satisfaction is observable and specific**. Everything else answers
**unknown**, which means the step runs and fails with its own reason. A wrong "no" costs a walk; a wrong "yes" is a
lie about work nobody did.

| kind | predicate | verdict |
|---|---|---|
| `mine` | `getBlockState(pos).isAir()` — what was to be removed is gone | observable, specific |
| `place` | the resolved target block is the block standing at `pos` | observable, specific |
| `walk-to` | `BodySpace.fits(...) && closerThan(pos, 1.5)` — a place a body fits, that he is already in | was distance only; fixed |
| every other verb | `default -> false` | unknown, correct |

That is three kinds, not eight. The narrowness is the point.

## Before and after, same test

```
BEFORE  CobbieAlreadyDoneTest FAILED after 4265ms: java.lang.AssertionError: A walk into solid stone at 3, -60, 2
        was reported as already satisfied; the honest answer is that it cannot be stood in

AFTER   [already-done] walk into solid stone: FAILED — "failed — walk into that stone: 0/1 steps done, 1 failed.
        Details: walk-to [failed) x1 — i cannot stand there"
        [already-done] verbs judged: 8 with no predicate, all unknown; the observable control answered yes
        [gametest] summary: 1 passed, 0 failed of 1 — {CobbieAlreadyDoneTest=ok}
```

`CobbieAlreadyDoneTest` pins both halves the rule needs:

- **the case that caught it** — the fixture first proves he really is within 1.5 blocks of the stone, so the trigger
  is demonstrated rather than assumed, then judges the predicate directly and a real plan through it: no plan may
  complete, and the failure must name the destination (*"i cannot stand there"*);
- **the general rule** — eight verbs with no satisfaction predicate (`attack`, `collect`, `craft`, `use`, `equip`,
  `drop`, `chat`, `wait`) asserted **against the predicate itself, not through a plan**, so the rule holds for kinds no
  current verb exercises, with a positive control (`mine` on open air answers yes) so the test cannot pass by
  answering no to everything.

The legitimate case still works, on the same run of `Lifecycle`:

```
[lifecycle] stale: COMPLETED — "nothing left to do — the world had already done that" (2 of 2 objectives were already done, 0 failed)
[gametest] summary: 1 passed, 0 failed of 1 — {CobbieLifecycleTest=ok}
```

## Two corrections from the same brief

**The out-of-sight wait is now bounded** (`FollowSight`, five unit tests). The follow loop used to begin with a bare
`if (!loaded) return;`: nothing asked for, nothing said, retried on a timer forever — a companion looping in silence,
which is the shape this project keeps removing. It now says *"i can't see where you are from here"* once per episode,
waits inside a 200-tick deadline, **stops trying** past it rather than hammering the pathfinder at a chunk that is not
there, and resumes following on the tick the owner's chunk loads again, clearing the state so a later disappearance
may speak once more. The world-level half is not reachable in a gametest — a player's own chunk is always loaded while
they are online — so it is proved where the decision lives, in pure Java, and the in-game tier proves the gate does not
disturb normal following (eight probe rounds and `CobbieFollowTest` all green). No faked condition is called tested.

**The follow probe's default is eight rounds**, about thirty seconds, raised to twenty-five with
`COBBIE_FOLLOW_ITERATIONS=25` by whoever is chasing that freeze. A suite that pays eighty-five seconds every run is a
suite people skip.

## The fifth failure, not fixed and named here

`CobbieWorldChangeTest` and `CobbieWallRescueTest` are red, and **they are red on clean master too** — confirmed by
running both against `bbbbb0a` in a separate worktree (`2 of 2 client game test classes failed`):

```
CobbieWorldChangeTest FAILED after 7063ms: java.lang.AssertionError: He stopped, but did not explain why; he said "all done"
CobbieWallRescueTest FAILED after 2848ms: java.lang.AssertionError: He got out of the block at 18.0/20.0 at 1, -60, 2 inWall=false but the line he said does not name the cause: "all done"
```

Both read `lastSpokenLine` and expect the causal line; they get `"all done"`. That is 0.75.0's `sayToOwner` doing what
it was asked to — `deliverReports` now records the line it sends, so the plan's settled line lands after the causal one
and wins the field. The causal line was still said; the field only remembers the newest, and a test that can only see
the newest is the same weakness as a scenario that scores whatever plan is newest. The fix belongs in its own slice:
a small ring of recent lines with a `saidRecently(fragment)` question, and those two assertions asking whether the
cause was named. **Not in this PR** — it touches two tests that are not this one's subject, and the decision to change
them is not mine to take quietly.

## Arithmetic versus observed

- **Arithmetic:** 245 unit tests, 0 failures, 6 skipped, from `build/test-results/test/*.xml`; 63 classes in the full
  registry, `59 passed, 4 failed`, from the harness summary line.
- **Observed:** every `[already-done]`, `[lifecycle]` and assertion line above is copied from a run log.
- **Not verified:** the out-of-sight branch against a real unloaded chunk (not reachable, as above), and the two
  pre-existing reds, which are recorded rather than fixed.
