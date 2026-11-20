# Cobbie 0.5.0 — release notes, test report, limitations

Target: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.162.0+26.3**, Java **25**.
Artifact: `cobbie-0.5.0.jar` (197,806 bytes, 49 classes, built by `./gradlew clean test jar`).

## What changed since 0.4.0

### Rendering — he is now a visible entity
`CobbieVanillaRenderer` extended `EntityRenderer` and drew nothing at all; that is why every 0.4.0
session showed an invisible nameplate. Replaced with a real model pipeline:

- `CobbieModel extends HumanoidModel<CobbieRenderState>` built from `MeshDefinition` +
  `LayerDefinition.create(mesh, 64, 64).bakeRoot()` — no model-layer registration, no GeckoLib.
- `CobbieRenderer extends MobRenderer<CobbieEntity, CobbieRenderState, CobbieModel>`: shadow,
  nameplate, `HumanoidMobRenderer.extractHumanoidRenderState` for arm poses and item-in-hand.
- Animation: vanilla walk / idle / head-look from `setupAnim`, plus a right-arm greeting wave
  driven by a `SynchedEntityData` boolean so the server can make the client wave.
- New 64×64 RGBA texture written to match the mesh UVs exactly; unused `geo/`, `animations/` and
  `cobbie_texture.json` GeckoLib leftovers deleted (they were decorative).

### LLM layer
- `OllamaDiscovery` is now the only connection probe in the mod (`/spawncobbie`, `/cobbie test`,
  `/cobbie status` all share it) and is provider-aware: `GET /api/tags` for Ollama,
  `GET /v1/models` for OpenAI-compatible.
- Six states instead of a boolean: `DISCONNECTED / CONNECTING / CONNECTED / MODEL_AVAILABLE /
  MODEL_MISSING / REQUEST_FAILED`. HTTP 200 alone no longer counts as ready.
- Missing model prints the installed list and the exact tag to configure. No silent substitution
  (a bare `lfm2.5` may match its own `lfm2.5:8b`, never a different model).
- `Authorization` is attached only when a key is set; blank keys send no header.
- Bounded retry (`max_retries`, default 1, linear backoff) for transport errors and 429/5xx only;
  404 fails fast instead of being hammered. Structured `Result(content, success, error, retryable)`.
- `think:false` is sent to Ollama. **Measured on the live Spark model: 593 hidden reasoning tokens
  per reply → 0**, same answer. Servers that reject the field drop it and retry once.
- `base_url` is normalised, so a pasted `.../api/` or `.../v1/chat/completions` no longer produces
  `/api/api/chat`. (This bug was live in the config file on this machine.)
- No network I/O during mod init.

### Actions: from one-shot replies to a bounded planner
- `ActionParser` rewritten: string- and depth-aware brace matching (prose and `{` inside the answer
  no longer corrupt the payload), verb whitelist, required numeric coordinates (`mine` with no
  coords used to become "mine 0,0,0"), height/border sanity checks, list cap of 8, and a
  `problems` list so rejected instructions are reported instead of silently dropped.
- `task/TaskRunner` + `TaskPlan` + `TaskStep`: PLAN → VALIDATE → EXECUTE → OBSERVE → VERIFY →
  RETRY → COMPLETE, with unique plan id, per-step attempts, retry budget, wall-clock deadline,
  cancellation, and a one-shot report. Max 8 steps, 25 actions per reply ceiling.
- `CobbiePrompt` now asks for an ordered multi-step plan (up to `plan_step_limit`) instead of
  "exactly ONE action", and tells the model that results are reported back next turn.
- Action results really do go back: the entity pushes its settled report to `CobbieBrain`, which
  shows it to the owner, stores it in memory, credits the skill that ran it, and feeds it to the
  model as a `RESULT REPORT` turn.

### Reach, mining, building
- `action_range=48` is gone. `navigation_range` (how far he walks) and `interaction_reach` (how far
  he can touch) are separate, and a block is only ever modified from inside reach — he walks to a
  computed standing spot first, then acts.
- Mining uses `Block.getDrops(state, level, pos, be, toolUser, toolInstance)` — the game's own
  loot table with the tool he is holding — then verifies the block is air. Hardness and
  protected-block refusals come first; durability is worn; if the bag cannot hold the drops the
  block is broken with vanilla ground drops instead of deleting items.
- Placement validates in order: permission → border → loaded → reach → replaceability → support →
  collision → materials, then `setBlock` and **verify the block is the one asked for** before
  consuming anything. Failed placement costs nothing. Orientation is set to face him.
