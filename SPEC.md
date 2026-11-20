# Cobbie — SPEC.md

**Your AI friend who actually lives in your Minecraft world.** Not a horror gimmick, not a chatbot in a box — a companion with a body that follows you, talks like a friend, remembers you across sessions, understands the world around him, and acts on it: explore, hunt, build, and learn skills of his own to make playing easier.

Status: SPEC v1 — expanded to autonomous companion. Structure locked; implementation pending.

---

## One-liner
A from-scratch Fabric companion mod: an embodied AI friend (entity + personality + memory + agency) that follows you, converses reliably, perceives the world, acts autonomously, and learns skills over time.

## Problem / target user
Rishi wants a friend in Minecraft that Verity couldn't be: no baked-in horror, real memory, reliable (never silent), full control over personality and behavior, and eventually a companion that actually *does things* with him. Target = single-player-first, multiplayer-capable, BYOK brain.

## Core concept / pipeline
```
PLAYER ──chat/action──▶ COBBIE BRAIN ──▶ decides ──▶ [talk | emote | pathfind | mine | place | attack]
   ▲                        │                                    │
   │ world state            │ tool-calls                         ▼
   │ (coords, inv,          │ (BYOK LLM:                        A* pathfinder
   │  health, nearby)  ─────┘  Spark/Groq/Ollama)                + AI goals
   │                                                              │
   └──────────────── memory (persistent, cross-session) ◀─────────┘
                              ▲
                              │ learned skills (replay successes)
                              └───────────────────────────────────┘
```
Body = entity in world (follow, idle, react, wander). Brain = BYOK LLM with strict JSON tool-calls + never-silent fallback. Memory = local persistent store (player + chat + skills). Agency = LLM emits intent → action executor runs on entity tick.

## Locked decisions

| Decision | Choice | Why |
|---|---|---|
| Loader | **Fabric** | cleaner API, better docs, newest MC installs already Fabric |
| MC version | **26.3** | current; own pathfinder so no Baritone version-lock |
| Mappings | **Mojmap** (`loom.officialMojangMappings()`) | MC 26.x dropped Yarn; codebase uses official Mojang names |
| Architecture | **From scratch (our own), libraries reused** | full control; pathfinding/anim/HTTP pulled from OSS, not rewritten |
| Scope model | **Layered, each layer playable** | never >2 weeks from something that works |
| LLM | **BYOK, pluggable** (local Spark default; Groq/Ollama optional) | user's choice; free local floor |
| Memory | **Persistent local, cross-session** | the thing Verity lacked |
| Agency engine | **Own A* pathfinder (from scratch)** | full control, works on 26.3, Baritone = optional later swap |
| Project name | **Cobbie** | Rishi's pick |
| Workspace | `C:\Users\rishi\Agent\cobbie` (NVMe) | C: = active work |
| Multiplayer | **Yes, after v1 single-player works** | network sync is its own beast |

## Build order (each step = shippable/playable)

| # | Milestone | Deliverable | Depends on |
|---|---|---|---|
| 0 | Dev env | JDK 25 + Fabric Loom 1.17 + Gradle 9.8, hello-world mod runs on MC 26.3 | — |
| 1 | Body | Cobbie entity: model, GeckoLib anims (vanilla fallback renderer), spawn, despawn | 0 |
| 2 | Presence | Follows player, idles nearby, wanders when idle, reactions (look-at, wave) | 1 |
| 3 | Brain | BYOK LLM client, strict JSON tool-calls, never-silent fallback | 1 |
| 4 | Memory | Persistent store: remembers player + conversation + skills across restarts | 3 |
| 5 | Perception | World snapshot (blocks, entities, players, biome, time, health, inventory) fed to LLM | 3,4 |
| 6 | Agency | LLM intents executed via A* pathfinder + AI goals: walk-to, mine, place, attack, chat | 1,2,3,5 |
| 7 | **v1 DONE** | A real friend: body + reliable chat + memory + perception + basic agency, single-player | 2,3,4,5,6 |
| 8 | Explore/hunt/build | Autonomous wandering, combat vs hostile mobs, block placement from inventory | 7 |
| 9 | Skill learning | Record successful action sequences, replay as reusable skills | 7,8 |
| 10 | Multiplayer | Entity + chat synced over network | 9 |
| 11 | Voice | TTS (reuse offline Kitten pattern from Verity) | 7 |

**Playable checkpoints:** end of #2 (a body that follows), end of #7 (the actual friend with agency), end of #8 (explore/hunt/build), end of #10 (multiplayer).

## Constraints / risks
- **Own pathfinder, not Baritone:** agency uses our own A* (v1 scope: walk-to + basic mine/place/attack, ~3 weeks). Edge cases (bridging, parkour, nether) are v3+ polish; Baritone (LGPL-3.0) optional later swap behind same pathfinder interface. Unlocks MC 26.3 (no version-lock).
- **Mojmap learning curve:** codebase uses official Mojang names, not Yarn — newer tutorials may show Yarn names; translate mentally.
- **"Full in one go" temptation:** resisted via layered build order above. Each layer ships before the next starts.
- **Never-silent reliability:** the JSON-fumble that broke Verity is the #1 failure mode. Cobbie's brain layer must have a hard fallback (parse-fail → graceful plain reply, never empty).
- **JDK:** PATH has Java 8; Fabric 26.3 needs JDK 25 — installed at Eclipse Adoptium jdk-25. Step 0 wires it into the build.
- **LLM latency:** every action is an LLM call; local Spark keeps it free but slow. Action executor batches intent decisions, never calls LLM per block.

## v1 definition of done (step 7)
- [ ] Cobbie spawns as an entity in a Fabric 26.3 world
- [ ] Follows the player, idles, wanders when idle, reacts (look-at / emote / wave)
- [ ] Chat: player types, Cobbie replies in character, BYOK LLM (local Spark works out-of-box)
- [ ] Tool-calls/intents work (follow/wave/walk-to/mine/place/attack/chat) via strict JSON with graceful fallback
- [ ] Remembers the player + recent conversation + learned skills across a full game restart
- [ ] Perceives world: nearby blocks/entities/players/biome/time/health/inventory fed to LLM
- [ ] No horror elements; personality is friendly-friend by default, configurable
- [ ] Single-player only (multiplayer is step 10)

## Explicit out-of-scope (for v1)
Multiplayer sync · voice/TTS · bridging/parkour/nether travel · custom model creation · mobile/Bedrock · hosting/servers. All are later steps, not v1.

## Rishi's non-negotiables (from scoping Q&A)
- No horror elements, ever.
- Form = body AND good conversation.
- Eventually: friends join and talk to him (multiplayer).
- Eventually: he mines, fights, builds alongside you (agency).
- Brain: both local Spark AND BYOK pluggable.
