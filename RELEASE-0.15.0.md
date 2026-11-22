# Cobbie 0.15.0 — orchestrated model calls, no stage directions

Artifact: `cobbie-0.15.0.jar`. 109 unit tests, 14 in-game tests, green against the live pool.

## His AI calls are orchestrated now

Before: every chat line fired its own model request immediately. Two quick lines meant two racing
requests, an overloaded pool meant "busy right now, give me a second", and a burst of instructions was
interpreted as separate thoughts.

Now there is a small scheduler in the brain:

- **Bursts coalesce.** Lines within `chat_batch_ms` (default 350 ms) are joined into one user turn, so
  "mine this" and "and get the coal too" become one request the model sees together — which is exactly
  the composition case from 0.14.0.
- **Concurrency is a queue, not a refusal.** When `max_concurrent_requests` is saturated, the batch
  waits for a slot instead of answering "busy". Nothing is dropped and nothing is shouted at.
- **Typed commands never enter the queue at all** — they are answered deterministically (0.11.0), so
  the model is only called for things that actually need a brain.
- **Stale replies are discarded** by the existing cancel path, so a superseded answer never lands.

`chat_batch_ms` is in `config/cobbie.json` (0 disables batching entirely).

## No more stage directions

`*waves*` appeared in chat in a live run while he was not waving. Two fixes:
- the parser strips `*...*` stage directions from speech, with a unit test;
- prompt rule 11 tells him to queue the `wave` action instead of describing it.

Live reply after the change: `"Hi there! So good to see you!"`

## Verified against your pool

```
chat ok: reply length=29 "Hi there! So good to see you!"
cobbie alive at (162,70,162) name=§eCobbie
imperatives ok: waved, walked to the owner, dropped his stone; wiki said: Obsidian …
plan ok: … merged new work into the running job (mine the seam / 2 steps)
mine / place / combat / persistence / follow / commands / config ui / death loot — all ok
```

## Still open
- Batching trades a little latency for coherence; 350 ms is a guess worth tuning on your machine.
- Plan quality on long jobs is still unproven.
- Death drops onto the ground remain broken; his bag goes to the owner.
