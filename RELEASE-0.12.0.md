# Cobbie 0.12.0 — multi-step work that survives

Artifact: `cobbie-0.12.0.jar`. 105 unit tests, 14 in-game tests.

Three things stopped him finishing a job, and all three are fixed.

## 1. A failed step no longer cancels the rest of the plan
Before: step 1 fails after its retries → every remaining step is marked skipped → you get one attempt
and silence. So "place two blocks then dig a hole" did the first thing or nothing at all.

Now: a world step that fails after its retries is marked failed and **he carries on with what is left**,
and the report names both what failed and what got done. Only a genuine refusal — a permission you
switched off, a verb he does not have — still stops the plan, because continuing past that just fails
five more times and buries the real reason.

## 2. Instructions queue instead of being refused
Before: anything said while he was busy got "already busy with…". A companion that drops your
instruction is indistinguishable from one that ignored it.

Now: up to eight instructions wait in order behind the running one, and `stop` clears the queue as well
as the current job. An explicit typed command still pre-empts (0.11.0), so "drop your stone" wins over a
walk he was doing.

## 3. "planner guard tripped" is gone
The execution loop was bounded by the step count, so a step that retried could trip the bound and abort
the whole plan with that message — it appeared twice in one real session. The bound now accounts for the
retry budget.

## Verified
```
plan ok: survived a bad middle step, and queued a second job
```
A three-step plan with a deliberately impossible middle step still placed both blocks, and a second
instruction given mid-walk was queued rather than refused. Two unit tests pin the new contract, and the
two tests that encoded the old behaviour were rewritten — deliberately, because the behaviour changed.

## Still open
- Plan *quality*: 24 steps are allowed and now survivable, but nothing proves a long plan is sensible.
  Worth watching what he queues for a build request.
- Death drops onto the ground remain broken in this build; his bag goes to the owner instead.
- The live-model tests (smoke, chat) need the pool or Ollama running.
