# Cobbie 0.81.0 — he uses the bag well, and wears the cheap tool

Artifact: `cobbie-0.81.0.jar`.

Nine slices have taught him to *tell* the truth about a job. This one is about the bag: he can list it and
resolve "my worst pickaxe", but until now the question "which of these do I raise for this block" was answered
by a hand-written table that always chose **the best tool he owns**. Given stone, iron and diamond pickaxes he
wore the diamond one down on cobblestone — and obsidian with a wooden pickaxe he simply broke, reported
"nothing dropped", and kept going.

```
before:  stone pickaxe, iron pickaxe, diamond pickaxe, one block of stone
         -> hand: diamond_pickaxe, damage 1/1561
after:   -> hand: stone_pickaxe,   damage 1/131,   iron 0, diamond 0
```

## The rule, and where its two numbers come from

`ToolChoice` (pure, unit-tested) answers it, and both halves are the game's, not a list of names:

- **Will it harvest this block at all** is `ItemStack.isCorrectToolForDrops(state)`, which reads the block's own
  tags against the tool's own material. It is fail-closed, so a wooden pickaxe is never a candidate for
  obsidian, a pickaxe is never a candidate for dirt, and a shovel is never a candidate for stone.
- **How expensive it is to wear** is the material's own durability, `ItemStack.getMaxDamage()`, the only price
  the game publishes. It orders the tiers the way a player ranks them: wood 59, stone 131, copper 190, iron 250,
  diamond 1561, netherite 2031.

The pick is the **cheapest tool that can finish the job**, out of the ones that will harvest it. Nothing about
the block's *name* is consulted any more: `scores()`, `TIER_RANK` and `TOOL_WORDS` are deleted, and `isTool`
now asks the game whether the stack carries a `Tool` component.

## Never break a tool he was lent

A tool at one damage below its maximum is destroyed by its next use, so the number of blocks it can be trusted
with is `maxDamage - damage - 1`. The job's size is the mine steps still pending in the plan — one step is one
block — so a tunnel of forty blocks is not a job he starts with a pickaxe that has three uses left.

```
before:  diamond 1 use left + fresh iron, two blocks of stone -> hand: diamond_pickaxe (breaks on block 1)
after:   -> hand: iron_pickaxe (wear 2), diamond untouched, and he says why once:
         "using the iron pickaxe — the diamond pickaxe is nearly spent"
```

Three things follow, and each one is a sentence:

| case | what he does | what he says |
|---|---|---|
| a better tool is too worn for the job, a cheaper one can do it | raises the cheaper one | "using the iron pickaxe — the diamond one is nearly spent" |
| nothing he owns can finish it | raises the longest-lasting one anyway, because refusing to work is worse | "the stone pickaxe is the only one i have for this and it is nearly spent" |
| a tool gives out mid-job | — | "my stone pickaxe just gave out" |

The first is said **once per plan**, not once per block: a tunnel of forty blocks is one remark. The silence
was the defect — a companion whose pickaxe vanishes mid-tunnel without a word is one you stop lending tools to.

## Refuse rather than waste

`dropRefusal` and `breakRefusal` now ask the same `ToolChoice` question the swing does, so "no tool of a
sufficient tier" is a refusal that names what is missing — *"I need a proper tool for minecraft:obsidian and
have none"* — instead of a bare-handed hole with nothing in the bag. The two gates keep their deliberate
difference: an owner's *"dig through that stone"* still works bare-handed, because an empty-handed break that
yields nothing is the thing he asked for, while a *project* that promises to gather still refuses stone it
cannot harvest.

## Keeping, not hoarding

Nothing in the mod discards what he picks up: a full bag puts the drops on the ground beside him (verified in
`mineBlock`'s two paths) and there is no auto-discard anywhere in the entity. So there is no policy to enforce
here, and a *keep* switch is the owner's to set rather than a behaviour to invent — see the proposal in
`docs/AUDIT.md`. As of this slice he keeps.

## Evidence

`CobbieToolChoiceTest` (registry entry **68**, a survival world because a drop has to matter) reads the
inventory **after** the run, never the sentence:

| case | the assertion |
|---|---|
| stone + iron + diamond pickaxes, one block of stone | the hand holds `stone_pickaxe`; its damage is 1; iron and diamond read 0 |
| a diamond pickaxe with one use left and a fresh iron one, two blocks | the hand holds `iron_pickaxe` with damage 2; the diamond's damage is unchanged at `maxDamage - 1`; the remark was said **once** and names both tools |
| a wooden pickaxe and obsidian | the plan FAILS naming the missing tool, the obsidian is still standing, and the wooden pickaxe is unworn |

`ToolChoiceTest` (unit, 10 tests) settles the arithmetic: the cheapest sufficient tool wins; a tool that cannot
finish is passed over and named; stepping *up* because the cheap tool is short is silent; with nothing that
lasts, the longest-lasting is used and flagged; a non-harvesting tool is never chosen; a tie goes to more uses
left and then to the hand.

## Verified

- `JAVA_HOME=… ./gradlew cleanTest test` — 286 tests, 0 failed, 6 skipped (was 276).
- `./gradlew runClientGameTest -PcobbieTests=ToolChoice --no-build-cache --rerun-tasks` — 1 passed of 1:
  `hand stone_pickaxe, wear stone=1 iron=0 diamond=0`, then `hand iron_pickaxe, wear iron=2 diamond untouched,
  said "using the iron pickaxe — the diamond pickaxe is nearly spent"`, then the refusal.
- The whole suite is quoted in the pull request with the `CobbieGameTests` class count.
