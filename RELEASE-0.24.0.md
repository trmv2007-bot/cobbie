# Cobbie 0.24.0 — his own experience

Artifact: `cobbie-0.24.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), **21 of 25 in-game classes** — the four failures are the ones that need a live model, which
this run directory has no key for.

Experience is the resource every expensive interaction in the game asks for: the enchanting table, the
anvil, mending. A `PathfinderMob` has no level bar, so until now none of them could be his. Now he has
one, and it is his.

```
[gametest] experience ok: 30 points of orbs took him to level 2 with 4 progress, the owner's level
           untouched at 0, and the level survived a save
```

## Earned the way a player earns it

- **Orbs, with the game's own numbers.** He takes `ExperienceOrb.getValue()` from orbs within a block
  and a half that are older than ten ticks — a player's pickup delay in spirit — and levels along the
  game's own curve (`7 + 2n`, then `37 + 5(n-15)`, then `112 + 9(n-30)`).
- **He takes them himself.** The orb is discarded by him rather than touched through the fake-player
  actor, so no other mod sees a second hand in a pickup. The actor is for things the game insists a
  player must do; absorbing an orb is not one of them.
- **Not from you.** Nothing in this path reads or writes the owner's experience. The test asserts the
  owner's level and progress are untouched across an absorption, which is evidence rather than an
  intention.

## Kept, deliberately

**He keeps his levels when he dies.** His bag and armour already go to the owner instead of dropping,
and taking away a level bar he earned would be worse than a player's death, not better. This is a
design decision: the numbers are saved with the entity and nothing in `die()` touches them.

## Visible without asking

The panel's header now reads `LV 2 · 4/9` — level and progress toward the next, carried in the panel
payload so a person can see it without typing a command.

## What this unblocks

The enchanting table and the anvil are next, and both now have something to spend. The refusal will
always be the honest one — "I need N levels for that and I have M" — never a quiet withdrawal from
somewhere else.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game:** 21 of 25 classes. `StatusTest`, `PanelTest`, `SmokeTest` and `ChatTest` stop on
`§cCobbie (LLM offline)` because this run directory has no provider key. `CobbieExperienceTest` is
new and green.

**One bug the test caught, worth recording:** the first run absorbed nothing because the orb was going
to the *owner* — after a spawn he stands right next to Cobbie, and a player absorbs orbs faster than a
companion can. The test now moves the owner twelve blocks away before awarding orbs, which is also the
realistic case.

**Not verified:** orbs from real mining and combat — the test awards them directly — and anything that
spends a level, since nothing spends one yet.
