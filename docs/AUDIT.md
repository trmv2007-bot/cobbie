# Audit — the four bug families, 0.52.0

Nine defects this week were found by luck or by a probe, never by looking. This is the pass that goes
looking, family by family, with the scanner in `tools/audit-bug-families.py` as the reading aid and this
file as the record of judgment.

Method: run the scanner, read every hit, decide, and fix only real defects. The scanner is deliberately
noisy — a template is not a bug — so its false positives are listed here with the reason.

## One shape behind three of this week's defects

Three fixes this week opened a hole beside them, and the shape was the same every time: **a condition written
for one caller, applied to all of them.** A fourth instance, written by the person who made it: the item
resolver in 0.55.0 was first built as a description path *beside* the existing id scan, so the words "diamond
sword" spelled an id and the first of two swords was taken silently. The fix was one resolver for both verbs,
not a second path. The mining gate learned that a log drops to a bare hand and then
refused stone for a tunnel the owner asked for; the initiative negatives waited out a cooldown and became
vacuous rather than slow; the empty-hand path added so an item could come back out of a frame refused every
bare-handed block use, and a lever answered *"I am not holding anything to use"*. None of the three was
subtle once seen, and all three were found by a test or a probe rather than by reading. A reader who
recognises the shape — *who else calls this?* asked at the moment the condition is narrowed — saves the day
each of them cost.

### The same bug, twice, in a file written by the person who fixed it

`waitFor(tickCount % 20 == 0, ticks)` matches the **first** tick divisible by twenty and waits nothing. It was
found and fixed in 0.46.0, and then reintroduced in 0.60.0 — in the probe *about measuring things*, where every
phase reported the same number and the measurement was worthless.

It was fixed once in 0.46.0, reintroduced in 0.60.0's probe, and reintroduced again in 0.61.0's own harness — three
slices, two of them written *after* the fix, and each time the guard available at the time could not see it. **That
is the part worth keeping: a pattern survives its own fix when the shape stays easy to type.** A check that runs by
default is what eventually catches it, and making the shape unwritable is what stops it; neither replaces the other,
because the check has to exist before the unwritable version does.

The lesson is not that the helper was wrong. It is that **the wrong helper is still the obvious thing to type**:
`waitFor(predicate, timeout)` reads like "wait a while", compiles, and looks correct. `CobbieTestSupport.elapsed`
exists for exactly this and has to be remembered. Until the shape is unwritable — one way to wait, or a check that
fails the build on a tick-modulo predicate — it will be typed again by whoever writes the next probe.
`tools/audit-bug-families.py` now flags the shape in gametest sources for that reason.

## A fifth family: an allowlist that means "not a claim" when it misses

Four of this project's defects share a second shape, and it is worth naming separately because the fix is
different: **a rule written for the cases someone thought of, where a miss is silent and means "nothing happened".**

| Allowlist | What a miss looked like |
|---|---|
| the mining tool gate | a log was refused as if it were obsidian, so a project could not start |
| the empty-hand guard | every bare-handed block use refused, so a lever answered *"I am not holding anything to use"* |
| the item-description path beside the id scan | "diamond sword" spelled an id, so the first of two swords was taken |
| the promise verb list | *"Equipping my worst pickaxe — the worn one — now"* was **not a promise**, so no repair ran and the sentence was delivered as if the work had been done |

The last one is the dangerous version: a claim the list does not know is not merely unhandled, it is *reported as
true*. The fix that worked was to invert the default — **anything that looks like a claim gets one bounded repair
call**, rather than only the claims on the list. Where an allowlist cannot be avoided, the miss has to be loud: a
refusal with a reason, or a check that fails, never silence.

The refusal test is as load-bearing as the positive ones: *"I have no hoe"* is a report, not a claim, and must not
trigger a repair. A fix that turns every sentence into a model call would be a worse defect than the one it fixed.

## A test that fails for an environmental reason can hide a real defect underneath

`CobbieStatusTest` and `CobbiePanelTest` were counted as "brain-gated" for a week: they failed with *"the nameplate
never said idle; it reads `Cobbie (LLM offline)`"*, and that reads like a missing model. It was not. The nameplate was
a **frozen constant** — nothing rebuilt it — so it read `Cobbie (LLM offline)` whether or not a brain existed. The
test was right to refuse to call that idle, and the reason had nothing to do with the environment.

The naming slice made the plate rebuild from the companion's name and status every ten ticks, and both classes went
green **with no brain at all** on the next full run. Part of a failure count reported as "environment, not us" was
wrong, and it was wrong in the direction that flatters the author.

**The test that tells them apart: would the failure message change if the environment were fixed?** If the message is
a constant — `offline`, a fixed nameplate, a hard-coded state — the test is not measuring the environment, it is
measuring the constant. A genuine environment failure says something the environment produced: a connection state, a
timeout, a classpath error. Naming a class "brain-gated" is a claim that needs that question answered, not a label to
put on a red.

