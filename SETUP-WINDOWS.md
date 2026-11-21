# Cobbie 0.7.0 — install, Ollama setup, model swap

Everything below is offline-capable and free. Cobbie never sends anything to the internet except
the AI endpoint you configure (default: `127.0.0.1`, your own PC).

## 1. Minecraft 26.3 + Fabric loader

Official route (recommended, supports your legitimate purchase):
1. Minecraft Launcher → Installations → **New installation** → Version **26.3** → Create.
2. Fabric installer from https://fabricmc.net/use/installer/ → pick **26.3** + latest **Loader
   (0.19.5+)** → Install. It adds a "fabric-loader-26.3" installation.
3. Launch once so the profile exists, then quit.

Your existing **TLauncher → profile "Fabric 26.3"** works identically for mods; its game directory is
`C:\Users\rishi\AppData\Roaming\.minecraft`. Nothing else to install — Fabric API is already in
`mods\` (`fabric-api-0.162.0+26.3.jar`).

Java 25 is required by MC 26.3; the launcher brings its own runtime, so no system JDK needed to play.
(You already have `C:\Users\rishi\jdk25` for building.)

## 1b. Proving it works without playing (optional)

`./gradlew runClientGameTest` boots a real client, generates a world per test and checks eight
behaviours end to end (spawn, follow, stay/follow, chat, mining, building, combat, persistence) in
about two minutes. Needs Ollama up for the chat test. Screenshots land in
`build/run/clientGameTest/screenshots/`.

## 1c. Opening the settings screen

Press **C** in game, or run `/cobbie config`. Every setting that changes behaviour is there, labelled,
with a **Test Connection** button that probes the endpoint and names the model it found. Saving applies
immediately.

## 2. Install the mod

```bash
cp /c/Users/rishi/Agent/cobbie/build/libs/cobbie-0.7.0.jar \
   "/c/Users/rishi/AppData/Roaming/.minecraft/mods/"
```
Or in Explorer: `%APPDATA%\.minecraft\mods\` → drop `cobbie-0.7.0.jar` in. **Only one cobbie jar** —
delete any older one; Fabric caches jars, so fully quit Minecraft before relaunching after a swap.

Required alongside: `fabric-api-*.jar` for 26.3 (present). Not required: GeckoLib — Cobbie no longer
uses it, and GeckoLib 5.5.7 crashes on 26.3.

`config\cobbie.json` was already written for you with these values; it is regenerated on first run
if missing:

```json
{
  "provider": "ollama",
  "base_url": "http://127.0.0.1:11434/",
  "model": "lfm2.5:8b",
  "api_key": "",
  "temperature": 0.6,
  "max_tokens": 320,
  "connect_timeout": 5,
  "read_timeout": 120,
  "max_retries": 1,
  "personality": "warm, curious, a little playful; a good friend who is never scary",
  "autonomy_mode": "CREATIVE",
  "plan_step_limit": 5,
  "task_retry_limit": 1,
  "max_concurrent_requests": 2,
  "task_timeout_seconds": 180,
  "follow_distance": 4.0,
  "navigation_range": 24,
  "interaction_reach": 4.0,
  "mining_allowed": true,
  "building_allowed": true,
  "combat_allowed": true,
  "player_combat_allowed": false,
  "exploration_allowed": true,
  "collection_allowed": true,
  "chat_mode": "ALWAYS_LISTEN",
  "allowed_players": [],
  "chat_cooldown_ms": 900,
  "memory_enabled": true,
  "max_chat_history": 12,
  "persistent_memory": true,
  "inventory_size": 27,
  "only_allowlisted_players": false,
  "max_cobbies_per_player": 1
}
```

What the numbers mean, in the two ways people get bitten:
- **`interaction_reach` (4.0) is the block-modification radius. `navigation_range` (24) is only how
  far he will walk.** There is no 48-block remote editing any more.
- **`autonomy_mode` never unlocks destruction by itself.** `mining_allowed`, `building_allowed`,
  `combat_allowed` are separate switches and are re-checked at execution time. `player_combat_allowed`
  stays `false` unless you deliberately want him hitting players.
- `chat_mode`: `MENTION` (default upstream) needs the word "cobbie"; this machine is set to
  `ALWAYS_LISTEN` because you asked not to repeat his name. Other players' conversation still never
  reaches the model unless they are in `allowed_players` or `only_allowlisted_players` is on.

## 3. Ollama on Windows

Already installed and running on this PC (verified live during the build). For a fresh machine:

1. Download from the official site https://ollama.com/download/windows and install (it registers
   `ollama` on PATH and starts a background server on port 11434).
2. Check it answers:
   ```bash
   curl -s http://127.0.0.1:11434/api/tags | head -c 200
   ```
3. If it is not running: `ollama serve` in a terminal, or start the "Ollama" tray app.
4. GPU is used automatically when a CUDA card is detected — no `gpu_layers` setting exists in
   Ollama's API; that key is ignored and can be deleted from your config.
5. Optional auto-start: Task Scheduler → new task running `ollama serve` at logon (hidden).

VRAM on this machine (RTX 3050 8GB): the Spark 4B Q4_K_M weights are 3.5 GB and fit, with Minecraft
(~1–1.5 GB) alongside. If both do not fit, Ollama spills layers to CPU and replies get slow — set
`max_tokens` lower (160) and shorten `max_chat_history` (6) before changing models.

## 4. Which model to configure, and swapping it

Verified on this PC right now (`/api/tags`):

| Model tag | Size | Notes |
|---|---|---|
| `lfm2.5:8b` | 4.9 GB | **use this one.** Advertises `tools` + `thinking`, follows the JSON contract, ~2–4 s/reply with thinking switched off |
| `SparkLLM/Spark-X2.6-4B:q4_k_m` | 3.5 GB | also installed; smaller and faster, a drop-in if the 8B is too slow on your machine |

Set it in `config\cobbie.json` → `"model": "lfm2.5:8b"`, then in game:
```
/cobbie test        # probes the server and confirms the exact tag is installed
/cobbie status
```
If the tag is wrong, `/cobbie test` and `/spawncobbie` both print the installed list instead of
quietly using something else.

Pull a different model:
```bash
ollama pull qwen2.5:3b-instruct        # small + fast, good JSON following, fits 8GB easily
ollama pull llama3.1:8b                # smarter, slower on a 3050
ollama list                            # exact tags to put in "model"
```
Any OpenAI-compatible endpoint also works (`lmstudio`, `llama.cpp server`, a remote host):
```json
{ "provider": "openai", "base_url": "http://192.168.1.20:1234/v1",
  "model": "your-model", "api_key": "" }
