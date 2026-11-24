# Cobbie 0.29.0 — the smithing table, and the pickaxe that comes out is yours

Artifact: `cobbie-0.29.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and every in-game class green except the ones that need a live model.

Netherite is the top of the tool progression, and this is the only way to reach it.

```
you: upgrade my diamond pickaxe
Cobbie: upgraded minecraft:diamond_pickaxe into minecraft:netherite_pickaxe
```

## How it works

The smithing table is the least menu-like of the stations. Its result slot's whole job is
`getRecipeFor(SMITHING, input, level)` then `assemble(input)` — no level charge, no menu-side state,
nothing that depends on who is standing there. So there is nothing to drive: `station/Smithing` is
that lookup, and the table is required to exist within reach the way the anvil and the enchanting
table are.

Two details are the whole capability:

**`assemble` is `TransmuteRecipe.createWithOriginalComponents`.** It rewrites the base item and
carries the base's components — enchantments, custom name, damage — onto the result. That is what
makes netherite an upgrade of *his* pickaxe rather than a fresh one, and it is why the real stack
goes in rather than a copy stripped down to an item id.

**An upgrade is preferred over a trim.** Both are smithing recipes, so a search that took the first
match could answer "make my pickaxe netherite" with a decorative trim on a chestplate that happened
to pair up first. The table's own property sets (`SMITHING_BASE`, `SMITHING_TEMPLATE`,
`SMITHING_ADDITION`) prune the candidates first, then transforms win over trims, and only then does
anything get spent.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.29.0.jar`.

**In game:** `CobbieSmithTest` is new and green, and it asserts the components rather than the item
name, because a fresh netherite pickaxe would satisfy "did a netherite pickaxe appear" while having
destroyed the owner's work:

```
[gametest] smith ok: diamond_pickaxe -> netherite_pickaxe keeping the name "Rishi's Pick",
           efficiency 3 and 240 damage; template and ingot spent; an item with no smithing recipe
           was refused and cost nothing
```

The pickaxe goes in named, enchanted with efficiency 3 and already worn by 240 points. All three
come out on the netherite pickaxe. The template and the ingot are both spent, and the diamond
pickaxe is gone rather than kept alongside — so nothing was duplicated. A cobblestone in his bag is
refused with "I have no template and material that would upgrade", and that refusal leaves the
template in his bag.

**Not verified:** only a tool upgrade. Armour trims go through the same call and the same
preference order, but no run has applied one, so the trim path is untested. Only the netherite
upgrade recipe has been exercised; the other eleven `smithing_transform` recipes in the jar are
recipes out of the same table and are assumed rather than observed.
