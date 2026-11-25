# Audit — the four bug families, 0.52.0

Nine defects this week were found by luck or by a probe, never by looking. This is the pass that goes
looking, family by family, with the scanner in `tools/audit-bug-families.py` as the reading aid and this
file as the record of judgment.

Method: run the scanner, read every hit, decide, and fix only real defects. The scanner is deliberately
noisy — a template is not a bug — so its false positives are listed here with the reason.

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
