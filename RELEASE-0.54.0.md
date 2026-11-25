# Cobbie 0.54.0 — the three surfaces nobody had looked at

Artifact: `cobbie-0.54.0.jar`. **200 unit tests green** (10 new), `Decor`, `Spend` and `Capability` green in
game.

A quality pass on the parts a player touches most and no test covered. All three had "drawn" as their only
evidence; two of them turned out to be broken in ways only an extreme shows.

## 1. The panel with a full bag, and a name at the length limit

Two real defects, both invisible until the extreme:

- **The level line was drawn at a fixed x.** A twenty-four character name — exactly what
  `CompanionName.MAX_LENGTH` allows — ran straight through `LV 7 · 4/20`. The header now measures the name and
  puts everything after it, trimming the name to the space actually available.
- **A full bag could make the panel taller than the window.** `panelH` grew by a row per slot with no ceiling,
  so on a short window the panel's bottom edge and its own **Done** button went past the screen — leaving Esc
  as the only way out, with nothing saying so. Rows are now capped by the window, slots that do not fit are
  replaced by *"+N more"*, and the panel is clamped to `height - 12`.

The geometry moved into a pure `PanelLayout` so it can be checked as arithmetic: the unit tier now runs 45
slots and 8 steps against five window heights, asserts the panel always fits, that a bag too big for a short
window reports its hidden slots, and that the level line starts after the name ends.

**The arithmetic is tested; the drawing is not.** A gametest for the panel was written first and abandoned —
the client refuses to construct a screen off the render thread — so the rules were moved out of the drawing
instead of the test pretending to draw. Saying which half is verified is the whole point of the exercise.

## 2. A twenty-four character name on the nameplate

The header rule is covered by the arithmetic above. **What is still not verified is the plate itself as
drawn** — the name above his head in the world — because this machine has no screen capture, and I would
rather say that than describe a screenshot I do not have. The plate's format and its reload behaviour are
covered by `CobbieNameTest`; its readability at the length limit is an open question for a person to look at.

## 3. Narration cadence over a long session

Measured instead of listened to, over ten simulated minutes with a line offered every 150 ms — far faster
than the gap:

```
no two lines closer than the gap · never more than the gap allows · never quieter than it requires
a 500-line backlog collapses to the newest line, not a queue
```

And it found a defect: the throttle used **`0` as its "never spoken" sentinel**, so a call at timestamp zero
slipped past the gap until the next line set the clock. Harmless with a wall clock and wrong all the same —
the sentinel is now `Long.MIN_VALUE`, and the test starts its clock at zero, which is what exposed it.

The cadence the **model** produces is still unmeasured: narration needs a live brain, and the run directory has
none. What is proven here is the floor the narrator enforces on whatever arrives.

## 4. The red on master, which was mine

`CobbieRedstoneTest` went red after this pass: `use [failed] — I am not holding anything to use` on a lever.
Cause: the empty-hand path added for item frames in 0.51.0 left a guard that refused **every** empty-handed
block use, and a bare hand on a lever, button or door is a real gesture. The refusal now applies only when the
intent named an item he does not have.

```
summary: 6 passed, 0 failed of 6 — {Mine, Use, Redstone, RedstoneProbe, DecorProbe, Decor}
```

That is the third time this week a fix opened a hole beside it, and the pattern is consistent: the guard was
written for one caller and applied to all of them. The frame's take-out needed an empty hand; a lever always
did.

## Verification

- `cleanTest test`: **200 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Decor,Spend,Capability`: 4 passed of 4 (unchanged from 0.52.0, re-run after
  the screen changed).
- A gametest for the panel geometry was written first and abandoned: the client refuses to construct a screen
  off the render thread (`Rendersystem called from wrong thread`). The arithmetic moved out of the drawing
  rather than the test pretending to draw.
