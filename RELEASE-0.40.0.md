# Cobbie 0.40.0 — the farm: harvest the ripe, sow it again

Artifact: `cobbie-0.40.0.jar`. **178 unit tests green**, `CobbieFarmTest` green, and the verb-choice
harness at **7 of 8**.

First slice of phase 4. Most of farming was already there, and a probe said so before any code was
written.

## What the probe found

A hoe, seeds and bone meal all work through the `use` verb he has had since 0.21.0, because none of
them is a class: a hoe is a `BLOCK_TRANSFORMER` data component and seeds are a plain `BlockItem`, both
reached through `ItemStack.useOn`. Five lines of probe, printed rather than asserted:

```
[probe] hoe: dirt -> farmland (result=Success)
[probe] seeds on farmland: wheat (result=Success)
[probe] bone meal: age 0 -> 2
[probe] ripe wheat drops 5 item(s) with a bare hand
```

So **tilling and sowing needed no code at all** — they are `use` with the right item, which the model
already picks. The missing piece was the cycle.

## The cycle: `farm`

One verb: every ripe crop within four blocks comes off, its drops are his, and the same cell is sown
again from his bag. What replants a crop is not a table of mine — `CropBlock.getBaseSeedId` is
protected, so the seed is found by the link the game keeps: an item whose `BlockItem` places *this*
block. That asks the registry rather than asserting a mapping that would rot when a crop is added.

Two refusals carry as much weight as the harvest, because "it did not work" teaches nothing:

```
nothing is ripe yet — 4 crop(s) are still growing
harvested 3 ripe wheat and had no seed to replant with
```

The first names the reason, the second refuses to claim a replant it did not do.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.40.0.jar`.

**In game, cache-free (`--no-build-cache --rerun-tasks`):**

```
[gametest] farm ok: 3 ripe wheat harvested and all 3 cells resown, the growing crop untouched;
           a field of seedlings refused as "not ripe"; and a harvest with no seeds reported that
           instead of claiming a replant
```

The assertions are the world after the run: zero ripe crops left standing, three cells at age 0, and
the crop that was growing still at age 2 — a harvest that trampled the seedlings would fail.

**And the model picks it.** Eight asks in one session, `pooled-deepseek` pinned:

```
[gametest] VerbChoice ok  "cobbie harvest the wheat and replant it" wanted farm
             -> farm(wheat) -> completed — harvested 3 ripe wheat and replanted them all
[gametest] VerbChoice summary: 7 right, 1 wrong of 8 measurable
```

## What the one miss was, since it is not the verb

`"cobbie till this dirt and plant some wheat"` produced **no reply at all**:

```
Cobbie: no text back after 801 reasoning tokens on a 800 token budget — raise max_tokens
```

The model spent the whole budget thinking and returned nothing usable. That is the known shape of
this reasoner — it was the cause of the 0.16.x "no text" failures — and it is a configuration finding,
not a farming one: with a longer prompt (eight verbs and a fenced snapshot) the 800-token ceiling is
now tight enough to be hit. Named here rather than fixed, because `max_tokens` is Rishi's setting and
the right value is his call.

## Not verified

- Harvesting crops other than wheat. The seed lookup is generic and carrots, potatoes and beetroot all
  follow it, but no run has touched them.
- A farm larger than the four-block radius, which would need him to walk the plot.
- Hoeing or sowing through a *plan* rather than a direct ask: the probe drove the actor, and the
  harness showed the model choosing `use`, but no run has watched both steps of "till and plant" run
  through the executor.
