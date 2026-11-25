# Cobbie 0.61.0 — the probe interleaved, and the wait that waits nothing, caught by a check

Artifact: `cobbie-0.61.0.jar`. **219 unit tests green**, `Perf` probe re-run.

## The probe is interleaved now

Phases used to run in blocks, which meant the server's own drift was larger than the effect. Now each round kills
him, samples, spawns him, samples — five rounds, each pair adjacent in time:

```
round 1: without 1291.2  with 817.9  delta -473.2 us/tick
round 2: without  603.0  with 572.6  delta  -30.4 us/tick
round 3: without  631.7  with 747.3  delta  115.6 us/tick
round 4: without  642.8  with 495.7  delta -147.1 us/tick
round 5: without  519.4  with 519.6  delta    0.2 us/tick
paired delta median -30.4 us/tick, range -473.2 .. 115.6, 2 of 5 rounds above zero
verdict: he costs less than this measurement can resolve (median -30.4 against a spread of 588.9)
```

The verdict line is the probe's own conclusion rather than a number I pick out of the spread. Model calls per
minute, measured over 200 ticks each: **idle(assistive) 0.0, idle(creative) 0.0, working 6.0**.

## The wait that waits nothing, caught by a check this time

0.60.0's first probe reported the same number in every phase because
`waitFor(s -> s.getTickCount() % 20 == 0, ticks)` matches the **first** tick divisible by twenty and returns at
once — the bug fixed in 0.46.0, reintroduced by the person who fixed it, in the slice about measuring.

The fix that matters is not the two call sites. `tools/audit-bug-families.py` grew a **fifth family** for the shape,
and it found two live instances on its first run:

- `CobbieAnimalProbeTest:61` — a 240-tick wait that was really about twenty ticks;
- `CobbieVerbChoiceTest:223` — **the harness's own pause between asks**, a 40-tick gap that was really 0–9 ticks.

Both now use `CobbieTestSupport.elapsed`. The second one is a candidate for the plan drift that made the harness
ratio untrustworthy — **and I have not re-measured it, so I am not claiming that.** What I can say is that the
pause the harness believed it was taking, it was not taking.

The lesson is in `docs/AUDIT.md`, in the terms that make it useful: the bug is not that the helper was wrong, it is
that **the wrong helper is still the obvious thing to type** — `waitFor(predicate, timeout)` reads like "wait a
while", compiles, and looks correct. Until the shape is unwritable, it will be typed again, which is why a check
exists now instead of a resolution.

## Verification

- `cleanTest test`: **219 tests, 0 failed, 6 skipped**.
- `tools/audit-bug-families.py`: family E clean (0 hits) after the two fixes, all earlier families unchanged.
- `runClientGameTest -PcobbieTests=Perf`, `--no-build-cache --rerun-tasks`; the probe prints and never asserts.
