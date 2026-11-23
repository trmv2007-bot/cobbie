# Cobbie 0.18.1 — he walks, swims, wanders and crafts

Artifact: `cobbie-0.18.1.jar`. **177 unit tests green** (183 counting the 6 opt-in live-provider
checks) on the rebased tree, and the in-game client classes green except the three that need a live
model — this machine's provider key was on cooldown throughout (see Verification).

This is the body half of 0.18, on top of 0.18.0's presentation work (the status tag, narration
cadence, the shift-right-click panel, the provider screen). Nothing here touches those: this is how
he moves, how he swims, how far he will go, and what he can do with what he is carrying.

The four things that were wrong in his legs and hands, in the order they were felt.

## "If I go too far he is not able to come to me"

Following used to be one `moveTo(you)` clamped at `navigation_range × 2`, so past about 48 blocks he
simply stopped being a companion who follows and became furniture. Worse, a pathfinder asked to plan
60 blocks of real terrain usually answers "no path" and gives up entirely.

Now he follows in steps. Further than 20 blocks away, the follow loop aims at a point 24 blocks along
the straight line to where you are, walks that, and re-aims every 12 ticks as you keep moving — one
reachable plan at a time, never one hopeless plan from where you were. Measured in game: you teleport
62 blocks away, he arrives at 5.9.

**He still does not teleport.** The test samples his position every 10 ticks for two seconds and fails
if any single step is over 8 blocks; the largest observed step was 2.26. If you want the old
"always there" guarantee at absurd distances, `recall_distance` exists as an explicit last resort —
off by default (`0`), and when it fires he says so out loud in chat rather than silently appearing.

## "He is super slow in water"

There was no water handling at all. A land mob in water has physics and no plan: the ground navigator
refuses to path while he is in water at all (`canUpdatePath` is false without `canFloat`), so he
bobbed, drifted and drowned his speed in drag.

Three changes, all vanilla levers rather than a private physics model:

- `setCanFloat(true)` on the navigator, so water is a place he can plan through instead of a wall
  the pathfinder refuses to cross. `WATER` costs 4 and `WATER_BORDER` 2, so a lake is crossable but
  land is still preferred when it is close.
- A swim assist on top of that path: horizontal thrust toward where he is going, capped at 0.30
  blocks per tick so he can never outrun a sprinting player, and buoyancy that holds him at the
  surface instead of walking the lakebed.
- Climbing out. This is the one the test caught rather than the spec: floating puts his feet a
  fraction *below* the bank, so he pressed against the grass and bobbed there forever. He now looks
  for a standable dry column next to him that is further toward his goal than he is, and hops into it.

Measured in game, four runs of the same 12-block crossing: 45, 57, 105 and 135 ticks (5.3, 4.2, 2.3
and 1.8 blocks/s), each ending on the far bank about 3.7 blocks from you. Walking speed is about 4.3 blocks/s,
so at his best he is crossing water at walking pace and at his worst he is still moving the whole
way. The spread is the navigator re-planning and bobbing at the entry edge, not his top speed — I
would rather report the slow run than quote the fast one.

## "His exploration isn't even going that far"

The old wander picked a random offset of `±min(navigation_range, 16)` — a shuffle around wherever he
happened to be, with no reason to be anywhere in particular and no way home.

Exploration now has a radius of its own (`exploration_radius`, default 48) measured from **you**: he
picks a target between half the radius and all of it, on ground he can stand on, and the plan carries
a second step back to where you were — so a wander always ends with him returning rather than with a
companion who walked off and stayed gone. Measured in game: at least 20.1 blocks out, back within
11.9.

One bug fell out of writing that test: exploration was aimed 26–48 blocks away but the *walk* limit
still refused anything past `navigation_range` (24), so he never moved at all. Walking his own wander
is now bounded by `exploration_radius`; `navigation_range` still bounds what he will walk when the
model or you ask him to go somewhere.

## "He is not able to craft"

There was no crafting code of any kind. There is now a `craft` verb that goes through the game's own
recipe table, behind `crafting_allowed` (default **off**, like mining, building and combat).

```
you: cobbie, make yourself a pickaxe
Cobbie: result - completed — make yourself a pickaxe: 1/1 steps done.
        Details: craft wooden_pickaxe [done] — crafted 1 minecraft:wooden_pickaxe
        (1x minecraft:oak_planks, then 1x minecraft:stick, then 1x minecraft:wooden_pickaxe)
```

One request, three recipes. The chain is resolved before anything is spent: the planner simulates the
bag forward, so a plan that would need a fifth plank from four logs fails at planning time with "I
have no recipe for oak_log and none in my bag" instead of half-consuming his wood.

- **Nothing is hardcoded.** Ingredient slots come from the recipe's own `PlacementInfo`, the result
  stack from `assemble` — components, counts and all. Special recipes (banners, maps, firework stars)
  are skipped, because they read the grid they are handed and he does not have one.
- **Several recipes can make the same thing.** Sticks come from planks and from bamboo; the planner
  tries each against a snapshot and rolls back when one cannot be satisfied. That was a real failure
  in this version's own testing: it found the bamboo recipe, could not make it, and reported "no
  recipe" while sitting on four planks.
