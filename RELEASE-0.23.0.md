# Cobbie 0.23.0 — mending and dyeing, the recipes that read the grid

Artifact: `cobbie-0.23.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), `CobbieCraftTest` green in game with all five stages in one run.

## Two specials, decided by the game

Ordinary crafting is planned ahead: the planner knows what a recipe costs and what it makes before
anything is spent. Repair and dye are the opposite — what comes out depends on the two items that go
in, so planning the result would mean inventing it. They are therefore their own verbs, each of which
builds a real two-item grid and lets the recipe manager decide:

```
you: mend my pickaxes
Cobbie: mended two minecraft:iron_pickaxe into one

you: dye my boots red
Cobbie: dyed minecraft:leather_boots with minecraft:red_dye
```

- **`repair`** takes two damaged stacks of the same thing, hands them to the game as a
  `CraftingInput`, and lets `RepairItemRecipe` answer whether it is legal and what the mended item is —
  including the five-per-cent bonus a player gets.
- **`dye`** takes a colour and an item, resolves `minecraft:red_dye` at runtime, and lets `DyeRecipe`
  combine them. A pair the table would refuse is refused here too, with the reason.

Neither can report a success the crafting table would not have given, which is the whole reason they
are not a general search over grids.

## What is deliberately not supported, and why

Banner duplication, map extending, book cloning, firework assembly and shield decoration are also
grid-reading recipes and are **not** in this release. Each needs an item that carries state — a banner
pattern, a map scale, a written book — and he has no way to ask for "the banner with the creeper on it"
by name yet. Saying that plainly is better than a verb that works only when he happens to be holding
the right one.

## XP is decided: he earns his own

Rishi picked option A from 0.22.0's scoping, and this is the shape it will be built in:

1. **Orbs, from the game's own values** — a player's pickup delay and radius, `getXpValue()` rather
   than a table of my own.
2. **He keeps his levels on death.** His bag and armour already go to the owner rather than dropping;
   taking a level bar he earned away would be worse than a player's death, not better. This is a design
   decision, not an oversight.
3. **He never touches the owner's XP.** Not as a fallback, not when his own level is short. The refusal
   is "I need N levels for that and I have M".
4. **A level line in the panel**, because the panel is how a person sees his state without asking.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game:**

```
[gametest] craft ok: planks from logs, a pickaxe through the chain, a stair from one stone at the
           stonecutter, two pickaxes mended into one, boots dyed (logs 5 -> 4)
```

**Not verified, stated plainly:** the repair has only been tried on two iron pickaxes damaged equally,
and the dye only on red over undyed leather — combining an already-dyed item with a second colour, or
repairing something with a different damage split, goes through the same call but no run has done it.
The "not something the game will mend" and "the game will not dye that" refusals are untested in game
too; they are reached only by pairs the table rejects.
