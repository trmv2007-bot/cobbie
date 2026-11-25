# Cobbie 0.70.0 — an adversarial run, and three things it found about the obstacles

Artifact: `cobbie-0.70.0.jar`. **229 unit tests green**, `Adversity` green in game.

Every other class in this suite proves a verb. None proved what happens when the world moves while a plan is
running — which is the thing a player meets in their first hour. This one hands him a task and then takes the world
out from under it, with one rule:

> **He finishes, or he stops and says precisely what stopped him. He never reports completion he did not achieve.**

Both halves are asserted, because a run that dies silently is the other half of the same defect.

## Three obstacles, three verdicts

```
vanished block: FAILED — mine that oak log: 0/1 steps done — "there is nothing at 5 -60 5"
wall in the path: COMPLETED, stone left=false — mined at -2 -60 6, stored 1 item(s)
stale skill: "gather-the-oak-logs" needs minecraft:oak_log nearby and I see none — I would be swinging at nothing
```

The first is the invariant doing its job: the block was removed before the swing landed and he said exactly which
coordinate was empty instead of claiming the mine. The second is the opposite result and just as important — a wall
between him and the block is not a failure, he pathed around it and the block is measurably gone. The third is a
skill replayed after every log in sight was deleted: it refuses and names what it needs, rather than swinging at air.

## What the obstacles themselves taught

**A running step's tool lives in his hand, not in his bag.** The first version of the tool-removal obstacle emptied
his bag and he mined the stone perfectly well — the step had already raised the pickaxe into his hand. That is the
same distinction the fishing rod taught, one layer down, and a test that only empties the bag proves nothing.

**In creative, "no tool" is not an obstacle.** With the pickaxe taken from the hand *and* the bag he still broke the
stone and stored a cobblestone: a bare hand breaks stone in creative, and his drop comes off the loot table rather
than off the swing. His "he keeps what he breaks" convention therefore diverges from a creative *player*, who gets
nothing at all. That is a convention, not a bug — but it is why the second obstacle became a wall.

## Verification

- `cleanTest test`: **229 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Adversity`: 1 passed of 1, `--no-build-cache --rerun-tasks`, three scenarios,
  each asserting the world as well as the sentence.
