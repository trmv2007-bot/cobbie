# Cobbie 0.50.1 — a name is an identity, so it has an owner

Artifact: `cobbie-0.50.1.jar`. **190 unit tests green**, `CobbieNameTest` and `CobbieCapabilityTest` green
in game.

Three fixes from the review of 0.50.0, all in the same place: the name is not cosmetic, and every route to
it had to agree about that.

## Renaming is the owner's

`/cobbie name` had no permission check. In single-player that is the same person; on a LAN it is anyone on
the server, and it is exactly the kind of hole that becomes a real problem the moment multiplayer lands. A
rename changes **his** chat lines, **his** prompt and **his** memory, so it is `renameBy(actor, name)` now —
**owner only**, refused with *"only my owner can name me"*, checked in the same method the command calls so
the rule cannot be bypassed by another caller. An op is not a substitute: the companion belongs to one
player, and "ours" is a different thing from "mine".

## A name cannot break the brackets around it

His chat lines are `§e[<name>] §f`, so a name containing `[` or `]` produced a nested or truncated bracket
that reads as corruption. Refused, like `§`:

```
no square brackets in a name — they are the brackets around it
```

## The turn after a rename explains itself

His transcript holds lines in the old name, so for the next few turns the history says one name and the
system prompt another — the likely symptom being him denying words he plainly said. A rename now sets one
note, consumed by the next prompt:

```
NOTE: The owner has renamed you to Mallow; any earlier lines of yours under a different name are still yours.
```

It is deliberately **not saved**: it is about one turn, not the world. The test asserts it appears once and
is empty on the turn after.

## Evidence

```
name ok: "Sir Fluff" on the nameplate ("§eSir Fluff §8· §7idle"), in the panel, on his chat line,
         and in his prompt; a refused rename left the name untouched; owner and level unchanged
name ok: "Sir Fluff" survived a save and reload — plate reads "§eSir Fluff §8· §7idle"
```

The test also proves the refusal path: with his owner id pointed at somebody else's account, `renameBy` for
the real player is refused with the reason and **the name does not change**.

## Verification

- `cleanTest test`: **190 tests, 0 failed, 6 skipped** — one more, for brackets at both ends and mid-string.
- `runClientGameTest -PcobbieTests=Name,Capability`: **2 passed of 2**, `--no-build-cache --rerun-tasks`.
