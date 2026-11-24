# Cobbie 0.25.0 — the enchanting table, on his own levels

Artifact: `cobbie-0.25.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game classes green except the ones that need a live model.

0.24.0 gave him experience. This is the first thing that spends it, and the first capability in the mod
that has to drive a **menu** rather than a recipe.

```
you: enchant my sword
Cobbie: enchanted minecraft:diamond_sword for 1 level(s) and 1 lapis
```

## How it works

`EnchantmentMenu` wants a `Player.Inventory`, so the actor from 0.21.0 holds the item and the lapis,
his level is copied in before `clickMenuButton` and read back afterwards, and the enchanted stack is
taken out of the menu's own slot. The table has to exist within twelve blocks; the lapis is counted
before anything is spent; and the refusal is the honest one — *"I need 1 level for that and I have 0"*.

## What a level of enchanting costs, with the arithmetic

The first version quoted the wrong number, and settling it changed the code rather than the wording.

`EnchantmentMenu.costs[]` is **not** the price. It is the enchantment *power* the option rolls with.
The price of pressing a button is its **index + 1** — which is also how many lapis it takes.

The proof is the game itself: a real `ServerPlayer` at level 4, progress 0, in front of the same table
with `costs = [2, 2, 6]`, pressed the first button and went **4 → 3**. The actor did exactly the same
thing in the same world with the same item, and also went 4 → 3. So the charge is right, my earlier
reading of `costs[]` was wrong, and the refusal now quotes the price (`slot + 1`) rather than the
power. The guard in the bytecode agrees: it checks `experienceLevel >= index + 1` first, and only
falls back to `costs[index]` for the creative-materials case.

## Two traps in the actor, both now handled

- **`FakePlayer` starts with creative-style abilities, so `instabuild` is true.** A menu that sees it
  charges nothing: the first run enchanted a diamond sword for free. The actor is stripped of
  instabuild for the call and restored afterwards, and the test asserts the charge, so it cannot
  regress quietly.
- **`AbstractContainerMenu.setItem` takes a revision index as well as a slot**, so slot access goes
  through `getSlot(i).set(...)`.

## The test, and the race it had to fix

```
[gametest] enchant ok: sword enchanted for 1 level(s), level 4 -> 3, lapis 3 -> 2; a level-short
           request was refused with the numbers
```

The first runs failed on "he was not charged any levels" while the probes showed the charge landing.
The cause was the test: it queued a second plan while the first was still retrying, so it asserted
against a plan that had not started (`report=validated — 0/1 steps done — [pending)`). Every plan-shaped
stage now waits for `lastPlan().isFinished()` before the next one begins.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.25.0.jar`.

**In game:** the suite's per-class line is in the pull request; `CobbieEnchantTest` is new and green,
and it asserts three things — a level-short request is refused *with the numbers*, a funded one puts an
enchantment on the sword, and the table's charge lands in both the level and the lapis.

**Not verified:** only the cheapest option (`low`) has been pressed. The middle and high slots go
through the same call but no run has bought one, and the table in these tests has no bookshelves, so
the higher tiers were never actually offered. Enchanting an item that already has enchantments, and the
anvil, are both still ahead.
