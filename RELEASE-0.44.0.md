# Cobbie 0.44.0 — the redstone tail, and the two components that are not his

Artifact: `cobbie-0.44.0.jar`. **179 unit tests green**, `CobbieRedstoneTest` green, and the
verb-choice harness at **11 of 11**.

Last slice of phase 4, and it is a **verification** release rather than a feature one — the probe said
the fiddly tail needs no code.

## What the probe found

Eight components, one pass:

```
[probe] lever: CHANGED powered=false -> powered=true
[probe] button: CHANGED powered=false -> powered=true
[probe] repeater: CHANGED delay=1 -> delay=2
[probe] comparator: CHANGED
[probe] noteblock: CHANGED note=0 -> note=1
[probe] piston: unchanged result=Pass[]
[probe] iron_door: unchanged result=Pass[]
[probe] daylight: CHANGED
```

Everything a player can right-click already cycles through `ItemStack.useOn`, which the `use` verb has
had since 0.21.1: a lever, a button, a repeater's delay, a comparator's mode and a note block's note.
**No new verb, and no new code.**

Two do not change at all, and answer `Pass` instead of acting:

- **a piston** — it has no hand interaction; it responds to redstone;
- **an iron door** — likewise, it is opened by power, not by a click.

Driving redstone is an *actor* job rather than an interactor's, which is the case the brief said to name
and drop. They are dropped, and the test asserts they stay dropped: using one must report *"nothing
happened"*, never success.

(The daylight detector's `CHANGED` is its own sky-light reading moving while the probe ran, not an
interaction — it has none.)

## Verification

**Unit (`./gradlew cleanTest test`):** 179 green, 6 skipped. Jar builds as `cobbie-0.44.0.jar`.

**In game, cache-free (`--no-build-cache --rerun-tasks`):**

```
[gametest] redstone ok: lever powered, repeater delay 1 -> 2 and note block note 0 -> 1 all through
           the use verb; a piston and an iron door changed nothing and were reported as nothing
           happening rather than as success
```

Every claim is a block state read after the call: `POWERED`, `DELAY` and `NOTE` are the game's own
properties, not a return code.

**And the model picks it:** eleven asks in one session, `pooled-deepseek` pinned, **11 of 11** — brew,
anvil, grind, shear, weave, map, farm, till-and-plant, breed, trade and `"cobbie flip that lever"` →
`use() → completed`.

## Not verified, and one thing worth naming

- **Pistons and iron doors are not operated by him and will not be.** They need power, and powering
  redstone means acting on the graph rather than on a block. Named as a boundary, not a gap to close.
- Pressure plates were already incidental — he triggers them by standing on them, and no verb is needed
  or wanted.
- The lever ask's own report says *"used minecraft:potion"*, because `use` with no named item uses
  whatever he is holding while the block-use path flips the lever. The block is what changed and the
  test proves it; the wording of that report is a cosmetic weakness of `use`, not of this slice.
