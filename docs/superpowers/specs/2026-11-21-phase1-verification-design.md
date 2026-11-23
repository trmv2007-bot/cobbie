# Cobbie Phase 1 — In-Game Verification: Design

**Date:** 2026-11-21
**Status:** Approved by Rishi ("try A and do it")
**Scope:** Phase 1 of 3. Phase 2 (release polish) and Phase 3 (SPEC steps 8-11) are gated on this.

---

## Problem

Cobbie is 8154 lines across 30 classes with 83 passing unit tests, and **not one line of it has
ever been observed running in Minecraft.** `RELEASE-0.5.0.md` states it plainly:

> **Gameplay tested:** nothing yet. Every in-game behaviour below is compiled-and-unit-tested but
> has **not** been observed running in Minecraft 26.3.

The gap is not features. It is evidence. Body, animations, following, mining, building, combat,
persistence and skill learning are all claimed and none is verified. Phase 2 would polish
unverified code and Phase 3 would build on it.

## Goal

Every behaviour is either **observed working** or **reported broken with evidence**. No behaviour
is described as working on the strength of a unit test.

## Constraints discovered during reconnaissance

| Constraint | Consequence |
|---|---|
| Horizon (the Rust MC reimplementation) has **no JVM** and cannot load Fabric jars | Horizon is not a verification target, at any point, with any amount of work. Its own docs: "Fabric and NeoForge jars do not run in Horizon." |
| Rishi launches TLauncher himself; memory says "do not launch the game unattended" | A verification path that needs a human per cycle cannot be the primary loop. |
| `cobbie-0.4.0.jar` is unrecoverable; `.gitignore` `config/` stripped a Java package | Never use bare directory names in `.gitignore`. |
| MC 26.3 API surface differs sharply from 1.20/1.21 | Every API assumption must be checked with `javap` against the real jars, not recalled. |

## Chosen approach

**Loom's client gametest harness (`fabric-client-gametest-api-v1`) driven by `runClient`.**

This was not the original plan. The original plan was "launch `runClient`, drive it via a debug
command, read logs". Reconnaissance found something strictly better: Fabric ships a first-class
client game test API, and Loom 1.17.21 has `fabricApi { configureTests { ... } }` with
`enableClientGameTests`. That gives, with no human in the loop:

- **`TestSingleplayerContext`** — creates a real world and boots a real client into it
- **`ClientGameTestContext.runCommand(String)`** via `TestServerContext` — runs `/spawncobbie`
- **`waitFor(Predicate<Minecraft>)`** — deterministic waits, not sleeps
- **`takeScreenshot(String) -> Path`** — real rendered frames, no F2 key
- **`assertScreenshotContains(...)`** — screenshot comparison, built in
- **`computeOnClient` / `computeOnServer`** — reach both sides from test code

Assertions run **inside the actual game**. A test that spawns Cobbie, waits, screenshots and
asserts the entity exists is stronger evidence than any log line, because it fails loudly when
the behaviour regresses.

### Rejected alternatives

| Approach | Why not |
|---|---|
| Horizon + `game_*` MCP tools | Structurally impossible — no JVM. Would produce zero evidence. |
| TLauncher + human at keyboard | Works, but every cycle needs Rishi. Cannot run unattended; too slow to be primary. Retained as **final acceptance test**, run once at the end. |
| `runClient` + hand-rolled debug command | Works (verified), but is a bespoke harness reimplementing what Fabric already provides, and gives weaker evidence than assertions. Retained as a manual escape hatch. |
| `runServer` + server-side gametests only | Cannot see the body render — the single most important unverified claim. Server gametests are a useful **supplement** for logic-only checks (persistence, planning). |

## Verified foundations

Established by execution during reconnaissance, not inference:

| Fact | How it was proven |
|---|---|
| `runClient` boots a real Fabric 26.3 client | Launched; reached main menu; `Player110`; sound engine up |
| Cobbie loads clean in the dev client | Log: `Cobbie 0.5.0 ready - say his name`, `client renderer + config screen registered`, no exception |
| Isolated game dir, no interference | Fresh `run/` with its own `saves/`, `config/`, `mods/`, `logs/` |
| JDK 25 present | `openjdk 25.0.4.1 Temurin` |
| Assets already local | asset index 34 present in `.minecraft/assets` (1.7G) — no re-download |
| Client gametest API present and complete | `javap` on all context/world/input/screenshot interfaces |
| Loom gametest support present | `javap net.fabricmc.loom.api.fabricapi.GameTestSettings` |
| Trigger property | `fabric.client.gametest.modid`; entrypoint id `fabric-client-gametest` |

## Architecture

```
gametest source set (separate from main)
        │
        ├── CobbieSmokeTest      spawns him, asserts entity exists       → proves body spawns
        ├── CobbieRenderTest     screenshots, asserts pixels             → proves body renders
        ├── CobbieChatTest       sends chat, asserts a reply arrives     → proves never-silent
        ├── CobbieFollowTest     moves player, asserts he follows        → proves presence
        ├── CobbieActionTest     mining/building change real blocks      → proves agency
        ├── CobbieCombatTest     hostile targeting, player safety        → proves combat rules
        └── CobbiePersistenceTest relog, asserts inventory/owner intact  → proves memory
                │
                ▼
        each test: setup → act → waitFor → assert → screenshot
                │
                ▼
        gradle run → exit code + XML report + PNG artefacts
```

Tests are **independent and individually runnable**. A failing one names the behaviour, not the
line. Screenshots are artefacts for human review when an assertion fails.

### Unit-test boundary

Existing 83 unit tests stay as the regression floor for pure logic (parsers, memory serialisation,
retry policy, plan state machine). They are not evidence of in-game behaviour and will not be
presented as such. The new tier is: *compiled → unit tested → **in-game observed***.

## Failure handling

`runClient`'s first boot does setup that can be flaky, and a dev-client launch can hang. Mitigations:

- Harness setup is done and proven **before** any behaviour test is written (done — see above).
- If the gametest harness cannot be made to run, fall back to the `runClient` + debug-command path
  (also verified working) and say so plainly rather than reporting unverified work as done.
- Any behaviour that cannot be verified is reported as **not verified**, never as passing.

## Out of scope for Phase 1

Multiplayer with 2+ real players · dedicated-server behaviour beyond logic-only checks · TTS/voice ·
bridging/parkour/nether pathfinding · any new feature. Phase 1 changes no behaviour unless a test
proves it broken.

## Definition of done

- [ ] Gametest harness runs from one command, exit code meaningful
- [ ] Each behaviour above has a test that **fails when the behaviour is absent**
- [ ] Every test observed passing, or its failure reported with the artefact that shows it
- [ ] Screenshots captured at least once per visual claim (body, animation)
- [ ] `cobbie-0.6.0.jar` rebuilt and deployed
- [ ] One acceptance checklist handed to Rishi for a single TLauncher pass
- [ ] `RELEASE-0.6.0.md` reports per behaviour: observed-working / observed-broken / not-reached
