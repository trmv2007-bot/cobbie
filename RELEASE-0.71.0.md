# Cobbie 0.71.0 — three obstacles that are real, and a gate that asked the wrong question

Artifact: `cobbie-0.71.0.jar`. **229 unit tests green**, `Adversity` green in game with five scenarios.

The first adversarial run was too easy: two obstacles turned out to be about the fixture, and the stale-skill case
could not be measured at all. This is the version where each obstacle is a real one.

## Five scenarios, and what each one shows

```
vanished block: FAILED — "there is nothing at 5 -60 5"
wall in the path: COMPLETED, stone left=false — mined at -2 -60 6
replay aimed at: mine minecraft:oak_log ~0 0 0, ~1 0 0, ~2 0 0 | oak logs in a 12-block cube: 0
stale skill: "gather-the-oak-logs" needs minecraft:oak_log nearby and I see none — I would be swinging at nothing
emptied slot: FAILED placed=false — "I have no minecraft:torch with me"
fence and door: COMPLETED, stone left=false — mined at 7 -60 3
```

- **The stale skill is measurable now.** A clean gather learns the skill; the replay's aim and the world are printed
  together — three `~` offsets against **zero** oak logs in a twelve-block cube — so the interesting failure (a replay
  that succeeds against the wrong world) is visible rather than inferred from a status.
- **The desync names what went missing.** The torch was removed after the plan accepted the step and before it ran:
  he stopped with *"I have no minecraft:torch with me"* and did not place one.
- **A door and a fence are not a wall.** He got through the closed door and mined the stone on the far side.

## The defect this found

The first version of these obstacles could not be measured because the gather refused to mine an oak log with bare
hands: *"I need a proper tool for minecraft:oak_log"*. The cause is the family this project keeps meeting — **the gate
was asking the autonomy setting where it meant the world's game mode.** `CobbieConfig.isCreative()` is
`autonomyMode == CREATIVE`, so a **creative world with ASSISTIVE autonomy** refused to break a log by hand.

- `breakRefusal` (an instruction) now asks `worldIsCreative()` — the owner's game mode;
- `dropRefusal` (a project) asks only the drop question, `requiresCorrectToolForDrops()`, because "too slow by hand"
  was never a question about the drop.

## Verification

- `cleanTest test`: **229 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Adversity`: 1 passed of 1, five scenarios, `--no-build-cache --rerun-tasks`, and
  `./gradlew --stop` afterwards — the daemons are what emptied the box to 231 MB and killed runs before they started.