### The same name meaning two things: `isCreative()`

The fourth instance of the pattern, and the clearest one, because nothing looks wrong at the call site:

```java
boolean slowByHand = hardness > 1.5f && !CobbieConfig.isCreative();
```

`isCreative()` returns `autonomyMode == CREATIVE` — a companion setting. The question the line is asking is whether the
**world** lets a bare hand break a block, which is the *owner's game mode*. So in a creative world with **ASSISTIVE**
autonomy, he refused to break an oak log by hand: *"I need a proper tool for minecraft:oak_log and have none"*. Two
different questions, one method name, and the compiler delighted with both.

The fix is two predicates with honest names — `breakRefusal` asks `worldIsCreative()` (the owner's mode), and
`dropRefusal` asks only the drop question, `requiresCorrectToolForDrops()`, because "too slow by hand" was never about
the drop.

**The lesson to carry: a predicate's name is a claim about what it answers.** When one name serves two subsystems, at
least one of them is asking the wrong question, and the only way to notice is to read what the method returns rather
than what it is called. This one survived four slices of tests because every world those tests used had creative
*autonomy* — the tests were built in the mode that made the wrong answer right.

## Summary

| Family | Hits | Real defects | Verdict |
|---|---|---|---|
| A read-after-consume | 24 | 0 | one candidate judged a **false alarm**, the invariant now pinned by a test |
| B existence-not-content | 2 | 0 | both are the game's own rule — fuel is a marker component |
| C silent success | 1 | 0 | the message is a defence, not a measurement: a fight that ended |
| D stale state | 4 | 0 | all four are per-plan fields cleared with the plan, not caches |

**No live defects in the four families.** That is the finding, and the two things worth keeping are below:
one candidate that looked exactly like the week's bugs and was not, and the invariants that now fail if the
code drifts toward them.

## A — read-after-consume

The shipped shape: a stack leaves the bag and is read afterwards for its id, count or components. Two
candidates were read closely.

**`drop` and `give` pick one stack and spend another?** No. Both loops break at the first match —
`for (… && from.isEmpty(); …)` — and `consumeItem` spends from the first matching slot, so the stack that
was chosen and the stack that is spent are the same one. The invariant is load-bearing and invisible: drop
the `&& from.isEmpty()` and the two agree no longer. `CobbieSpendTest` pins the consequence —
**enchantments are conserved across `drop` and `give`** — and it was proved to bite by removing the guard and
watching the count go to two enchanted swords.

**A correction worth recording:** this was written up as a real defect first, with a `spendFrom(chosen)`
method to fix it. The negative control — putting the old call back and running the test — passed, which is
what exposed the misread. The fix was reverted rather than kept as unneeded robustness. A test that cannot
fail is worse than no test, and a fix for a bug that does not exist is the same mistake in the other
direction.

Everything else in the family is a template, not a defect:

- `leftover.isEmpty()` after `ItemStack leftover = result.copy()` — the copy is taken *before* the slot is
  cleared.
- `stack.isEmpty()` inside inventory compaction loops, and `dropped += stack.getCount()` where the stack was
  never emptied.
- `taken`/`made`/`out` in `FakeActor`: each is copied out of the menu *before* the menu slot is cleared, which
  is the opposite of the bug.

## B — existence instead of content

Two hits, both in the station layer and both correct: `Containers.isFuel` and `Brewing`'s fuel check ask
whether `COOKING_FUEL` / `BREWING_FUEL` is present, which is exactly what the game itself reads to decide
whether something burns. Asking for "content" here would be inventing a second rule beside the game's.

## C — silent success

One hit: `attack` returning success with a constant message, *"nothing left to fight"*. Judged **not a
defect**: the target died or vanished during the fight, so the job is over and success is the true report.
The family this check exists for is the empty right-click on an item frame — an interaction that reported
success while the world did not change — and that path now reads its success off the frame's own contents.

`use` on a block already reads back both the block state and the held item before claiming anything
(`blockChanged`, `handChanged`), so the family's model case is covered where it lives.

## D — stale state

Four hits, all in `CobbieEntity`: `active`, `activeTarget`, `workTarget`, `planOrigin`. All are per-plan
fields, cleared with the plan and rebuilt on the next one, and none is a cache that a rename or reload
should invalidate. The family's real case this week was the nameplate reverting to `Cobbie` on load; that is
fixed and covered by `CobbieNameTest`'s reload assertion.

One honest gap that is **owned elsewhere**: he has no dimension-change hook at all, so a plan running when he
walks through a portal keeps coordinates from the old dimension. It fails step by step with *"that area is not
loaded"* and abandons with a reason rather than silently, so it is noisy rather than wrong — and portals,
travel and movement belong to another thread in this file, so it is named here rather than changed.

## Checks that now exist because of this audit

- `CobbieSpendTest` — enchantments conserved across `drop` and `give`, proved to fail when the first-match
  guard is removed.
- `CobbieDecorTest` — a verb's success read off the world (sign text, frame contents, banner layers) rather
  than off an interaction result.
- `tools/audit-bug-families.py` — the scanner, kept because re-running it before a slice is cheaper than
  rediscovering a family by shipping it again.

# The endings inventory — 0.79.0

Nine slices have each fixed one verb's honest ending, and every one of them was found by a probe or a run.
Nothing had asked the whole question at once: **for every verb he can execute, what exactly does he claim,
and what is that claim checked against?** Two defects were sitting in the answer, and both were of the shape
this file keeps rediscovering — a success claimed off an intermediate answer rather than off the world.

The count, which is the spine of this section:

| | verbs |
|---|---|
| registered in `ActionVocabulary` | **36** |
| that make no claim about the world (`answer`, `chat`, `wave`, `follow`, `stay`, `explore`, `wait`, `lookup`) | 8 |
| that change or read the world | **28** |
| whose success is read back **off the world** after the change | **17** |
| whose success is taken from **a call's own return value** and never re-read | **11** |

Seventeen and eleven, not twenty-eight and zero. The eleven are not dishonest — the game's own transaction
answering "yes" is real evidence — but they are a *different grade* of evidence, and the difference is what
decides which verb is the next false success. A station's menu returning a result is a fact about that menu;
the bag afterwards is a fact about him.

## The table

`done means` is the objective in the owner's words. `claimed from` is what the code actually reads before it
says OK. **W** = re-read off the world after the change; **C** = a returned value, never re-observed;
**N** = no claim about the world at all.

**The C column is history as of 0.80.0.** Every verb in it now reads the world before it claims success and
the bar is enforced by the executor rather than kept by hand — see "The contract that replaced the
convention" at the end of this file, which lists which read each one gained.

| verb | done means | claimed from | |
|---|---|---|---|
| `walk-to` | he is standing at the square | `blockPosition().closerThan(target)` | W |
| `mine` | the block is air and the drop is his | block state re-read, `destroyBlock` accepted | W |
| `place` | the block is there and one left his bag | `setBlock` returned true **and** state re-read | W |
| `light` | a torch is at the square | same path as `place` (the verb is rewritten to it) | W |
| `write` | the sign holds his words | the sign's own text re-read after setting it | W |
| `take` | what moved is in his bag | bag grew by exactly what left the container | W |
| `put` | what left his bag is in the container | bag shrank by exactly what moved | W |
| `drop` | the stack is on the ground, not in the bag | bag count delta **and** `addFreshEntity` | W |
| `equip` | the thing is in the equipment slot | slot re-read, and put back if it is not | W |
| `craft` | the bag gained what the chain said | bag delta versus the recipes' own expected count | W |
| `smelt` | the bag gained what the furnace made | same `finishWork` accounting as `craft` | W |
| `use` | the block or the held item changed | both re-read, compared with before | W |
| `collect` | the pile is in his bag | per-stack `add` outcome, the world stack shrunk to match | W |
| `attack` | the target is dead | `target.isAlive()` after the swing | W |
| `farm` | N crops left the world | `destroyBlock` returned true, counted (fixed in 0.79.0) | W |
| `breed` | a young one exists | calf count before versus after | W |
| `board` | he is one of its passengers | `craft.getPassengers().contains(this)` | W |
| `give` | the stack is in the owner's inventory | `owner.getInventory().add(gift)` returned true | C |
| `repair` | two became one | the grid's own result, computed before the inputs are spent | C |
| `dye` | the thing took the colour | the dye grid's own result | C |
| `enchant` | it is enchanted and he was charged | the menu's `EnchantOutcome` (level after, progress, remaining) | C |
| `anvil` | the book went on | the result slot after a real click on it | C |
| `brew` | N potions came out | `Containers.extract` returned N | C |
| `smith` | the upgrade happened | the smithing table's result slot | C |
| `grind` | the enchantments are off | the grindstone's result, checked to be unenchanted | C |
| `weave` | the banner carries the pattern | the loom's result, checked to be patterned | C |
| `map` | the map was worked | the cartography table's result slot | C |
| `trade` | N results were bought | the trade loop counted its own results | C |
| `chat` `answer` `wave` `follow` `stay` `explore` `wait` `lookup` | he spoke / waved / is following / stayed / is wandering / is waiting / the brain answered | the flag or the line he just emitted | N |

## The two defects the table found

Both were the same mistake at a different distance from the world: **a successful intermediate answer
promoted to the answer.**

**`write` reported success having written nothing.** `startWrite` walks him into range when the sign is
further than his arm, and the hand-over (`performNow`) had no case for the verb — so the walked write fell
through to a `default` arm that answered `ok("write", "arrived")`. Arriving is where a verb *starts*. The
plan completed, `Narrator` said "all done", and the sign was blank. The fix is a `WRITE` case that re-reads
the sign and writes it, and a `default` arm that **fails** — `"i got there but do not know how to write"` —
because a verb that walks to its work with no arm here is a verb whose work nobody wrote, and the old default
turned that gap into a success.

**`farm` counted a crop it had not removed.** `harvested++` sat directly after `destroyBlock(...)`, whose
boolean was discarded: a world that refused the break still incremented the count, so the report said
"harvested 2" over two crops still standing. It now continues on a false return, and counts a replant only
when the young crop is really in the world.

A third, smaller one came out of asking the same question of the *closing* line rather than of a verb: **a
failed job's last word was "partly done — 1 I couldn't manage"**, with no reason in it. The reason existed —
the narrator had it — but the narrator is paced and drops anything offered inside its cooldown, and a retried
step offers two failures a tick apart, so the second is always dropped. The line that is guaranteed to be
heard now carries the reason (`Narrator.jobLine`), scrubbed of coordinates like every other spoken line. The
endings test is what found it: eight refusals in a row, and only the first one's reason survived to the chat.

## The guarantee is still a convention, and that is the next slice

`TaskRunner.settle()` marks any outcome whose `success()` is true as DONE (`TaskRunner.java:509`). "The world
agrees" is therefore a property each handler keeps by hand, not one the executor enforces — which is why two
of the defects above were possible: `write` answered `ok("write", "arrived")` and `farm` answered `ok("farm",
"harvested 2")`, and nothing in the runner could tell either from a real completion.

Making it structural means an `ActionOutcome` that cannot be built without saying what its claim rests on,
which is a change at every one of its 254 construction sites plus a per-family answer to "what counts as
evidence here" — a slice of its own, not an appendix to this one. Doing it halfway would give two definitions
of success, which is the shape this file spends its first section warning about.

## Bounds: one place, one number, and the reset that was not one

Every retry in this mod is a count. `TaskRunner` bounds a step at `maxRetriesPerStep` (+1 attempt), a place
at one re-plan (`TaskStep.replanned`), a plan at `planStepLimit` steps and a wall-clock deadline, and its own
tick loop at `steps × (retries + 2) + 8`. A walk is bounded at three re-hands to the navigator.

The exception was the climb assist: when the navigator came back empty with a climbable beside him and the
goal above, it **reset `walkRepaths` to zero**, so the same three tries were handed out again, for ever —
bounded only by the 60-second step deadline. A bound that resets itself when it is used is not a bound, and
from the outside it looks exactly like one. It now has its own budget (`MAX_CLIMB_ASSISTS`), and the failure
line says when the climb is what it could not do.

Every one of those numbers is now asserted rather than described:

- `BoundsTest` (unit, 7 tests): a forever-failing step is handed to the world exactly `budget + 1` times; a
  budget of zero is still one attempt; three failing steps get 3 × (2 + 1) and no more; **a re-plan does not
  hand the step a fresh retry budget**; a step the world calls broken is never handed over at all; a negative
  budget clamps to none; a step the world never answers ends at the deadline with the step interrupted.
- `CobbieEndingsTest` asserts the same count where it is observable in a real world: a `place` with no
  material in his bag is tried **exactly twice** against a budget of one, and the square is still empty.

## Induced failure, per verb

`CobbieEndingsTest` (registry entry 66) drives one deliberate failure per verb and accepts exactly three
endings: **it succeeded anyway** (checked against the world, never against the report), **it recovered once
and succeeded** (proven in `CobbieStalePlanTest` case 3, not duplicated here), or **it stopped and named what
stopped it** — the reason in the plan record *and* in what he actually said. The fourth ending, success over
work the world does not have, throws.

| group | verbs | induced failure |
|---|---|---|
| the station he has not got | `enchant` `anvil` `smith` `grind` `weave` `map` `brew` `smelt` | everything switched on, nothing built |
| the item he is not carrying | `drop` `give` `equip` `dye` `repair` `use` | one stick in the bag, nothing else |
| the thing that is not there | `take` `put` `board` `trade` `breed` `collect` `farm` `attack` | an empty world: no chest, no boat, no villager, no animal, no pile, no crop, no mob |
| the instruction no recipe can satisfy | `craft` | wool and no planks, no logs |
| an objective that already holds | `mine` | the square is already air |
| the write | `write` | a sign eight blocks away; then the same sign broken mid-walk |
| the count | `place` | every attempt fails the same way |

That is **26 of the 28 world verbs driven in this class**. The remaining two are named rather than omitted:
`walk-to` is driven by `CobbieStalePlanTest` case 1 (destination filled in before the step), and `light` is
the `place` path with the block rewritten to a torch, driven by that same class's case 3. The eight verbs
that make no world claim are not applicable: there is no world fact for them to disagree with.

**What cannot be driven in the harness, and why.** Two induced failures exist only in the code:

- the `farm` false-return path — `destroyBlock` answering false needs a world that refuses the break, and a
  vanilla flat test world has no protection system, so the fix is covered by reading it and by the reported
  count matching the world, not by inducing the refusal;
- the climb-assist bound — it needs a navigator to fail repeatedly beside a climbable with the goal above,
  which a flat test world does not produce. `BoundsTest` covers the same rule where it can be observed (the
  runner's retry budget); the entity's own `MAX_CLIMB_ASSISTS` is a count in the source and is asserted by
  nothing that runs. That is a gap, and it is written down here instead of being left to look tested.

## A second opinion's five findings, verified against the code

A model read the public repository at 0.78.0 and returned five ranked findings. Each was a claim to check,
not a work order. Two of them are the defects above; the other three are below, with what the code actually
said.

| # | finding | verdict | where |
|---|---|---|---|
| 1 | remote write false success | **real** — the defect at the top of this section | `performNow()` had no `WRITE` arm |
| 2 | farm false accounting | **real** — `harvested` incremented over a discarded boolean | `farmAround()` |
| 3 | exception cleanup leaves him unusable | **real** | `begin()` answers `"still busy with " + active.action()` while `active != null`, and a handler that throws never clears it |
| 4 | unverified output delivery loses items | **real, and narrower than stated** | ten `level().addFreshEntity(pile)` calls whose answer was discarded |
| 5 | weak item identity maps a description to the wrong item | **half right, wrong consequence** | `slotOf()` searched bag slots only and matched three of six identity fields |

**On 3.** `TaskRunner.tick` catches whatever `tickUnsafe` throws and fails the plan, so the *plan* always
ended. The entity's in-flight state was a different matter: `active`, `activeTarget`, `workTarget` and the
walk counters are cleared by `interrupt()` and by nothing else. A handler that threw inside `begin()` thus
left `active` set for ever, and every later instruction answered "still busy with <verb>" until a reload —
a companion that is present, alive, and cannot be asked to do anything. Fixed at both entry points: the verb
table is `dispatch()`, wrapped so a throw interrupts and reports
`something went wrong on my side (<class>)`; `driveActiveStep()` wraps `driveStep()` the same way.

**On 4.** The claim was item loss through an unverified store. Ten sites — repair, dye, enchant, anvil,
brew, smith, grind, weave, map, trade — tried his bag and then dropped what would not fit, discarding
whether the drop landed. A world that refused the entity meant the stack existed nowhere while the sentence
still said "mended two swords into one". `craft`'s own `storeOrDrop` already checked, which is why this was
ten sites and not eleven. One helper, `stored()`, answers "in his bag or on the ground", and a false answer
ends the verb as `lostIt()` rather than as a success.

**On 5, the correction.** The stated consequence — "he can equip or use a different item from the one
described" — is not what the code did. `slotOf()` searched `inventory` only, so a description naming the
thing already **in his hand** returned -1 and `equip`/`use` refused with *"I lost track of my sword"* about
the sword in his hand: a wrong sentence, not a wrong item. The real hazard is the other half of the same
method, and it is worth stating precisely because it is the stronger one: `slotOf` matched on **id, count
and damage**, so two diamond swords of the same count and damage answered to each other's description — the
one with Sharpness I could be taken for "my sharpest sword" when the other carried Sharpness V. Both halves
are fixed: the match is the whole six-field identity (`candidateOf(stack).equals(chosen)`), and the hand is
treated as a known place rather than an unknown one, in `equip` (already holding it is the answer) and in
`use` (it is the item to use).

# The contract that replaced the convention — 0.80.0

The endings inventory ended with the honest sentence this slice begins from: `TaskRunner.settle()` marked any
outcome whose `success()` was true as DONE, so "the world agrees" was a property every handler kept by hand.
Two of the three defects in that section were possible for exactly that reason — `write` answered
`ok("write", "arrived")` and `farm` answered `ok("farm", "harvested 2")`, and nothing in the runner could
tell either from a real completion.

That is now the executor's rule rather than each handler's habit.

## One type, one check, one table

`ActionOutcome` cannot be built as a success without `ActionEvidence`, and `ActionEvidence` has exactly two
kinds: `World` (something was read off the world after the change) and `OwnAct` (the verb claims nothing
about the world — he spoke, waved, waited). A station's menu answering yes, a boolean a method returned, and
"the step got where it was walking" are none of those; they are the shapes that produced four false successes
in one week.

`ActionVocabulary.provesWorld(verb)` is the endings inventory's last column turned into code, and it is
fail-closed: the eight verbs that claim nothing are named, and everything else — including a verb nobody has
classified, and a null — has to show the world. `settle()` applies it. A success below the bar becomes a
failure ("i said write was done with only "i got there" to go on, and write has to be read back off the
world"), takes the same retry budget as any other failure, and is reported rather than promoted.

Two fall-throughs that used to answer `ok(verb, "done")` and `ok(verb, "arrived")` are failures for the same
reason the `performNow` default already was: a verb whose finishing move nobody wrote is not finished.

## The inventory, re-counted against what the code now reads

| | verbs |
|---|---|
| registered in `ActionVocabulary` | 36 |
| claiming nothing about the world (`NONE`) | 8 |
| that change or read the world | 28 |
| whose success is read back off the world **before it is claimed** | **28** |

Seventeen and eleven was the count at 0.79.0. The eleven were not dishonest and the fix was not to relabel
them: it was to notice that 0.79.0 had already given ten of them a world read in `stored()` — "the thing I
made is in my bag or on the ground at my feet" — and that the remaining four paths had a read within reach.

| verb or path | what it reads now | was |
|---|---|---|
| `give` | the owner's inventory count before and after, not `add()`'s answer | C |
| `mine`, full-bag path | the square is air, on the path that used to trust `destroyBlock` | C |
| `collect` | the bag total grew by what came off the ground | C |
| `farm` | the squares that no longer hold the crop — the count is that read | W by the boolean |
| `attack`, target gone | the hunted entity by UUID: not alive, or not in the world | C |
| `use` on an entity | his hand after the use; an unchanged hand is a failure, not a success | C |
| `anvil`, `brew` | what he carries, and the stand's own contents, already read | C |
| ten station verbs | `stored()`'s location, now carried into the sentence | C |

`stored()` became `store()`, returning *where* a made thing went — bag, ground, or nowhere — because the
success message and the evidence sentence are both written from it. A boolean could say `true`; a location
can say "in my bag".

## What this contract can and cannot do

It makes the *shape* unwritable: a success with nothing observed does not compile, and a claim weaker than
the verb's bar does not settle. It cannot stop a handler from writing a world sentence it did not read —
nothing in a type system can — so the four defects are also pinned by tests that check the **world**, not the
report:

- `CobbieEvidenceTest` (registry entry 67): a walked write whose sign must read his words with the evidence
  being those words; a farm whose reported count must equal the squares that lost their crop; a repair whose
  mended pickaxe must be findable in his bag or on the ground *and* named as being there; an equip of
  "sharpness 5" among two swords that id, count and damage cannot tell apart, which must leave the enchanted
  one in his hand.
- `ActionContractTest` (unit, 9 tests): every verb classified with no gap; a success cannot be built without
  evidence; the four 0.79.0 defect shapes fed to the runner as they were — a claim with only its own act
  behind it — and refused with the retry budget every failure gets. Proved to bite by disabling the check in
  `settle()`, which turns two of them red.

One limit, named: the farm case cannot induce a refused break, because a vanilla flat world has no protection
system. The number is therefore derived from the squares the world reports empty rather than from
`destroyBlock`'s answer at all, and the test asserts the count against the field — but no run of this suite
can make a crop refuse to be cut.

# Inventory intelligence — 0.81.0

The bag has been legible since 0.55.0: he can list it, and he can resolve *"my worst pickaxe"* out of a
description. What was never asked is the question a player asks on every block — **which of these do I wear for
this one** — and the answer was a hand-written table in `CobbieEntity`:

```java
private static final List<String> TIER_RANK = List.of(
        "netherite", "diamond", "iron", "stone", "copper", "golden", "wooden", "wood", "leather");
// scores(): tier + 8 if the block's *name* looks stony and the tool's name contains "pickaxe"
```

Four defects lived in those fourteen lines. All four are the family this file keeps rediscovering: **a rule
written for the cases someone thought of, where the game already had the answer.**

| what the table answered | what actually happened |
|---|---|
| "higher tier is better" | stone, iron and diamond pickaxes on cobblestone: the diamond one wore. A companion who breaks your diamond pickaxe on cobblestone is one you stop lending tools to |
| "a stony *name* means a pickaxe helps" | stone-like fragments (`stone`, `ore`, `deepslate`, `cobble`, `granite`…) and nothing else. A pickaxe scored by its tier alone on **dirt**, so he dug dirt with a diamond pickaxe and wore it |
| the same, asked of the drop | obsidian is neither stony by name nor hard to break by that gate, so a **wooden** pickaxe was raised, the obsidian broke, `Block.getDrops` paid out nothing, and the report said "nothing dropped". The block was lost for nothing and the sentence was true |
| nothing at all | a pickaxe with one use left was as good as a fresh one. It broke mid-tunnel, silently |

## The rule, and why both numbers are the game's

`action/ToolChoice` answers it, and it is pure so the unit tier settles it:

- **sufficient** = `ItemStack.isCorrectToolForDrops(state)`. In 26.3 there is no `Tiered` or
  `TierSortingRegistry` to ask (checked in the deobfuscated jar, not assumed): a tool's material is baked into
  its `Tool` component as one rule — `blocks = #mineable/pickaxe`, `speed = <material speed>`,
  `correctForDrops = !<the material's incorrect_for_…_tool tag>.contains(block)`. `isCorrectForDrops` walks those
  rules and returns `false` when nothing matches, so it is fail-closed. That one call replaces the name table: a
  wooden pickaxe is not sufficient for obsidian, a pickaxe is not sufficient for dirt, a shovel is not
  sufficient for stone.
- **cost** = `ItemStack.getMaxDamage()`, the material's own durability — the only price the game publishes, and
  it orders the tiers the way a player ranks them (wood 59, stone 131, copper 190, iron 250, diamond 1561,
  netherite 2031). Gold at 32 is cheaper than wood and is spent first, which is also what a player does.

So the pick is *the cheapest tool that can do the job, out of the ones that will harvest it*, and `isTool` now
asks the game whether the stack carries a `Tool` component rather than scanning its name for "pickaxe".

## Wear is arithmetic, not a mood

Vanilla destroys a stack whose damage reaches its maximum, so a tool with `maxDamage - damage` uses left can be
trusted for `maxDamage - damage - 1` blocks. The job's size is the mine steps still pending in the plan — one
step is one block — which is why a tunnel of forty blocks does not begin with a pickaxe that has three uses
left. Three outcomes, each with its own sentence:

| the choice | what he says |
|---|---|
| a better tool is too worn, a cheaper one can do it | *using the iron pickaxe — the diamond pickaxe is nearly spent* |
| nothing he owns can finish it | *the stone pickaxe is the only one i have for this and it is nearly spent* |
| a tool gave out mid-job | *my stone pickaxe just gave out* |

The first is said **once per plan**, not once per block. The silence was the defect: a tool that vanishes in a
tunnel without a word is worse than a refusal. The second is a deliberate inversion of "never start a job you
cannot finish" — refusing to work at all with a nearly-spent tool in the bag is not honesty, it is idleness — so
he works and warns, and the warning is the guarantee.

## Both gates now ask the same question

`dropRefusal` and `breakRefusal` used the same table, so they now use the same `ToolChoice`, and the refusal
names what is missing (*"I need a proper tool for minecraft:obsidian and have none"*). The two gates keep their
deliberate difference from 0.79.0: an owner's *"dig through that stone"* may be a bare-handed break, because a
hole and no drop is the thing he asked for, while a project that promises to gather may not.

## Keeping, and the switch that is the owner's

Nothing in the mod discards what he picked up. A full bag drops the loot at his feet on both of `mineBlock`'s
paths, and there is no auto-discard anywhere in the entity — so *"should not throw away something the owner
might want"* is already true, and it is now covered by the same test that reads the inventory.

*"Should not fill his bag with forty stacks of dirt"* is the half with no behaviour to fix, because the only
thing that fills his bag is work he was asked to do. A policy would be a knob — `keep everything` versus `stop
picking up what I did not ask for` — and the honest default is **keeping**: a companion that silently drops the
cobblestone out of your tunnel is one you have to clean up after. Proposed, not implemented, and defaulted to
keeping: the owner sets it or nobody does.

## What this slice does not cover

- **The job estimate counts only blocks of the same kind.** A plan of ten stone and two oak logs whose steps
  name no target counts twelve for the pickaxe. Over-counting makes him reach for a *longer-lasting* tool, which
  is the safe direction; under-counting would break tools.
- **The farm path does not use this chooser.** `farmAround` has its own hoe handling and was out of scope; the
  chooser serves `mine`, where the wear is a block at a time.
- **"Right implement" is the game's answer for *drops*, not for speed.** A gold pickaxe is cheap and sufficient
  for stone and will be chosen over a wooden one; speed is not part of the price, by design.

## The files

| was | is |
|---|---|
| `scores(ItemStack, String blockId)` | `ToolChoice.choose(bag, blocksLeft)` |
| `TIER_RANK`, `TOOL_WORDS` | deleted; `isTool` asks for the `Tool` component |
| `bestToolFor(String)`, `equipBestToolFor(String)` | both take the `BlockState` and go through one `chooseTool` |
| `indexOf` | deleted (the chosen slot comes back from `ToolChoice`) |
| — | `noteToolChoice`, `blocksLeftInJob`, `toolCandidate`, `toolAt`, `toolWords` |

`CobbieToolChoiceTest` (registry entry **68**) asserts all of it off the inventory after the run, in a survival
world: `stone_pickaxe` with damage 1 while iron and diamond read 0; `iron_pickaxe` with damage 2 and the diamond
left at `maxDamage - 1`, with the remark said exactly once; and obsidian refused with the wooden pickaxe still
unworn. `ToolChoiceTest` (unit, 10 tests) settles the ordering arithmetic.

# Survival instincts — 0.82.0

Every slice before this one was about what he *claims*. This one is about the body making the claim: he has 20
health, and until now nothing in the mod made him look after it. Combat exists, and the pathfinder prices lava
and fire as though they were walls, but nothing stopped a plan from walking him into a fire, digging the block
he was standing on, or standing in melee at three health until he died.

Four behaviours, and one rule about where each of them lives:

| behaviour | where it is decided | what it reads off the world |
|---|---|---|
| get out of fire, lava or a cactus | the tick, beside the wall rescue | the block his **bounding box** overlaps |
| never dig the block under his feet when the drop is fatal | `mineBlock`, at the swing | the column below, and `calculateFallDamage` |
| break off when badly hurt | the tick, before the runner advances | `getHealth()`, and a hostile whose `getTarget()` is him |
| never path down a fall that costs more than he has | `getMaxFallDistance()` | the same damage arithmetic |

## The two numbers are the game's

`world/Falls` is pure, and both behaviours that turn on a drop ask it. The damage function is the game's own
`LivingEntity.calculateFallDamage` — fall distance, jump boost and the fall-damage-immune tag all inside it —
handed in as a function so the unit tier can pin the boundary with its own:

- **the drop under a break** is measured to the top of whatever he would land on, because that is where his feet
  stop; water ends it without damage; lava and the void need no arithmetic.
- **fatality** is `damage >= health`. A fall costing exactly his health is fatal: the game applies damage at zero
  health, and dead is dead.
- **the deepest safe drop** is the largest whole number whose damage is still under his health.

The last one is now what `getMaxFallDistance()` answers, and that is the defect worth recording. The game's own
answer is `getComfortableFallDistance(health)` = `floor(health + 3)`, which by the game's own arithmetic is a fall
costing **exactly his health**: at 20 health the pathfinder will cheerfully plan a 23-block drop worth 20 damage.
The comfort rule is off by one in the direction that kills, and every Mob in the game inherits it.

```
health 20:  deepest drop he will path down 22   (a 23-block drop costs 20 = his whole bar)
health  8:  deepest drop he will path down 10   (an 11-block drop costs 8)
```

## Where each refusal is, and why not somewhere else

- The **hazard escape** is on the tick and not in a verb: a body can be pushed into fire by a piston, a creeper,
  a teleport or the walk it was given, and the walk it was given is exactly the plan that must not be obeyed. The
  plan in flight is cancelled with a reason, so the sentence names both the hazard and the stopped work. It is
  asked of the **bounding box**: his body is 0.6 wide, so standing a block away from a cactus is genuinely not
  touching it, and the first version of this bug was a rule that teleported him away from a cactus he was never
  hurting on.
- The **dig refusal** is in `mineBlock`, the one place both the walked and the in-reach path end. `canStartMining`
  asks the same question, so a project is never announced that cannot take its first step.
- The **retreat** is a bounded state (`RETREAT_TICKS` = 160, forty ticks of cooldown after) rather than a verb,
  because what it interrupts is a step, not a plan. It is also a *setting*: `retreat_health_fraction`, default
  0.35, and `0` turns the instinct off. The melee goal and the defence goal both stand down while
  `isRetreating()`, which is the half that keeps a retreat from becoming a fight he turns back into.
- The **fall limit** is the pathfinder's own hook, so it applies to following, wandering and every walk a plan
  builds, not only to the verb someone remembered to guard.

## Evidence, per behaviour

| test | class | what it reads off the world |
|---|---|---|
| fire, lava, cactus | `CobbieHazardEscapeTest` | his bounding box no longer intersects the hazard block, he is alive at 12/20, and the line names the hazard |
| the fatal dig | `CobbieFatalDigTest` | the block is **still stone** over lava; at 20 health the same nine-block drop is dug and cost him six; at 4 health it is refused with the drop and his health in the reason |
| the fatal walk | `CobbieFatalDropTest` | the limit is 22 at 20 health and 10 at 8; a 23-block walk fails naming the drop and the price; he never left the top and never took the damage |
| disengage | `CobbieDisengageTest` | the same wound and zombie twice: switched off he loses health standing still (6 -> 3); at 0.5 he says he is falling back, keeps 9, and ends beside his owner |

## What could not be built honestly here, and what stands in for it

- **A cliff over lava** — the dig test builds the shaft (solid platform, six blocks of air, lava) rather than a
  natural cliff, because a flat harness world has no cliffs and building one by hand would be a sculpture of a
  cliff, not a cliff. The question the code asks is the same either way: what is in the column below.
- **The control run in `CobbieDisengageTest` needs the zombie re-pointed every tick.** A zombie's own targeting
  goal prefers players, and the owner is standing right there, so the engagement the test sets up is replaced a
  few ticks later and the "fight" becomes a zombie walking at a resident player. The re-point does not stage the
  damage — the walking and the swinging are the zombie's — but it is rigging of the scenario, and it is named
  here rather than hidden.
- **His health after a retreat cannot go *up*.** No mob regenerates in vanilla, so "higher after than before" is
  not a fact this world can produce. The assertion is the pair instead: with the instinct he finishes on the
  health he started the retreat with, and without it he does not — which is the same claim measured against the
  world that exists.
