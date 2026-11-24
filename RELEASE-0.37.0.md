# Cobbie 0.37.0 — the loom and the cartography table, and both picked from plain words

Artifact: `cobbie-0.37.0.jar`. **178 unit tests green**, and the in-game classes green: `CobbieLoomTest`,
`CobbieCartographyTest`, `CobbieGrindTest` and `CobbieAnvilTest`, plus `CobbieVerbChoiceTest` at
**6 right, 0 wrong of 6 measurable**.

The last two stations on the capability map, and the two the brief held back because they looked like
they needed state nobody could name. They do not.

```
you: put a creeper pattern on my banner
Cobbie: On it — stepping to the loom to weave the creeper pattern onto your banner in red.
        wove minecraft:creeper_banner_pattern onto the banner in minecraft:red_dye

you: zoom my map out with paper
Cobbie: On it — taking your map to the cartography table to zoom it out with paper.
        worked the map with minecraft:paper
```

## Chosen by name, never by index

This is the condition the brief set, and the item-state work in 0.33.0 is what satisfies it.

**The loom** takes the pattern as an **item** — `creeper_banner_pattern` is an ordinary item with a
registry id, and 0.33.0 is what made the bag line say which one he carries. `LoomMenu` derives its
pattern list from that item, so with one item in the slot the list has one entry and index 0 *is* the
pattern he asked for. Nothing in the code chooses a pattern: the name does. A pattern he cannot name is
refused with `I have no …`, and if he carries several and names none, the refusal lists them —
`tell me which pattern — I have …` — rather than picking one for him.

**The cartography table** takes a `filled_map` and a nameable addition: paper zooms, an empty map
copies, a glass pane locks. The `map_id` is the game's business and the model never has to say it.

## Two bugs the harness found, which their own tests had missed

**The weave reported failure on a weave that worked.** The check for `BANNER_PATTERNS` ran on a stack
*after* `add()` had emptied it into the bag, so a successful weave consumed the pattern and the dye,
put a patterned banner in his bag, and then failed the step. The same flaw was latent in the anvil
path from 0.26.0, which checked `leftover` after `add` too. Both now verify the made item **before** it
is stored.

That was only visible because the verb-choice harness scores the *step*, not the item — my own
`CobbieLoomTest` was green throughout, because it asserted the banner in his bag and never asserted
that the step reported success. It does now.

**The report said "wove minecraft:air".** The success sentence read the item id after the stack was
spent, so the owner would have been told he wove air onto a banner. Both this and the same flaw in
`grind` now name the item before it is consumed, and `CobbieLoomTest` asserts the sentence itself:
`wove minecraft:creeper_banner_pattern onto the banner in minecraft:red_dye`.

## Condition two: both are in the harness

Two new asks, same discipline as the last four — plain language, the world state printed for each
turn, and the precondition checked before scoring:

```
[gametest] VerbChoice ok  "cobbie put a creeper pattern on my banner" wanted weave
             -> walk-to(), weave(creeper_banner_pattern) -> completed
[gametest] VerbChoice ok  "cobbie zoom my map out with paper" wanted map
             -> walk-to(), map(filled_map) -> completed
[gametest] VerbChoice summary: 6 right, 0 wrong of 6 measurable
```

Six of six across brew, anvil, grind, shear, weave and map. A station is not a capability until he
picks it from a plain ask, and both of these now do.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.37.0.jar`.

**In game:** the loom's assertion is the layer that comes back — a `BANNER_PATTERNS` layer whose
pattern key is `creeper` **and** whose colour is `RED` — the pattern item and exactly one dye spent,
one banner still carried, and a pattern he does not have refused at no cost. The cartography
assertion is the map's own saved data: `scale 0 → 1`, one paper spent, still exactly one map.

**One run used the VRAM guard's own documented escape.** `gametest-preflight` refused at 2,190 MiB
free against its 2,500 bar, so the run went with `-MinFreeMiB 2000` and `-PskipGametestPreflight` and
completed normally — three classes, 47 seconds, no allocation failure. The bar guards against the
0.16.1 crash, which happened at 529 MiB free; 2,190 is four times that. Recorded so the number is not
something a reader has to guess.

**Not verified:** a banner that already has a pattern (layering a second one), the copy and lock
paths at the cartography table (only zoom has been run), and the "tell me which pattern" refusal when
he carries several and names none — every ask in the harness names one.
