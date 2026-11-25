# Cobbie 0.43.0 — the villager says what it sells, and the empty prompt line that cost a trade

Artifact: `cobbie-0.43.0.jar`. **179 unit tests green**, and the verb-choice harness at **10 of 10**.

A perception release, and the measurement is the whole story: the ask it was built for moved.

## What was missing

0.42.0 shipped a working `trade` verb and the harness caught the model declining it:

> I can't buy pumpkin pie — I don't see that trade available, and I only have emeralds for what the
> villager actually offers. Want me to try buying bread instead?

He was right. Perception said `minecraft:villager@2.0` — there is a villager, nothing about whether the
pumpkin pie is on offer — so the one fact a trade needs was absent from the prompt while the verb
behind it worked. The same class of defect as the item-state gap at 0.33.0, found the same way: by a
harness miss rather than by guessing.

## What the line says now

```
minecraft:villager@2.0 d2.0 hp20 sells 4 pumpkin_pie for 1 emerald, 2 bread for 1 emerald
```

Capped hard, because a prompt that lists everything stops being read:

- **three offers per villager, two villagers**; the rest are summarised as `+N more villager(s)` rather
  than eight more price lists;
- **the item and the price only** — the cheap half, with a second cost appended when an offer has one.
  Recipes, villager xp and restock state are noise to him;
- **a sold-out offer is skipped**, so the line never quotes a price nobody can pay.

`EntityNote` gained a `detail` field with a no-detail constructor, so nothing else that reads or builds
an entity note changed, and only merchants ever carry text.

## The measurement

The Captain's condition was the harness, not my taste: the pumpkin-pie ask had to move from "I don't see
that trade" to either a completed buy or an honest "too expensive". It moved, and the bag proves it:

```
[gametest] VerbChoice ok  "cobbie buy some pumpkin pie from the villager" wanted trade
             -> trade(pumpkin_pie) -> completed
             "bought 4 minecraft:pumpkin_pie for 1 emerald"
[gametest] VerbChoice summary: 10 right, 0 wrong of 10 measurable
```

The turn's own bag line shows `minecraft:pumpkin_pie x4` where there was none before, so the breadcrumb
is not the model's claim: it is what he is carrying.

That same run fixed a stale-read in the report too — it said *"bought 4 minecraft:air"*, because the
item id was read after `add()` had emptied the stack, the same flaw weave and grind had. Named before
it is stored now.

## Verification

**Unit (`./gradlew cleanTest test`):** 179 green, 6 skipped. Jar builds as `cobbie-0.43.0.jar`.

**In game, cache-free (`--no-build-cache --rerun-tasks`):** the harness run above, ten asks, ten plans,
with the trade completing and its item in the bag.

**Not verified:** a village of more than two villagers (the `+N more villager(s)` summary is written but
no run has had three); a two-item price in the perception line; and the rest of the ten asks are
re-measured only in this one session, so their scores remain single observations rather than rates.
