# Cobbie 0.35.0 — two prompt rules, and a score that moved from 1 of 4 to 4 of 4

Artifact: `cobbie-0.35.0.jar`. **178 unit tests green**, the four brain-gated classes green against a
live model, `CobbieVerbChoiceTest` green at **4 right, 0 wrong of 4**.

Prompt wording only, in `CobbiePrompt`: two rules, and the harness now prints the world line for
every ask instead of once at setup.

## The two rules, and what they were written from

Neither is a guess; both come from transcripts.

**Rule 13** binds the model to its own senses: *WHAT YOU SEE NOW is the truth about the world. If a
mob is listed under entities, it is right there and you may act on it — never say you cannot see
something that is written in that block.* Written because one run answered *"Still no sheep anywhere
near me — the plains are empty of animals, just grass and my anvil"* while that turn's own prompt
said `entities=[minecraft:sheep@10.5]` — it quoted the **blocks** line back and skipped the
**entities** line.

**Rule 14** says the bag is shared for work: *Anything in your INVENTORY is yours to work on. When
the player says "my sword" or "this axe" and you are carrying one, that is the thing they mean: brew,
grind, smith, enchant or use it without asking whose it is.* Written because rule 9 — *never claim to
own something that is not in your INVENTORY* — had taught the model that the player's things and its
own are different sets, so it read "my axe" as the player's, saw an axe in its own bag, and declined
on ownership grounds. That was the old rules read correctly; the old rules were the defect.

## The measurement

Same four plain-language prompts, same fixture, same model.

| | score | what the misses were |
|---|---|---|
| perception fix only (0.33.0) | 1 right, 3 wrong | ownership refusals and the empty-field denial |
| with rules 13 and 14 | **4 right, 0 wrong** | — |

All four now plan the verb that does the job, and three of them then ran or were refused by the
*game* rather than by the model:

- `brew` — planned, and refused honestly downstream: *"i need blaze powder to heat the stand"*, the
  fixture's single powder having been spent by the previous batch.
- `anvil` — `walk-to(), anvil(iron_sword)`, then *"i need more levels for that than i have (0)"*,
  which is the anvil's own refusal through my 0.26.0 path.
- `grind` — `walk-to(), grind(iron_axe)` → **"all done — 2 things sorted"**. The enchantment came off.
- `use` — `walk-to(), use()` on the sheep: *"On it — walking over to that sheep with my shears."*

## What the evidence does **not** say

**Rule 13 is not proved to be the fix for the sheep.** The denial appeared in one run with
`entities=[minecraft:sheep@10.5]` in that turn's prompt, and the next run saw a sheep at 9.0 blocks
and acted on it. So the contradiction is intermittent, and this release cannot say whether rule 13
fixed it or whether the model simply did not repeat a mistake. It is named here rather than claimed.

**The remaining miss is the item, not the verb.** The last step was `use()` with no target, so it
raised whatever was in its hand — a potion — and reported, correctly, *"nothing happened when i used
minecraft:potion on the sheep"*. The verb was right; the model did not name the shears. That needs
either prompt guidance (name the item in `target` when using it on a mob) or a `use` that picks a
sensible item, and it is a slice of its own rather than a third rewording of the same sentence.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.35.0.jar`.

**In game:** `CobbieVerbChoiceTest` green at 4 of 4 with the transcripts above; `Status`, `Panel`,
`Smoke` and `Chat` green against the live model, so the two extra rules and the longer prompt did not
break the conversational path.

**Not verified:** the harness prints the score; it does not gate on it, deliberately. A second run of
the same four prompts is the way to find out whether 4 of 4 is stable or lucky — and with
`temperature=0.7` on `pooled-deepseek`, one run is a data point, not a guarantee.
