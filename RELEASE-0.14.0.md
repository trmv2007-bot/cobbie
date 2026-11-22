# Cobbie 0.14.0 — one job, added to while he works

Artifact: `cobbie-0.14.0.jar`. 108 unit tests, 14 in-game tests.

"Parallel" for a single body means **composition**, not simultaneity: he is mining a seam, you say
"get coal too", and the coal joins the errand he is already on.

## New work merges into the running plan
- A model request that arrives while he is busy is appended to the plan in flight, not queued as a
  second job and not replacing the first. The report covers the whole errand:
  `mine the seam / 2 steps`.
- Within what you just asked for, he does the **nearest target first**, so a request does not mean
  walking back along a tunnel he has already dug.
- Typed commands keep their own behaviour (0.13.0): they pre-empt, parking the running job so it
  resumes afterwards.
- `stop` still clears everything: running job, queue, urgent lane, parked job.

## Verified
```
plan ok: survived a bad middle step, queued a second job,
         resumed a walk after a mid-job wave,
         and merged new work into the running job (mine the seam / 2 steps)
```
The in-game test mines a seam, adds a second target mid-job, and requires both blocks gone with a
single plan covering both.

## Test honesty
The wiki lookup is now network-tolerant: the plumbing is unit-tested, and an unreachable wiki is
reported in the run rather than failing the suite. A test that goes red on your connection teaches
nothing about Cobbie.

## Still open
- The ordering heuristic is proximity only. A human would also consider "on the way past" versus
  "backtrack", which needs the path, not just the distance.
- Long plans are possible but not proven *wise*.
- Death drops onto the ground remain broken; his bag goes to the owner.
- Live-model tests need the pool or Ollama running.
