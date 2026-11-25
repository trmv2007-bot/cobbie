# Cobbie 0.42.0 — trading, and the villager's own bookkeeping

Artifact: `cobbie-0.42.0.jar`. **179 unit tests green**, `CobbieTradeTest` green, and the verb-choice
harness at **9 of 10**.

Third slice of phase 4. The probe answered the question the brief asked before anything was built.

## What the probe settled

```
[probe] offers=2 costA=1 minecraft:emerald result=4 minecraft:pumpkin_pie uses=0
[probe] merchant menu: slots=39 resultSlot=4 minecraft:pumpkin_pie
[probe] notifyTrade: uses 0 -> 1 xp=5 tradingPlayer=null
```

Three answers, and the last is the one that matters:

1. A farmer at level 2 offers real trades, and `getCostA()` is the price **the villager would charge**.
2. `MerchantMenu` does build against the fake actor and its result slot fills — the menu route works.
3. **`notifyTrade` runs with no trading player at all**: uses 0 → 1, +5 villager xp.

So no menu is needed. The villager's own code counts the use, pays its xp and drives its restock; the
verb only spends what the offer asks and hands over what it gives.

The probe also failed first, which is worth recording: a **synthetic offer on an unemployed villager
does not survive** — `overrideOffers` left `getOffers()` empty, because a villager's trade list is
rebuilt from its profession. The probe and the test both use a real farmer with real trades now.

## The verb, and the owner's currency

```
you: buy some pumpkin pie from the villager
Cobbie: bought 4 minecraft:pumpkin_pie for 1 emerald
```

Nothing is spent until the price has been read off the offer and counted against **his own bag**, and a
refusal quotes what it would have cost — *"that costs 3 emeralds and I have 1"*. A trade the villager
will not take is refused naming which half failed: the price, the stock (*"sold out of X for now"*), or
the item not being sold at all (*"it offers: 4 pumpkin_pie for 1 emerald, …"*). Nothing is retried.

## Verification

**Unit (`./gradlew cleanTest test`):** 179 green, 6 skipped. Jar builds as `cobbie-0.42.0.jar`.

**In game, cache-free (`--no-build-cache --rerun-tasks`):**

```
[gametest] trade ok: bought minecraft:pumpkin_pie for emeralds (6 -> 5), the villager counted the use
           (0 -> 1); a price he could not meet was refused with the number, and an item not sold was
           answered with what is on offer
```

Every claim is read off the world: the item arrived, the emeralds left, and the **villager's own use
counter moved** — which is the proof that a real trade happened rather than an item appearing.

## Why the model declined, and it is not the verb

Nine of ten asks were picked; the miss is `"cobbie buy some pumpkin pie from the villager"`:

> I can't buy pumpkin pie — I don't see that trade available, and I only have emeralds for what the
> villager actually offers. Want me to try buying bread instead?

He is right. **His perception lists the villager as `minecraft:villager@2.0` and says nothing about
what it sells**, so the one fact a trade needs is missing from the prompt. The verb works — the test
proves it — but a model that cannot see the offers cannot ask for one, and refusing was the honest
answer rather than inventing a price.

That is the next slice and it is a small one: put each nearby villager's offers in the perception line
the way item state went in at 0.33.0, and the ask becomes answerable.

## Not verified

- **Second-item prices.** `getCostB()` is spent when an offer has one and the test's farmer only had
  single-item offers; no run has paid a two-item price.
- **Restock.** The villager's counter moved and its restock is its own code, but no run has waited for
  a sold-out offer to come back.
- **Wandering traders, and any villager that is not a farmer.** The verb takes any `Merchant`.
