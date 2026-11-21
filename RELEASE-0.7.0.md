# Cobbie 0.7.0 — lfm2.5:8b, a real settings screen, two new abilities, built-in skills

Target: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.162.0+26.3**, Java **25**.
Artifact: `cobbie-0.7.0.jar` (212,847 bytes), built by `./gradlew clean build`.

**Model:** `lfm2.5:8b` is now the default and the model everything below was verified against.
**Unit tests:** 89, 0 failures. **In-game tests:** 11, all green.

## What is new

### 1. lfm2.5:8b is the default brain
`config/cobbie.json` now generates with `"model": "lfm2.5:8b"`. The probe confirms the tag is installed
(`MODEL_AVAILABLE`) and a live chat round-trip through the real chat channel produced a 96-character
reply in character. `SparkLLM/Spark-X2.6-4B` remains a documented drop-in for slower machines.

A stale `config/cobbie.json` from an earlier session silently outranks the new default — that is why an
old file has to be deleted (or `/cobbie reload` after editing) before the switch is real. This bit me
during development and is worth knowing.

### 2. Every ability is now proven reachable by the model
`CobbieCapabilityTest` walks the whole vocabulary in-game and asserts two things per verb: it reaches a
real executor (never "unhandled verb"), and it is named in the system prompt. A new
`ActionVocabularyTest` keeps allowed / described / normalised in lockstep, so a verb added in future
cannot silently become dead weight.

```
verb answer -> OK          verb mine    -> FAILED mine: there is nothing at 0 -58 3
verb chat -> OK            verb place   -> FAILED place: nowhere to stand to build at 0 -58 -3
verb follow -> OK          verb attack  -> FAILED attack: nothing I am allowed to fight is nearby
verb stay -> OK            verb collect -> FAILED collect: nothing to pick up around here
verb wave -> OK            verb explore -> OK explore: wandering a little
verb walk-to -> OK         verb light   -> FAILED place: nowhere to stand to build at 0 0 0
verb wait -> OK            verb give    -> FAILED give: I have nothing to give you
capability ok: 14 verbs reach an executor and appear in the prompt
```

The `FAILED` lines are correct answers to nonsense requests, not coverage gaps: the sweep proves the
verb is wired, the dedicated tests prove it works.

One finding worth keeping: `explore` is gated on autonomy as well as its own permission, so autonomy
`OFF` silently removes a verb from his repertoire.

### 3. The settings screen was rebuilt, not patched
The old screen created ~35 widgets and then drew nothing — section headers and field labels were
constructed and discarded, and there was no scrolling, so most settings existed off-screen and
unlabelled. Now:

- every field is labelled, with section headers (LLM, Personality, Behaviour, Movement, Permissions,
  Chat, Memory, Entity)
- the list scrolls
- **Test Connection** probes the live endpoint and prints the resulting state inline, then saves
- settings apply on **Save** (`CobbieConfig.reload()` reconfigures the brain immediately — no restart)
- opens with a **keybind** as well as `/cobbie config`

Verified by `CobbieConfigUiTest` (opens, renders, closes) with screenshots written to
`build/run/clientGameTest/screenshots/`.

### 4. Two new abilities
| Verb | Does | Verified by |
|---|---|---|
| `light` | places a torch from his bag at x,y,z, through the same validated placement path as `place` | torch appeared at 0,-60,4 and his stack went 4 → 3 |
| `give` | hands one item from his bag to the owner, checked from both sides | owner received 1 stone, his stack went 3 → 2, nothing duplicated |

Both are in the prompt automatically, because the prompt's verb list is generated from the vocabulary.
`light` respects `building_allowed`; `give` is always allowed (handing something to you cannot hurt).

### 5. Built-in skills, so `cobbie do` works on a fresh install
Seeded once on a new install, as ordinary skills he can run, fail, learn from, and the owner can mute
or delete:

- `wave hello`
- `light the way` — places a torch a couple of blocks ahead (relative coordinates)
- `gather some wood` — mines two logs in front of him

`cobbie do wave hello` now runs a real plan through the same TaskRunner as everything else.

## Bugs fixed in this release

1. **Skill lookup could never find a saved skill by the name a player types.** Saving normalised
   `"wave hello"` to `wave-hello`; lookup only lower-cased, so `cobbie do wave hello` answered "I do
   not know a skill called wave hello" while `cobbie skills` listed it. Lookup and save now share one
   normalisation. This also broke the built-in skills before it broke anything else.
2. **Built-in seeding leaked into the unit-test store.** An empty store and a fresh install are not the
   same thing; seeding is now install-only and six registry tests went back to green.
3. Carried from 0.6.0: follow cancelling task navigation, `walk-to` arrival radius, the executor
   judging a walk before the navigator had computed one, and raw JSON being spoken aloud.

## Evidence, in-game, this build

```
capability ok: 14 verbs reach an executor and appear in the prompt
abilities ok: torch at 0,-60,4 (4->3), gave 1 stone (2 left), skills=[wave-hello, light-the-way, gather-some-wood]
config ui ok: screen opened and closed
mine ok: block at 0,-60,8 broke, final distance=2.52
place ok: dirt at 0,-60,6, stack 8 -> 7
combat ok: zombie health 19.0, sheep untouched at 8.0, owner alive
persistence ok: same uuid, same owner, stone intact at 7
commands ok: stay drift=0.00, follow 8.54 -> 6.45
follow ok: distance 14.18 -> 12.17
chat ok: reply length=96 "Hello there, friend! I'm Cobbie, happy to be here with you — what would you like to do together?"
```

## Still not verified

- **How he looks.** The screenshots are written every run and nobody, human or model, has reviewed
  them this session. Proportions, texture, nameplate and the wave remain unverified.
- **Model quality beyond one reply.** One clean reply at temperature 0.7 proves the pipeline, not that
  lfm2.5:8b is good at multi-step plans. Expect to tune `plan_step_limit` and personality.
- **`light`/`give` under a real conversation.** Verified through the executor, not yet through a model
  choosing to use them in chat.
- Multiplayer, dedicated servers, long-session memory growth.

## Install

Close Minecraft first, then:

```bash
rm -f "/c/Users/rishi/AppData/Roaming/.minecraft/mods/cobbie-0.6.0.jar"
cp build/libs/cobbie-0.7.0.jar "/c/Users/rishi/AppData/Roaming/.minecraft/mods/"
```

Only one cobbie jar may exist in that folder.

## Try this first

1. `/spawncobbie`, then press **C** (or `/cobbie config`) — the settings screen should open with labels
   and sections.
2. Hit **Test Connection**: it should print `MODEL_AVAILABLE (lfm2.5:8b)`.
3. `cobbie skills` — the three built-ins should be listed.
4. `cobbie do light the way` (give him torches first: he places his own, and will say so if his bag is empty).
5. `cobbie hand me a torch` — `give` should move it into your inventory.
