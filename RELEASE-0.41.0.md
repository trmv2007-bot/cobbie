# Cobbie 0.41.0 — animals, and a retry the reasoner can actually use

Artifact: `cobbie-0.41.0.jar`. **179 unit tests green** (185 counting the 6 opt-in live-provider
checks), `CobbieBreedTest` green, and the verb-choice harness at **9 of 9**.

Two things in one release because the second was found by the first.

## Breeding: what the probe settled

A probe ran before any code, and it changed the design:

```
[probe] feed: first=Success second=Success oneInLove=true twoInLove=true canMate=true
[probe] loveCause first=FakePlayer [[Cobbie]]
[probe] after ~12s: cows=2 calves=0
[probe] wolf tamed=true
```

Feeding through the actor **does** put both animals in love and `canMate` agrees — but **no calf
appeared**. The pairing is normally completed by the animal's own breeding goal, and that goal does not
run for two animals a fake player has fed. What *is* public is
`Animal.spawnChildFromBreeding(ServerLevel, Animal)`, which is the game's own code for the calf: the
offspring, both parents' cooldowns and the sounds. So the verb feeds, checks, and then asks the game to
do the part it owns.

```
you: breed the cows
Cobbie: fed a pair of cow and a young one was born
```

Every refusal names the condition rather than the outcome: *"I have nothing here that a cow would
eat"*, *"I need two grown cow close together and I can see 1"*. A third refusal is deliberately
**observed rather than diagnosed** — after a calf, the parents still answer `canFallInLove()` true and
simply decline the food, so the message says *"would not take the wheat — it may still be recovering
from the last young"* instead of claiming a cooldown the run does not support.

## The token budget, in code

0.40.0 noted that `"cobbie till this dirt and plant some wheat"` produced no reply at all:
`no text back after 801 reasoning tokens on a 800 token budget`. That is a reasoner spending the whole
ceiling thinking, and a default sized for prose is a default sized wrong. Two changes:

**The shipped default is 1200.** (`max_tokens` in the config; his live file is already at 1200.)

**The existing retry now has somewhere to go.** A budget-starved empty reply used to be marked
not-retryable, on the reasoning that repeating the same request at the same size would spend it the
same way. That is true of the *same* request and false of a bigger one — so it is now marked
`budgetExhausted`, and the retry loop **doubles the ceiling once, capped at 2400**, logging what it
did. If the retry answers, the turn succeeds and the log says the budget moved; if it does not, the
refusal stands and names the number, as before. This is the retry we already had, given a chance to
succeed rather than removed to hide the problem.

## Verification

**Unit (`./gradlew cleanTest test`):** 179 green (one new), 6 skipped. Jar builds as
`cobbie-0.41.0.jar`. The new unit test proves the retry rather than the flag: a scripted starved reply
followed by an answer, asserting the **second request's own body** carries a bigger `max_tokens` and
never more than 2400.

**In game, cache-free (`--no-build-cache --rerun-tasks`):**

```
[gametest] breed ok: two cows and two wheat produced a calf and spent both wheat; no food and a
           single adult each refused with their own reason, and a second attempt made no second calf
```

The assertions are the world: one more calf than before, exactly two wheat gone, and a second attempt
that produces no second calf.

**And the model picks it.** Nine asks in one session, `pooled-deepseek` pinned:

```
[gametest] VerbChoice ok  "cobbie breed the cows" wanted breed
             -> breed(cow) -> completed — fed a pair of cow and a young one was born
[gametest] VerbChoice summary: 9 right, 0 wrong of 9 measurable
```

That run also caught a fixture fault in my own harness — the cows wandered out of perception between
asks, exactly as the sheep had, and the model was right to say it could see no cows. They are pinned
with `setNoAi(true)` now, the same fix, for the same reason.

## Not verified

- **Taming** through the actor works in the probe (`wolf tamed=true`) but has no verb and no test; the
  wolf's owner resolves to the fake player, which is a real question about whose wolf it would be.
- **Sows and breeding beyond cows.** The verb is generic over `Animal` and the food lookup uses each
  animal's own `isFood`, but only cows have been bred.
- **Tilling and sowing through a plan.** The probe proved `use` + hoe tills dirt, and the harness shows
  the model choosing `use` with a hoe, but in that run its chosen position had nothing to till:
  `use [failed] — nothing happened when I used minecraft:iron_hoe on that`. The capability is real; the
  model's choice of *where* was not, and no run has watched a till-then-sow plan complete.
