# Cobbie 0.31.0 — the grindstone, and two stations named instead of guessed

Artifact: `cobbie-0.31.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and every in-game class green except the ones that need a live model.

Enchantments come off a thing.

```
you: take the enchantments off my iron sword
Cobbie: took the enchantments off minecraft:iron_sword
```

## How it works

The grindstone's menu carries no XP in 26.3 — its `removed` only clears the input slots, and the
class has no experience reference in it at all — so there is no price to model and nothing to lose
through the actor. The item goes into a real `GrindstoneMenu`, `computeResult` decides whether it is
something that can be stripped, and the take is a real click on the result slot. A curse stays on,
because that is what `removeNonCursesFrom` does.

What comes back is then checked for enchantments before it is accepted. "The menu offered something"
and "the enchantments are gone" are different claims, and only the second one is worth telling the
owner.

## Two stations that are named rather than shipped

The brief put the loom and the cartography table last and asked for a plain answer where one needs
state that cannot be named. Neither of those is the problem, and both are still not here:

- **The loom** needs a banner pattern chosen by **menu index**. `clickMenuButton(player, i)` takes an
  index into a list the game derives from the banner, the dye and the pattern item — the model can
  name the pattern item (`creeper_banner_pattern` is an ordinary item), but the index is not
  something it can reason about, so the code would be picking for it. Doable, needs its own run, and
  a patterned banner is decoration.
- **The cartography table** needs a map carrying a `map_id` — a real filled map, not a nameable
  item — and its two behaviours (zoom with paper, lock or clone) are menu-side with no public
  assemble. Also decoration.

Both are rated low in `docs/PLAYER-CAPABILITIES.md`, and shipping either one unverified would put a
claim in a release note that no run supports. They stay unimplemented and named, which is the
honest version of "last".

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.31.0.jar`.

**In game:** `CobbieGrindTest` is green:

```
[gametest] grind ok: an enchanted iron sword came back with no enchantments and its 100 damage
           untouched; an unenchanted pickaxe was refused with the reason and kept
```

The assertion is the **absence** of content, not the presence of a component — the trap the anvil
slice fell into. The sword that comes back has an empty enchantment map *and* the same 100 points of
wear, so it is the same sword with the enchantment taken off rather than a fresh one. An unenchanted
pickaxe is refused with "I have no enchanted minecraft:stone_pickaxe" and is still in his bag
afterwards.

**Not verified:** only the strip. The grindstone's other job — merging two damaged copies of the same
item and combining their enchantments — has no verb of its own here, because `repair` already covers
"mend two damaged things into one" through the crafting grid. Cursed items go through the same call
and no run has used one, so the curse-stays-behind behaviour is the game's, taken on trust.
