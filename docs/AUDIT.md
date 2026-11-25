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
