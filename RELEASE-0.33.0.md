# Cobbie 0.33.0 — the bag line says what the things are

Artifact: `cobbie-0.33.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), the four brain-gated classes green against a live model, and `CobbieVerbChoiceTest` green.

One method changed: `WorldPerception.inventoryIds`. No verb, no station, no prompt wording.

```
before: minecraft:potion x1, minecraft:potion x1, minecraft:potion x1, … minecraft:iron_axe x1
after:  minecraft:potion x1 (water), … minecraft:enchanted_book x1 (sharpness 1),
        minecraft:iron_axe x1 (sharpness 2)
```

## Why this was the blocker

The bag line recorded a registry id and a count and nothing else. So a water bottle was
indistinguishable from a swiftness potion, and an enchanted axe from a plain one — and the three
verbs that work by item name (`brew`, `grind`, `smith`) had nothing to read. Measured, not guessed:
asked to take the sharpness off an axe it was carrying, the model answered *"only my own plain iron
axe, which has no Sharpness to strip"*, which was exactly what the line told it.

## What the line carries now, and what it does not

Potion type from `POTION_CONTENTS`; the effective enchantments (three at most, then an ellipsis) from
`EnchantmentHelper.getEnchantmentsForCrafting`, which is `ENCHANTMENTS` on gear and
`STORED_ENCHANTMENTS` on a book — the pair an anvil or a grindstone acts on; a custom name cut to 24
characters; and wear, only once the item is damaged. Nothing else. Sixteen stacks is already a long
line, and a prompt that lists every component stops being read.

## The evidence, which is the same four prompts before and after

The grind case is the one the brief asked about, and it moved exactly as it should — from a false
statement to a different, honest objection:

```
before: "I can see the grindstone just a few steps away, but the axe is still in your hands …
         my own iron axe has no Sharpness on it."
after:  "I don't have your axe in my bag — only my own iron axe, and that one's not yours to strip.
         Drop the enchanted axe on the ground beside me and I'll pick it up, then take it to the
         grindstone and grind the Sharpness off."
```

It no longer denies the enchantment; it now knows the axe it carries *is* the enchanted one, and its
remaining objection is about ownership. That is a fair thing for it to think and a different problem
from this one — which is the test doing its job.

The anvil case moved the same way: before, *"the enchanted book"*; after, *"I've got a **Sharpness**
book and there's an anvil right beside us"* — it can now see which enchantment the book carries, and
still declines for the same ownership reason.

**The score did not move: still 1 of 4.** The one right is `brew`. That is the honest result: this
slice fixed what the model was *told*, and the remaining three misses are a different defect, which
is the next slice rather than something to fold in here. Tuning the prompt in the same change would
have made it impossible to say which fix did what.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.33.0.jar`.

**In game:** `CobbieVerbChoiceTest` green with the transcript above, and `Status`, `Panel`, `Smoke`
and `Chat` re-run green against the live model to confirm the prompt still works end to end with the
longer bag line.

**Not verified:** only the four prompts. A named item, a damaged item and a stack of three enchanted
books are all handled by the same method and none has been through a run; the wear field in
particular has never appeared in a transcript, because no ask in the harness carries a worn item.
