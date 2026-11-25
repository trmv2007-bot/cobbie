# Cobbie 0.52.0 — the audit, and the banner it closed on the way

Artifact: `cobbie-0.52.0.jar`. **190 unit tests green**, `CobbieDecorTest`, `CobbieSpendTest` and
`CobbieCapabilityTest` green in game.

Two things: the patterned banner from the decorative tail, and a pass that goes **looking** for the four bug
families this project has shipped nine times rather than waiting to trip over them again.

## The banner, closed

```
decor ok: patterned banner placed with 1 layer(s) intact
```

A banner's colour is part of its item id and its patterns are an item component, so the ask has to name the
item he is holding (`minecraft:white_banner`, not `minecraft:banner`). Vanilla's own placement carries the
component onto the block entity, and the test asserts the **layer**, not that a banner exists.

## The audit

`tools/audit-bug-families.py` reads the main sources for the four shapes and prints `file:line` for each hit;
`docs/AUDIT.md` records the judgment on every one. The scanner is deliberately noisy — a template is not a
bug — so the document is the product and the script is the reading aid.

**No live defects in any of the four families.** B and C produced one and two hits, all judged correct: fuel
is a marker component the game itself reads, and *"nothing left to fight"* is a defence that ends a fight
whose target died. D's four hits are per-plan fields cleared with their plan, not caches; the family's real
case — the nameplate reverting on load — is already fixed and covered.

### What the audit produced instead of defects

**A false alarm I would have shipped as a fix.** `drop` and `give` looked exactly like the week's
read-after-consume bugs: they choose a stack from the bag and then spend by registry id. Writing the fix —
a `spendFrom(chosen)` method — was easy, and the test proving enchantments are conserved passed. Then the
**negative control** put the old call back, and it passed too, which is what exposed the misreading: both
loops break at the first match (`&& from.isEmpty()`), so `consumeItem`'s first-match spending already agrees
with the stack that was chosen. The fix was reverted rather than kept as unneeded robustness.

**An invariant, and proof it bites.** The agreement is load-bearing and invisible, so `CobbieSpendTest` pins
the consequence: a bag holding a plain sword and an enchanted one, after `drop` and after `give`, must still
hold exactly one enchanted sword **of any two swords, wherever they are**. Removing the `&& from.isEmpty()`
guard is a real mutation, and the test fails on it with the cause in the message:

```
After dropping one sword the world holds 2enchanted/2total (expected 1 enchanted of 2 total) —
the chosen stack and the spent stack disagreed
```

That is the difference between a check and a decoration: the first version of this test passed with the bug
in place and proved nothing.

**One gap named, not changed.** He has no dimension-change hook, so a plan running when he takes a portal
keeps coordinates from the old dimension. It fails step by step with *"that area is not loaded"* and abandons
with a reason — noisy rather than wrong — and travel, portals and movement belong to another thread in the
same file, so it is recorded in `docs/AUDIT.md` rather than edited here.

## Verification

- `cleanTest test`: **190 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Decor,Spend,Capability`: **4 passed of 4**, `--no-build-cache --rerun-tasks`.
- The conservation test was run three ways: with the code as shipped (green), with the fix reverted (green,
  which falsified the bug), and with the first-match guard **removed** (red, which proves the test bites).