- **Vanilla's `matches` is deliberately not used** for validation. A shapeless recipe with two of the
  same ingredient does not re-match against a sample grid, which is a false negative that left him
  unable to make sticks at all.
- It spends the ingredient list, not a grid layout — he is not carrying a crafting table, and the
  shape of a recipe is not information he acts on.
- A full bag puts what he made on the ground at his feet rather than deleting it, and a shortfall is
  said in chat and in his plan report, not swallowed.

## What he says while doing it

0.18.0 made him narrate his own work one short line at a time. That path now covers the verbs this
release added, and a craft step reads like the rest of them:

```
making a wooden pickaxe
made it
```

Rather than the executor's own summary, which is written for the model and says things like
`crafted 1 minecraft:oak_planks (1x minecraft:oak_planks, then 1x minecraft:stick, ...)`. Failures keep
their reason: `couldn't make that — my owner has not allowed that` is a real line from the test run,
and the tag above his head now says **swimming** in water and **crafting** while he works, in the
same body-first order 0.18.0 introduced. The swim test asserts the word, so it is not just a claim.

## Smaller things

- `/cobbie permit crafting on|off`, and the config screen has rows for Crafting, Exploration Radius
  and Recall Distance.
- `craft` joins the action vocabulary, the prompt's verb list, saved skills and the status tag.
- The recipe search logs one line when it cannot find a recipe for an item, distinguishing "the game
  has none" from "the recipe would not load" — the difference between two completely different bugs,
  and the line that located the sticks problem.

## Verification — what was observed, and what was not

**Unit (`./gradlew cleanTest test`):** 177 tests green, 6 skipped (the opt-in live-provider ones) —
the ten new ones in this release are the crafting planner's.
Ten are new, for the crafting planner, and they do not just check the plan's shape — they *run* it
against a simulated bag (`runsFrom`), so a planner that spends one log twice fails the test rather
than the player's world. Also covered: the shared-stock case, quantity → number of runs, a recipe
loop failing instead of hanging, a slot accepting several items, and one item with two recipes where
only the second is usable.

**In game (`./gradlew runClientGameTest`), flat world, this PC, on the rebased head:**

```
[gametest] reach ok: came 56.1 blocks (from 62.0 to 5.9), largest sampled step 2.26 blocks
[gametest] swim ok: 12 blocks of water in 105 ticks (2.29 blocks/s), reached the owner at 3.8 blocks
  (four crossings measured: 45, 57, 105 and 135 ticks)
[gametest] explore ok: reached 20.1 blocks from home, returned to 11.9 blocks
[gametest] craft ok: planks from logs, then pickaxe through the chain (logs 5 -> 4)
```

The four new classes plus `CobbieFollowTest` are **5 of 5 green** on the rebased head. The pool is
walled in the swim test — a narrow channel failed first because he simply walked around it, which is a
fair criticism of the test rather than of him.

**Three classes did not run, and that is not a code result.** `CobbieStatusTest`, `CobbieSmokeTest`
and `CobbieChatTest` all wait for a connected brain and reported `§cCobbie (LLM offline)` / "no API
key". This run directory has no key on disk and this machine's provider keys were on cooldown for the
whole session (the KeyPool gateway answered 401 without a key and the keys behind it were rate
limited). The suite has never been hermetic. Nothing in this release changes the brain, the chat
path or the status tag, but I did not observe them pass on 0.18.0 and I am not claiming otherwise.

**Not verified, worth saying plainly:**

- **Water was tested in a flat, still pool he had to swim.** Real oceans mean depth, currents at the
  surface, lava shores, and underwater terrain the pathfinder treats as blocked. The bank-climb only
  considers one-block-tall banks and diagonal neighbours; a two-block cliff out of water will still
  need a path around it.
- **Reach was tested in a straight line across flat ground.** A mountain between you and him is a
  pathfinding question the step-and-re-aim loop makes *possible*, not guaranteed; if it is genuinely
  walled off he will keep trying, and say nothing, until he is close enough to path directly.
- **`recall_distance` was not exercised in game.** It is off by default and unit-untested beyond
  compilation; turn it on only if you want the behaviour, and treat the announcement in chat as the
  contract.
- **Exploration's 20.1 blocks is a floor, not a maximum** — the test stops measuring the moment he
  crosses its threshold. With the default radius of 48 he can go further.
- **Crafting covers ordinary crafting recipes only.** No furnace, smoker, stonecutter, anvil or
  brewing stand, and no crafting table placement — he makes things from his own bag in mid-air, which
  is the same thing the recipe data allows and no more.

## Install

```bash
# with Minecraft closed
rm -f "$APPDATA/.minecraft/mods/cobbie-0.17.0.jar" "$APPDATA/.minecraft/mods/cobbie-0.18.0.jar"
cp build/libs/cobbie-0.18.1.jar "$APPDATA/.minecraft/mods/"
```

Then, in game: `/cobbie permit crafting on` (or Settings → Config → Permissions → Crafting) if you want
him to make things, and `/cobbie permit explore on` for the wandering. `navigation_range` still controls
how far he will walk when you or the model ask him to go somewhere; `exploration_radius` controls how
far he wanders on his own.