- `stay` (new verb) and `chat` (now an owner-targeted message instead of a dimension-wide
  `players().forEach` broadcast).

### Owner, persistence, inventory
- Exactly one following mechanism: `FollowMobGoal` (which trailed the nearest thing) is deleted;
  owner-UUID tick following with `follow_distance`, repath throttled to every 12 ticks, and
  offline / other-dimension / unloaded-chunk guards.
- `addAdditionalSaveData` / `readAdditionalSaveData` on the new `ValueOutput` / `ValueInput` API
  persist owner UUID, following state and the whole inventory (typed `Codec` path, not stringly
  NBT). He is the same Cobbie with the same stuff after unload/relog.
- Inventory: registry-ID identity (`BuiltInRegistries.ITEM.getKey`), real stacking with partial
  fills, hand-slot-aware counts and consumption, a full-bag set that stops hammering, and
  tool selection that moves the real stack into the hand (no duplicated tool across save/load).

### Combat
- The unrestricted `NearestAttackableTargetGoal` (a permission bypass) is gone. Targeting is
  `OwnerDefenseGoal`: `Enemy` mobs only, only when they are attacking you or him, gated by
  `combat_allowed` in `canUse()` **and** `canContinueToUse()`. Melee damage is real
  (`doHurtTarget`) with cooldown, line-of-sight and revalidation immediately before each swing.
- Players, passive mobs and the owner can never be auto-targeted. Hitting a player additionally
  needs `player_combat_allowed` (default off).

### Chat privacy and multiplayer safety
- Trigger is a whole-word regex, not `contains("cobbie")`.
- Four modes: `MENTION` (default) / `ALWAYS_LISTEN` / `OWNER_MENTION` / `ALLOWLIST`, plus
  `allowed_players`, `only_allowlisted_players`, `max_cobbies_per_player`.
- A player reaches their own companion or an unowned stray — never somebody else's.
- Answers go to the asker (and the owner, if different). Concurrency is atomic with a real cap,
  duplicate messages are collapsed, stale replies can be cancelled and are then discarded.

### Memory and skills
- Conversation is sent as proper `system` / `user` / `assistant` turns in chronological order — 0.4.0
  stored the player's messages but only ever replayed Cobbie's own answers.
- `max_chat_history` controls the effective window; failures are never stored; writes are atomic
  (temp + `ATOMIC_MOVE`, corrupt file set aside rather than thrown at); nickname rewrites are
  skipped when unchanged; discovery/objective/preference lists are bounded.
- `SkillRegistry` is connected to the real pipeline: skills are injected into the prompt, matched
  requests can be executed by `SkillExecutor` through the same verified `TaskRunner`, outcomes are
  credited back (`cobbie do <skill>`), and a clean multi-step plan is **learned** as a new
  position-relative skill automatically.

### Autonomy and config
- `isAutonomyEnabled` was dead code. Now `autonomy_mode = OFF | ASSISTIVE | CREATIVE` gates
  wandering, self-initiated exploration and proactive skill use; personality is a separate string.
- Permissions are re-checked at execution time inside `begin()`, not only when parsing, so a plan
  queued before you flipped a switch cannot outrank it.
- Every key in `config/cobbie.json` affects behaviour; all numeric keys are validated and clamped.

## Commands

```
/spawncobbie                         spawn + connect (nameplate: Connecting… → Cobbie)
/cobbie status                       real connection state, task, last verified result, bag
/cobbie test                         re-probe provider and list installed models
/cobbie follow | stay | cancel       movement and plan control
/cobbie inventory                    what he is carrying
/cobbie do <skill>                   run a learned skill
/cobbie skills [list|on|off|delete]  manage skills
/cobbie memory [show|clear]          inspect or wipe what he remembers of you
/cobbie autonomy <off|assistive|creative>
/cobbie permit <mining|building|combat|player_combat|exploration|collection> <on|off>
/cobbie reload                       re-read config/cobbie.json
```
Chat without a command: `cobbie follow`, `cobbie stay`, `cobbie stop`, `cobbie bag`,
`cobbie skills`, `cobbie do <skill>` (no model round-trip).

## Test report — by evidence tier

**Compiled:** everything. `./gradlew clean test jar` → BUILD SUCCESSFUL.

**Unit tested — 83 tests, 0 failures, 0 errors** (`build/reports/tests/test/index.html`):

