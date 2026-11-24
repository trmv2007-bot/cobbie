# Cobbie 0.28.0 — the brewing stand, and a mundane potion nobody ordered

Artifact: `cobbie-0.28.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and every in-game class green except the ones that need a live model.

A potion is the one thing a companion can carry that changes what a fight is: fire resistance,
water breathing, healing, night vision. This is how he makes his own.

```
you: brew with nether wart
Cobbie: brewed 3 awkward potion
```

## How it works

A brewing stand is not the anvil's shape. It is a `Container` that ticks itself: bottles go into
slots 0-2, one reagent into slot 3, fuel into slot 4, and `BrewingStandBlockEntity.serverTick`
charges the fuel, runs the twenty-second brew and rewrites the bottles. So the stand is the
furnace's shape — load it, let it work, take the results out — and `BrewingStandMenu` would only put
a `Player` between him and a machine that does not need one.

Everything is settled before anything leaves his bag. Which of his bottles the named reagent acts
on comes from the game's own brewing recipes, matched through `RecipeType.BREWING` exactly the way
the stand's own `isBrewable` matches them, so his refusal and the stand's cannot disagree. A stand
with no fuel gets one blaze powder out of his bag first (twenty brews' worth, so a single batch does
not waste it), and a stand someone else already has a bottle in is used for its free slots rather
than emptied.

The batch is done when the stand holds what the recipe said it would — not when enough ticks have
passed — and the trigger is a real comparison of item and components, so a batch that silently
failed reports "the stand took the nether wart and brewed nothing" instead of waiting out the
timeout.

## Two things the game corrected

**Water plus a glistering melon slice is a recipe.** The first test asserted that pair would be
refused, because glistering melon makes *healing* — from an **awkward** potion. The run came back
green on the wrong claim: the stand brewed a **mundane** potion, which is a real 26.3 recipe and
was sitting in `data/minecraft/recipe/brewing/potion_water_glistering_melon_slice.json` the whole
time. The refusal case now uses an item the stand genuinely will not take, because the useful test
is "he does not pretend to brew something the game refuses", not "the ingredient I picked happens to
make the potion I assumed".

**Which is worth saying plainly to the owner:** a water bottle and a glistering melon slice produce
a *mundane* potion, exactly as they would for you. Awkward first. The report says which potion came
out (`brewed 3 mundane potion`) rather than "3 potion", so a wasted batch is visible instead of
merely unsuccessful.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.28.0.jar`.

**In game:** `CobbieBrewTest` is new and green.

```
[gametest] brew ok: 3 water bottles -> 3 awkward potions in a real stand, one nether wart spent,
           the stand emptied; a reagent the stand will not take was refused and cost nothing
```

Four claims, each read back off the world rather than off a return code: an item the stand will not
take is refused *and* costs nothing (his bottles and wart are still in the bag afterwards); three
water bottles come out as three awkward potions; exactly one nether wart was spent; and the stand's
own bottle slots are empty, so the potions are in his bag rather than sitting in the stand.

**Not verified:** only one batch, in a stand he was standing next to, with the reagent named
explicitly. A stand outside his search radius, a batch that runs out of fuel part-way, splash and
lingering variants (which brew from the same slots), and gunpowder/dragon's breath are all
untouched by any run. Filling a glass bottle with water is still the `use` verb's job and is not
part of this: brewing starts from a potion he already has.
