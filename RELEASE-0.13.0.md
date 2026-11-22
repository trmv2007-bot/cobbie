# Cobbie 0.13.0 — steering, and what "parallel" can honestly mean for one body

Artifact: `cobbie-0.13.0.jar`. 107 unit tests, 14 in-game tests.

## Steering: interrupt without losing the job

Before, an instruction given mid-job was either refused ("already busy") or cancelled what he was
doing. Both are wrong for a companion you want to direct.

Now there are two lanes:

- **Typed commands are urgent.** They park the running job — interrupting the step in flight, keeping
  every step not yet done — and run immediately.
- **Model plans queue** behind them and resume the parked job when the urgent work finishes.

So `cobbie wave` in the middle of a twenty-block walk makes him wave, then pick the walk back up and
finish it. Verified in game:

```
plan ok: survived a bad middle step, queued a second job,
         and resumed a walk after a mid-job wave
```

`stop` cancels the running job, the queue, the urgent lane and the parked job — the one command that
means "forget all of it".

## Parallel: the honest version

One body cannot mine and build at once, so what a single Cobbie gets is a **scheduler**, not
simultaneity: multiple requests are all accepted, ordered by priority, and none is dropped. On top of
that the brain already allows concurrent model requests (`max_concurrent_requests`, up to 16).

Real parallel *work* needs parallel bodies. The companion cap is 16 per player, so several Cobbies can
each hold their own job — but chat currently binds to one companion per player (`companionOf` returns the
first), so "you three, build this" is not wired yet. That is the next honest step if you want a crew
rather than one good companion, and it needs a naming/addressing scheme ("Cobbie, you mine") rather than
a bigger queue.

## Tests

Two new unit tests pin the contract: an urgent order interrupts and the interrupted job resumes; `stop`
clears queued and parked work. The in-game plan test now covers a bad middle step, a queued second job,
and a mid-walk steering wave.

## Still open
- Multi-companion addressing (above).
- Plan quality on long jobs.
- Death drops onto the ground remain broken; his bag goes to the owner.
- Live-model tests (smoke, chat) need the pool or Ollama running.