```
`provider: "ollama"` uses `/api/chat` + `/api/tags`; `provider: "openai"` uses
`/v1/chat/completions` + `/v1/models`. `base_url` may be pasted with or without its endpoint path,
and with or without a trailing slash — it is normalised.

## 5. First-run checklist (the gameplay test that is still outstanding)

1. Ollama running (`curl` check above), Minecraft 26.3 Fabric profile, one cobbie jar in `mods`.
2. Load a world (cheats are **not** needed — the commands register without a permission
   requirement, so a normal single-player world works).
3. Type `/spawncobbie` → expect: he appears 2 blocks away with a **visible humanoid body**,
   nameplate `Connecting…`, then `Cobbie`, and chat says **`I'm Ready To Play`** plus the matched
   model name. If it instead says *model missing* / *LLM offline*, the message lists what is
   actually installed and what to set.
4. Say something without a command: `hello cobbie` → he answers in chat within a few seconds.
5. `cobbie follow`, then walk far → he trails and stops at `follow_distance`. `cobbie stay` → he
   holds position.
6. Permissions are ON in your config, so try: `cobbie mine the dirt at <x> <y> <z>` (use F3
   coordinates). Expect a chat line stating what he actually did, then a **`result - completed …`**
   line when the plan finishes. If he cannot reach it or lacks a tool, he says that instead of
   pretending.
7. `/cobbie status` → connection state, current task, last verified result, what is in his bag.
8. `/cobbie inventory` after he picks something up. Then quit to menu, reload the world,
   `/cobbie status` → same owner, same bag (persistence check).
9. Ask for two steps in one go — `cobbie walk to <x y z> then mine <x y z>` — and watch the
   ordered plan run and report.
10. Logs if anything misbehaves:
    `%APPDATA%\.minecraft\logs\latest.log` (search for `Cobbie`) and
    `%APPDATA%\.minecraft\crash-reports\`.

Six of those steps are already checked automatically by `./gradlew runClientGameTest`; this pass is the
human half. The one thing no automated run can settle is whether he *looks* right — capture
`%APPDATA%\.minecraft\screenshots` on step 3 and I will review them against
`build/run/clientGameTest/screenshots/`.
