# Cobbie 0.36.0 — the witness stops scoring a sheep that walked away

Artifact: `cobbie-0.36.0.jar`. **Test-only release:** one file, `CobbieVerbChoiceTest`, no production
class changed.

Two repeat runs of the verb-choice harness came back **3 right, 1 wrong**, and the wrong one was
mine, not the model's:

```
[gametest] VerbChoice world: … | entities=[] | blocks=[grass_block, dirt, brewing_stand, anvil, grindstone]
[gametest] VerbChoice MISMATCH "cobbie shear that sheep" — no action planned
           he said: "I don't see any sheep around me right now — just grass, an anvil, a brewing
           stand and a grindstone."
```

He was right. The fixture spawned the sheep 2.5 blocks away, and over the ninety seconds of a run it
wandered past the twelve-block perception range; the snapshot said `entities=[]` and the answer
matched the snapshot exactly. Scoring that as a model miss would have reported a failure the world
had actually caused.

## Two changes, both to the witness

**The prop no longer wanders.** The sheep is spawned with `setNoAi(true)` and
`setPersistenceRequired()`, so the precondition of the ask is stable for the whole run.

**And an ask whose precondition is absent is not scored.** If the mob an ask is about is not in that
turn's snapshot, the harness prints `not-measurable` and the summary counts it out of the
denominator:

```
[gametest] VerbChoice summary: 4 right, 0 wrong of 4 measurable
```

## The result, which is the reason to trust the number

With the fair witness, two runs in a row:

```
[gametest] VerbChoice summary: 4 right, 0 wrong of 4 measurable
[gametest] VerbChoice summary: 4 right, 0 wrong of 4 measurable
```

Across all five scored runs of this harness: 1 of 4 before the prompt rules, then 4, 3, 3, 4, 4 — and
the two 3s are the phantom sheep, not a regression. `grind` ran in every fair run, and the sheep ask
is now solved by the model's own chain rather than by luck:

```
cobbie shear that sheep -> walk-to(), equip(shears), use() -> "all done — 3 things sorted"
```

That also closes the finding from 0.35.0 — *"nothing happened when i used minecraft:potion on the
sheep"*. With the world offering the sheep, the model equips the shears itself before using them, so
the item-in-hand problem was the model solving a chain rather than failing to name an item.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.36.0.jar`.

**In game:** the harness green twice at 4 of 4 measurable against `pooled-deepseek`, with the
transcripts above.

**Not verified:** nothing beyond the harness. It changes what the witness counts, not what the mod
does. The one claim still standing from 0.35.0 is that rule 13 is what stops the "I cannot see it"
reply: it has not recurred in five runs, and one occurrence is not enough to attribute it.
