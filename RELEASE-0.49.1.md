# Cobbie 0.49.1 — a skill remembers the world it came from

Artifact: `cobbie-0.49.1.jar`. **179 unit tests green**, `CobbieSkillTest` green in game.

The owner's call on a question 0.49.0 raised: **skills stay per-install, and the record says where each
one came from.** A skill is procedural knowledge, not a memory of a place — a habit that evaporates when
he walks into another world is not a habit — and the anchoring from 0.49.0 already makes a gather sensible
anywhere. So this is metadata for the human, with no behaviour change.

```
skill: learned "gather-the-oak-logs" — 3 anchored step(s), from world "New World",
       saved to disk and shown in the list
```

- `learned_in` is written to `cobbie_skills.json` and read back (`world` is accepted as an older name);
  an existing file without it loads unchanged, and the field is omitted when empty rather than written as
  a blank.
- `/cobbie skills list` shows `from <world>` beside the reliability, so an owner can tell a habit he
  watched being learned from one that arrived with the config.
- The prompt block does not change: the model's view of a skill is unchanged, which is the point of
  calling it metadata.

## Verification

- `cleanTest test`: **179 tests, 0 failed, 6 skipped**.
- `CobbieSkillTest`: 1 passed, 0 failed of 1, `--no-build-cache --rerun-tasks`, now also asserting that
  the recorded world is this world's name and that the list shows it. The replay is still measured at
  **0 model requests** on a pile it was never learned on.
