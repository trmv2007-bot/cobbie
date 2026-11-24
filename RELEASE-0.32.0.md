# Cobbie 0.32.0 — a harness that asks the model in plain words, and what it answered

Artifact: `cobbie-0.32.0.jar`. **Test-only release:** no production class changed.

The four slices before this one each proved their verb by driving it directly. That proves the verb
*exists*. It does not prove the model ever *picks* it, and a verb the brain never chooses does not
exist for the owner. `CobbieVerbChoiceTest` closes that gap: it types at him through the real chat
channel — "brew me a potion of healing", "put this enchanted book on my sword", "take the sharpness
off this axe", "shear that sheep" — and prints what he decided, with the world state he was given.

```
[gametest] VerbChoice world: bag=[minecraft:potion x1, …, minecraft:iron_axe x1, minecraft:shears x1]
           | entities=[minecraft:sheep@2.1] | blocks=[grass_block, dirt, brewing_stand, anvil, grindstone, …]
[gametest] VerbChoice ok      "cobbie brew me a potion of healing" wanted brew — brew(nether_wart) …
[gametest] VerbChoice MISMATCH "cobbie shear that sheep" wanted use — no action planned
                                he said: "Still no sheep anywhere I can see — the field around us is empty."
[gametest] VerbChoice summary: 1 right, 3 wrong of 4
```

## It reports; it does not redden

A class that is red against a live model on every run is the permanent-red problem the suite just
retired, so this one prints the score and the transcript and passes. The summary line states the
score plainly, so nobody can read it as a pass. `-Dcobbie.strictVerbChoice=1` turns it into a gate.
With no model configured it prints a skip line and returns.

## What it found

**`brew` was chosen** from plain words, once the stand was actually in sight — the biggest new verb
works end to end, not just when handed an intent.

**The anvil miss was the gateway, not the model.** One ask came back as
`dispatch->reply=30003ms` and *"the AI took too long to answer"* — the 30 s read timeout. Named here
because it is infrastructure and would look like a refusal in a count of four.

**The grind miss is perception, and the model was right.** The prompt says
`minecraft:iron_axe x1` — no enchantments — so "only my own plain iron axe, which has no Sharpness
to strip" is faithful to what he was told. `WorldPerception.inventoryIds` records an item's registry
id and count and nothing else, so an enchanted axe is indistinguishable from a plain one, and a water
bottle from a swiftness potion. That is a real defect and it is not in this programme's files.

**The sheep miss is the model contradicting its own prompt.** The snapshot given to it contained
`entities=[minecraft:sheep@2.1]`, `WorldSnapshot.describe()` renders that as an `entities` line, and
he said twice in two runs that the field was empty. Perception was right; the reply was not.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.32.0.jar`.

**In game:** the harness ran green three times against `pooled-deepseek` through his gateway, with
the four brain-gated classes also green in the same session (`4 passed, 0 failed of 4` for
`Status`, `Panel`, `Smoke`, `Chat` — those four are environment failures no longer). No production
behaviour is claimed by this release, so there is nothing else to verify.

**Not verified:** nothing, in the sense that this release changes no behaviour. Its own subject —
whether the model chooses verbs — is measured at 1 of 4 and reported, not fixed.
