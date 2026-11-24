# Cobbie 0.26.0 — the anvil, and a book that actually had an enchantment in it

Artifact: `cobbie-0.26.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game classes green except the ones that need a live model.

The anvil's main job is putting an enchanted book onto something. That is what this does.

```
you: put the book on my sword
Cobbie: put an enchantment on minecraft:iron_sword for 1 level(s)
```

## How it works

The item and the book go into a real `AnvilMenu`, and the take is a real click on its result slot
(`clicked(RESULT_SLOT, PICKUP, actor)`), so the level charge, the refusal when he cannot afford it and
the merging of enchantments are the game's. Instabuild is stripped for the call, because `mayPickup`
reads it, and his level and progress are copied in and read back out.

## The trap: the anvil prices against his level *before* it offers

The first version saw an empty result slot with both inputs present, and the probe said why: the anvil
decides what to offer against the player's own level, so an actor at level zero is offered nothing.
Unlike enchanting, where the level only matters at the click, here it has to be in place **before**
`createResult()`. That is fixed and the comment in `FakeActor.applyBook` says so.

## The test fixture was the other half, and the oracle proved it

The test then failed with "the anvil will not put that book on that" even with the level in place. The
oracle settled it the same way the enchanting price was settled — a **real player** at the same anvil
with the same pair:

```
[oracle] book=1 minecraft:enchanted_book stored=ItemEnchantments{enchantments={}}
[oracle] real player result=0 minecraft:air
```

The book was the problem. `/enchant` on a held book left an **empty** stored-enchantment map, and the
test asserted only that the component was *present* — the same failure shape as asserting a value that
changes later than the read: asserting existence instead of content, on the one thing whose content
mattered. With the book built through `ItemEnchantments.Mutable.set(...).toImmutable()`, a real player
gets `1 minecraft:iron_sword` and so does he.

```
[gametest] anvil ok: book applied, level 6 -> 5, book spent; a level-short attempt was refused
```

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.26.0.jar`.

**In game:** `CobbieAnvilTest` is new and green — the enchantment lands on the iron sword, the anvil's
level charge is paid (6 → 5), the book is spent, and a level-short attempt is refused.

**Not verified:** combining two damaged tools on the anvil and renaming are **not** implemented — this
release is the enchanted-book path only, which is what an anvil is mostly for. Both are named here
rather than left to be discovered. An anvil that is damaged or chipped behaves the same in the menu, but
no run has used one.
