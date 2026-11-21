# Cobbie 0.10.0 — no capability ceilings

Artifact: `cobbie-0.10.0.jar`. 93 unit tests, 11 in-game tests, green against the pooled endpoint.

Every artificial cap on what he can do has been lifted. The safety gates are untouched — see the
bottom of this file for what was deliberately kept and why.

## Lifted

| Limit | Was | Now |
|---|---|---|
| Steps in one plan | 8 (prompt silently capped at 5) | **24** |
| Actions per reply | 8 | **32** |
| Answer length | 900 chars | **4000** |
| Skills he can know | 32 | **256** |
| Steps per learned skill | 12 | **32** |
| Skill text fed to the prompt | 600 chars | **3000** |
| Blocks visible per snapshot | 10 | **40** |
| Entities visible per snapshot | 10 | **32** |
| Inventory lines in the snapshot | 8 | **54** |
| Snapshot total | 1200 chars | **6000** |
| Navigation range | 64 blocks | **256** |
| Interaction reach | 8 blocks | **16** |
| Follow distance | 32 blocks | **64** |
| Task timeout | 600 s | **3600** |
| Per-step deadline | 20 s | **60** |
| Bag size | 36 slots | **54** |
| Cobbies per player | 4 | **16** |
| Chat cooldown floor | 200 ms | **0** |
| Connect timeout | 30 s | **120** |
| Retries | 3 | **8** |

All of them are also exposed on the settings screen (**C**) and in `config/cobbie.json`.

## Tests this shook loose

- Two tests asserted the old ceilings. Updated deliberately, and two new ones now prove the new
  behaviour: a **20-step plan is accepted** (it used to be truncated to 8) and a **20-step skill is
  saved** (it used to be rejected above 12).
- The follow test was flaky: it measured distance before the owner's chunk finished streaming in, and
  blamed Cobbie for standing still. It now waits for the target chunk and allows 30 seconds. That was
  a test bug, not a mod bug, and it had been intermittently red across several runs.

## Deliberately kept

Not limits on capability — limits on what he may do *to you*:

- permission switches (`mining`, `building`, `combat`, `player_combat`, `exploration`, `collection`)
- the verb whitelist, checked twice: once when parsing, again at execution
- world-border and loaded-chunk checks before any block is touched
- "never invent coordinates" in the prompt, and verification of every world change by reading it back

With 24-step plans and a 550B model, an over-eager plan can now do more in one turn than before. The
permission switches are the thing standing between that and your base.

## Still not verified

- How he looks in-game (screenshots captured, unreviewed).
- Plan quality at the new size: a 24-step plan is allowed, not necessarily wise. If he starts
  over-committing, the right fix is prompt guidance, not a smaller cap.
