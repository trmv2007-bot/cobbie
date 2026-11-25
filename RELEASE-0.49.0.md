# Cobbie 0.49.0 — a project he has done, he can do again

Artifact: `cobbie-0.49.0.jar`. **179 unit tests green**, `CobbieSkillTest` green in game.

Milestone 9. Most of the machinery already existed — a persisted skill registry, learning from any
completed plan, replay through the same `TaskRunner`, `/cobbie skills list|delete|on|off`. What it could
**not** do was replay against a *fresh target*, and nothing anywhere measured the cost claim. Both of
those gaps turned out to be defects, and both are now closed.

## A skill stands itself up where the work is

Skills were recorded as offsets **from where he stood**. A skill learned by one tree replayed by the next
tree pointed at air. The learner now anchors to the **first block it works on** and records which block
that was, so replay puts the procedure where the material is:

```
skill: learned "gather-the-oak-logs" — 3 anchored step(s), saved to disk
```

## The learned shape is not the shape of the next pile

With the anchor fixed, the replay still mined one block and swung at air twice:

```
failed — skill: gather-the-oak-logs: 1/3 steps done, 2 failed, 2 retries.
Details: mine minecraft:oak_log [done) — mined at 3 -60 1 |
         mine [failed) — there is nothing at 4 -60 1 |
         mine [failed) — there is nothing at 5 -60 1
```

The offsets record the geometry of *that* pile, and the fresh pile ran along a different axis. Replaying
geometry is the wrong level: what a gather skill **means** is "take three of this block". Each mine step
now takes the next nearest block of the recorded material, using the learned spot first when it still
holds the right block — so an unchanged pile replays exactly as it was learned, and a different pile
replays sensibly. Deterministic, no model call, and still a plan: interruptible, verifiable, one row in
the panel.

## The measurement, which is the half that matters

```
skill ok: replay of "gather-the-oak-logs" harvested a fresh pile with 0 model request(s)
skill diag: replay plan COMPLETED — skill: gather-the-oak-logs: 3/3 steps done
skill: "/cobbie skills delete gather-the-oak-logs" removed it
```

The first time costs a brain round trip because the procedure does not exist yet; the replay costs
**zero**, and the count is read from a counter on the client that sends the requests, not inferred.

One honest caveat the counter taught us: a replay that **fails** costs one request, because a failed plan
still asks the model what to do next. So the claim is exactly "a replay that works costs none", and the
test asserts the working case.

## Two rules, learned the hard way this week

1. **A plan's later steps must be checked against the world its earlier steps will make.** `canPlace`
   validated each step against the world *as it stood*, so a plan could never propose anything taller than
   one block — four runs produced no project at all before a probe found it. `canPlaceOnOwn` is the other
   half of the question.
2. **A negative test whose precondition prevents the event is not slow, it is vacuous.** Two phases sat
   inside the 2400-tick cooldown after a project, so they passed while measuring nothing — the same family
   as `waitFor(tick % 20 == 0, N)` matching the first tick. Neither is a timing detail; both are a test
   that cannot fail.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped** (the opt-in network tier).
- `CobbieSkillTest`: 1 passed, 0 failed of 1, `--no-build-cache --rerun-tasks`; it asserts the learned
  skill is on disk (so it survives a restart), that its steps are anchored, that the replay harvests a
  pile it was never learned on, that the request counter does not move, that the skill is credited, and
  that the delete command removes it.
- The skill file lives in the config directory and may hold skills from other worlds, so the test looks
  its skill up **by name** — the first version asserted about a `wave-hello` skill left there by an
  earlier run, which is its own small lesson.