| Class | Tests | Covers |
|---|---|---|
| `brain.ActionParserTest` | 15 | verb whitelist, required coords, quoted numbers, out-of-world coords, action cap, fences/prose, truncated JSON, `{}` inside strings, never-blank answer, length caps |
| `brain/LlmClientTest` | 19 | `/api/chat` vs `/v1/chat/completions`, absent vs present `Authorization`, `options.temperature`/`num_predict`, chronological role history, `think:false`, think-rejection fallback, 5xx retry bound, 404 no-retry, malformed JSON, empty content, unreachable server, in-flight counter, `/api/tags` + `/v1/models` parsing, model matching with no substitution, base-URL normalisation |
| `task.TaskRunnerTest` | 12 | step order, async verify-before-advance, retry budget, no retry on chat, stop-after-hard-failure, cancellation + interrupt, deadline expiry, step trimming, invented verbs blocked, single-plan-at-a-time, progress text |
| `memory.CobbieMemoryTest` | 15 | role-correct history, bounded window, dedupe, corrupt-file recovery, atomic write, nickname idempotence |
| `skill.SkillRegistryTest` | 15 | save/match/enable/delete/cap, reliability ranking, prompt block bound |
| `world.WorldSnapshotTest` | 7 | `describe()` char caps and role rendering |

**Integration tested (real HTTP, no Minecraft):** the 19 `LlmClientTest` cases run against a live
`com.sun.net.httpserver` on localhost, so endpoints, headers, body shape, retries and parsing are
exercised over a real socket.

**Integration tested (live Ollama, this PC, classes from the built jar via `jshell`):**
```
endpoint=http://127.0.0.1:11434/api/chat
chat success=true  raw={"answer":"Hello!","actions":[{"action":"wave","target":"","x":0,"y":0,"z":0}]}
parsed=[ActionIntent[action=wave, …]]  problems=[]
probe=MODEL_AVAILABLE matched=SparkLLM/Spark-X2.6-4B:q4_k_m
probe(bogus model)=MODEL_MISSING installed=[SparkLLM/Spark-X2.6-4B:q4_k_m, lfm2.5:8b]
probe(bare "lfm2.5")=MODEL_AVAILABLE matched=lfm2.5:8b
```
Also measured on the live model: `think:false` removes 593 hidden reasoning tokens per reply and
`content` still carries the JSON answer.

**Gameplay tested:** nothing yet. Every in-game behaviour below is compiled-and-unit-tested but has
**not** been observed running in Minecraft 26.3 in this session: visible model proportions and
texture mapping, the wave animation, nameplate, following, chunk unload/relog persistence, real
mining/building against a live world, combat, and skill learning from an actual play session.
That needs one launch — see SETUP-WINDOWS.md.

**Not tested / not testable here:** dedicated-server behaviour (the client renderer path is never
exercised headlessly, which is also why the client/common split was kept strict); multiplayer with
2+ real players; model-swap on other Ollama builds; long-session memory growth under load.

## Limitations, honestly

1. **Rendering is unverified in-game.** The model maths (64×64 texture, `MODEL_Y_OFFSET`,
   `feet at y=24`) follows 26.3's verified API but has never been looked at through a Minecraft
   window. Proportions may need nudging.
2. **Instant block breaking.** `mineBlock` completes in one tick via `destroyBlock`; there is no
   per-block break progress over seconds and no `destroyBlockProgress` crack overlay.
3. **Skills stay procedural.** A learned skill records verbs plus relative coordinates; there is no
   parametric binding ("this many blocks of *whatever* wood you have").
4. **Tool durability is best-effort.** `hurtAndBreak` is called on the real stack, but the held tool
   is authoritative only in the hand slot after a save/load round-trip.
5. **`getFoodData()` is Player-only in 26.3**, so the perception "food" number is the owner's hunger,
   labelled `owner food`, not Cobbie's.
6. **Timeouts bound the socket, they do not abort it.** A cancelled request is discarded (its reply
   is dropped and never applied) but the underlying HTTP exchange runs to its own timeout.
7. **`cobbie-0.4.0.jar` is gone.** `./gradlew clean` removed it before it was archived, and it
   cannot be rebuilt: `.gitignore` contained `config/`, which silently excluded
   `src/main/java/com/rishi/cobbie/config/` from every commit — so the 0.4.0 `CobbieConfig.java` was
   never in version control. The ignore rule is fixed and the package is committed from 0.5.0 on;
   the 0.4.0 binary is unrecoverable. Rollback point is therefore commit `42cfc06` (source) only.
