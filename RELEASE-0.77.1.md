# Cobbie 0.77.1 — what he said is a window, not a slot

Artifact: `cobbie-0.77.1.jar`. **250 unit tests, 0 failed, 6 skipped.** `CobbieWorldChangeTest` and
`CobbieWallRescueTest` green, from the same assertions that were red.

## The defect

Two classes were red on master, and both for the same reason: they asked *what did he say* by reading one field.

```
CobbieWorldChangeTest FAILED: He stopped, but did not explain why; he said "all done"
CobbieWallRescueTest FAILED: He got out of the block at 18.0/20.0 at 1, -60, 2 inWall=false but the line he said does not name the cause: "all done"
```

He had said the causal line both times. It was overwritten by the plan's own settled summary, which lands a tick or
two behind it — 0.75.0's `sayToOwner` records the line it sends, so *"all done"* took the field. The tests were right
to fail: one field answers "what is the newest thing", never "did he say X", and those two questions stop being the same
question the moment two lines arrive inside one observation window.

`CobbieWorldChangeTest` had already written the lesson down for itself — *"Poll rather than sleep a fixed number of
ticks: … reading one field at one instant would score whatever happened to be last"* — and polling does not help when
the wrong line is what is left.

## The fix

`RecentLines`: the last eight lines, newest last, with `last()`, `saidRecently(fragment)`, and `recent()` for a failure
message that shows the actual words. The entity keeps one alongside `lastSpokenLine`, and every place he really speaks
records through it — in-world speech, the owner-facing line, and the recall line. Five unit tests pin the properties
that matter: an older line survives a newer one, the history is bounded and drops the oldest, order is oldest-first, an
empty history answers "" rather than null, and blanks are not speech.

Both tests now ask the question they always meant. **Nothing was weakened:** the same lines are required, the same
`"changed worlds"` and `"block"` fragments are what has to be found, and the assertions are the ones that were red.

```
[gametest] world change guard ok: he said [on my way, we changed worlds, so I stopped what I was doing, all done]
[gametest] world change ok: the guard ended a plan, and a real crossing left him at ... the_nether 8, 63, 8 busy=false (same uuid) with no plan to cancel
[gametest] wall rescue ok: sealed at 2, -60, 2, stood up at 18.0/20.0 at 1, -60, 2 inWall=false, saying [i was inside a block, got myself out, all done]
[gametest] summary: 2 passed, 0 failed of 2 — {CobbieWorldChangeTest=ok, CobbieWallRescueTest=ok}
```

Those two printouts are the defect and the fix in one line each: the causal line is *in the window*, with the report
behind it, exactly where a reader needs it.

## Why the mod and not the tests

The alternative was to teach the two tests to read the newest line differently — to accept that the report wins. That
would have hidden a real thing: a companion whose history is one field is one whose answers about its own recent
behaviour depend on timing. The ring is also a query a person debugging a companion actually wants, and it is what the
wake-up line and the out-of-sight line will both be checked through from now on.

## The suite list

The two classes were outside every filter used today, which is how a filtered run could look green while master was
not. **The canonical full run is the registry itself — 63 classes in `CobbieGameTests`** — and the count belongs in
every report so it is checked against that file rather than against a remembered list. A `-PcobbieTests` filter is a
subset and proves nothing about the classes it omits.

## Arithmetic versus observed

- **Arithmetic:** 250 unit tests, 0 failures, 6 skipped, from `build/test-results/test/*.xml`; 2 passed of 2 in game,
  with the class names in the summary; 63 registry entries counted in `CobbieGameTests`.
- **Observed:** every `[gametest]` line above is copied from the run log.
- **Not verified:** nothing about the brain — these two classes need no model.
