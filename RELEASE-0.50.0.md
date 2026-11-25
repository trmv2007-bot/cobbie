# Cobbie 0.50.0 — he has a name, and it is his

Artifact: `cobbie-0.50.0.jar`. **189 unit tests green**, `CobbieNameTest` green in game, `CobbieMineTest`
still green.

"Cobbie" becomes a default rather than the identity.

## The name lives on him, and it is synced

The name is a `SynchedEntityData` slot, not a field and not a constant. Three reasons, and each one is a
way this ships broken:

- the nameplate and the panel are **drawn on the client**, so a name held only in server memory looks like
  a rename that worked on the server and failed on screen;
- it is **saved with his body** (`CobbieName` in NBT), so a reload does not forget it;
- it is **per entity**, so two companions may differ later without touching anything global.

His prompt, his panel header, his nameplate, his own chat lines and the spawn message all read from that
one slot. A nameplate that says one thing while he calls himself another is the bug this is built against.

## `/cobbie name <name>`, and `/spawncobbie [name]`

Both exist, and they are the same rule applied at different moments. The default stays **Cobbie** when
nothing is given: summoning has to keep working for someone who already has a companion in a world, and a
name you did not ask for is worse than the one you have — so a bare `/spawncobbie` is unchanged, an
optional argument names him at spawn, and `/cobbie name` renames him later. `/cobbie name` with no
argument tells you what he is called now.

## What a name may be, and why it is refused rather than cleaned

The name is rendered inside fixed formats — `§e<name> §8· §7<status>` on the plate, `§e[<name>] §f` on his
lines — so a name carrying its own formatting breaks both. **Nothing is silently altered:**

| Input | Answer |
|---|---|
| `` (empty), spaces only, `null` | *a name cannot be empty* |
| `Sir §cFluff`, `§c` | *no colour codes (§) in a name — it is already coloured* |
| 25 characters | *that name is 25 characters; the limit is 24* |
| a tab, a newline, any control character | *no control characters in a name* |
| `·`, ` · — ` | *that name is only punctuation, which reads as part of the nameplate* |
| `  Mallow  ` | `Mallow` — trimmed, which is the one change that is not a change of meaning |

Length is counted in **code points**, so an emoji or an accented letter is never cut in half.

## A name tag lands in the same place

A vanilla name tag runs through `setCustomName`, which would otherwise be a second storage for the same
idea: the plate changes, the panel and the prompt do not. `setCustomName` now routes anything that is not
our own plate text through the same validation and the same slot, so a tagged companion has one name.

That also produced the bug the reload assertion caught: vanilla reads the saved plate back through
`setCustomName` **before** the name field is populated, so the first version mistook our own plate for a
name tag and reverted him to the default on every load. The plate is now recognised by its own format and
rebuilt from the restored name once loading finishes.

## Renaming orphans nothing

Memory, skills and levels are keyed by owner, not by him, so the test asserts the owner id and his level
are unchanged across a rename — and that a second rename back leaves the same answer.

## Evidence

```
name ok: "Sir Fluff" on the nameplate ("§eSir Fluff §8· §7idle"), in the panel, on his chat line
         ("§e[Sir Fluff] §fhello there") and in his prompt; a colour code, a 25-character name and a
         blank were all refused; owner and level unchanged
name ok: "Sir Fluff" survived a save and reload — plate reads "§eSir Fluff §8· §7idle"
```

## Verification

- `cleanTest test`: **189 tests, 0 failed, 6 skipped** — eight of them new, one per rule above.
- `runClientGameTest -PcobbieTests=Name,Mine`: **2 passed of 2**, `--no-build-cache --rerun-tasks`.
- `/spawncobbie` was broken for one run by the optional argument (reading a missing Brigadier argument
  throws), which the name test caught immediately and which is fixed.
