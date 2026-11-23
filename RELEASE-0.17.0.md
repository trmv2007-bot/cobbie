# Cobbie 0.17.0 — keys first, local model out of the way

Artifact: `cobbie-0.17.0.jar`. 137 unit tests green (143 counting the 6 opt-in live-provider
checks), and 14 in-game client tests green against a hosted model.

## A fresh install asks for a key instead of assuming a server

The defaults were the whole problem: a new install pointed at `http://127.0.0.1:11434`, assumed
`lfm2.5:8b` was installed there, and waited two minutes for an answer that was never coming. Now:

| Setting | 0.16.0 | 0.17.0 |
|---|---|---|
| `provider` | `ollama` | `openai` (hosted) |
| `base_url` | `http://127.0.0.1:11434/` | `https://api.groq.com/openai/v1` |
| `model` | `lfm2.5:8b` | `llama-3.3-70b-versatile` |
| `read_timeout` | 120 s | 30 s |
| `max_tokens` | 300 | 800 |

Nothing touches `127.0.0.1:11434` unless you pick Ollama, and a hosted provider with no key does not
even make a request — it says "no API key yet — paste one in /cobbie config" and stays out of the
network entirely. An existing `config/cobbie.json` is read exactly as before; nothing is migrated or
rewritten behind your back.

## The settings screen is a provider picker

`/cobbie config` opens on an **AI Provider** row whose button cycles the presets:

```
Groq »  OpenAI »  OpenRouter »  KeyPool gateway »  Custom »  Ollama (this PC) »
```

Choosing one writes `provider`, `base_url` and a sensible default model. Groq, OpenAI, OpenRouter and
the local KeyPool gateway are all the same wire protocol — `POST /v1/chat/completions` with a bearer
key — so they share one code path, not four. The URL and model fields stay editable afterwards, and
**Custom** deliberately leaves both alone: it exists for servers the preset list has never heard of.

The **API Key** field renders only the last four characters. The real value lives in the widget,
never in the text it draws, and lands in `config/cobbie.json` (already gitignored) when you press
Save.

## Test Connection answers the question you actually have

One real completion against exactly what is on screen — unsaved edits included, zero retries — and it
distinguishes four outcomes in words that tell you which field to fix:

| What happened | What it says |
|---|---|
| reachable, model answered | `ready — pooled-model answered in 4115 ms (58 reasoning tokens spent first)` |
| 401 / 403 | `the AI rejected my key — check the API key for KeyPool gateway` |
| 404 / unknown model | `the AI could not find my model — "nope" is not served at this endpoint` |
| nothing listening, or timed out | `the AI server is unreachable — check base_url, and that the server or gateway is running` |
| anything else (400/429/5xx) | `the AI is having trouble — <the provider's own words>` |

`/cobbie test` runs the same check in chat, so the command and the button can never disagree.

## Fast failure, and enough budget to think

- **A timeout is not retried.** It used to be: a dead endpoint meant two 30-second waits before the
  fallback line. Now one timeout produces the never-silent reply immediately, inside the 30-second
  default. Slow local setups can raise `read_timeout` in the screen as before.
- **`max_tokens` defaults to 800.** Measured on this machine's gateway: a greeting ate 238 of 292
  completion tokens as hidden reasoning. At a 300-token budget the visible answer is often empty,
  which is the "model returned no text" path. The usage block is now read, so a budget-starved reply
  says `no text after 16 reasoning tokens (raise max_tokens)` instead of a shrug, and Test Connection
  prints the number too.
- **`max_completion_tokens` is handled.** OpenAI's reasoning models reject `max_tokens` by name; the
  client renames the field once and retries, the same way it already handles Ollama's `think`.

## The key never leaves the Authorization header

- Masked in the screen, so it cannot be in a screenshot.
- Never in a request body, never in a log line — the startup summary prints `key=present` or
  `key=absent`, as it always did.
- Redacted out of provider error bodies: gateways do echo the credential in a 401, and that text used
  to reach chat and the log.
- `COBBIE_PROVIDER` / `COBBIE_BASE_URL` / `COBBIE_MODEL` / `COBBIE_API_KEY` can supply the brain at
  runtime, and a key supplied that way is never written back to disk — even a permission toggle that
  rewrites the config leaves the file's own `api_key` untouched.

## Ollama is still there

Ollama keeps its preset, its native `POST /api/chat` path, its `think` handling and its model
listing. What it loses is the assumption: it is no longer the default and no longer probed when
nobody configured it. `OllamaDiscovery` still owns every reachability question (`/api/tags` for
Ollama, `/v1/models` for everything else), and its wording now names the provider in hand instead of
always suggesting `ollama pull`.

## Verification — what was observed, and what was not

**Unit (`./gradlew test`):** 137 tests green, including provider → endpoint and provider → payload
mapping for every preset, preset inference from a stored config, the masked-key rules, the four
outcomes of the connection test, key redaction, reasoning-token parsing, the
`max_completion_tokens` correction, and `.origin()` keeping exactly one `/v1` on a gateway mounted at
`/v1`.

**Live, against the OpenAI-compatible gateway on this PC** (`pooled-model`, via
`./gradlew test --tests '*LiveProviderTest*' -Dcobbie.live=1`):

```
[live] chat ok: model=pooled-model chars=40 reasoningTokens=130 reply=Hello, friend! Let's go on an adventure!
[live] json turn ok: {"say":"Hello! How can I help you today?"}
[live] connection test: ready — pooled-model answered in 4115 ms (58 reasoning tokens spent first)
[live] bad key: the AI rejected my key — check the API key for live
[live] bad model: the AI could not find my model — "definitely-not-a-model-000" is not served at this endpoint
[live] dead port: the AI server is unreachable — check base_url, and that the server is running
```

**Live, in game** (`./gradlew runClientGameTest`, client booted against the same gateway with the
provider supplied through the environment so no key is written anywhere):

```
Cobbie brain overridden by environment: provider=openai url=http://127.0.0.1:8787/v1
  model=pooled-model key=from environment
Cobbie config: url=http://127.0.0.1:8787/v1 model=pooled-model provider=openai key=present
  temp=0.7 tokens=800 autonomy=ASSISTIVE chat=MENTION
Cobbie 0.17.0 ready - say his name (◕‿◕)

[gametest] config ui ok: preset label, masked key, preset application; screenshots=
  cobbie_config_keys_first.png, cobbie_config_preset_applied.png
[gametest] chat ok: reply length=85 first line="Hello there, TestPlayer! It's so nice to see
  you. How's your day going in the forest?"
```

All 14 client tests passed: capability, abilities, death loot, imperatives, plan, status, config ui,
follow, commands, mine, place, combat, persistence, smoke, chat. The config-screen test opens the
screen on a config holding a key, asserts the Provider button names the preset that config implies,
clicks the cycle to `Custom` (URL untouched) and on to `Ollama` (URL and model rewritten), and saves
two screenshots. Masked rendering is asserted by the unit tests on the mask and by the field having
exactly one rendering path; the screenshots are the artefact for a human to confirm on screen.

Two things this run needed, both said plainly:

- The suite is not hermetic: it talks to a real model. `build.gradle` now forwards
  `COBBIE_PROVIDER`/`COBBIE_BASE_URL`/`COBBIE_MODEL`/`COBBIE_API_KEY` into it, so a green run can be
  reproduced without a key ever touching a file.
- `CobbieTestSupport` now drains the brain's in-flight counter before spawning. The counter is
  static and tests share one client, so a request from the previous test's world could still be
  running and made a fresh companion report "thinking" — which is what `CobbieStatusTest` tripped
  over. That was test isolation, not a change to how he acts.

**Not verified, and worth saying plainly:**

- The **Groq, OpenAI and OpenRouter presets were never called** — there is no key for any of them on
  this machine. Their default model ids (`llama-3.3-70b-versatile`, `gpt-5.4-mini`,
  `meta-llama/llama-3.3-70b-instruct`) are best guesses as of November 2026, not confirmed against a
  live account. If one has been retired, Test Connection will say "the AI could not find my model"
  and the model field is right there.
- **Ollama was not exercised in this version.** The path is unchanged and unit-tested at the wire
  level, but no local model was run against it for 0.17.0 — that is the setup being moved away from.
- The **lag** was never attributed. This release removes 120-second hung calls and the reasoning-token
  waste, both of which stall a turn; it makes no claim about Cobbie's own tick cost.

## Install

```bash
# with Minecraft closed
rm -f "$APPDATA/.minecraft/mods/cobbie-0.16.0.jar"
cp build/libs/cobbie-0.17.0.jar "$APPDATA/.minecraft/mods/"
```

Then, in game: `/cobbie config` → press **Provider** until it reads the provider you have a key for →
paste the key → **Test Connection** → **Save**. The existing `config/cobbie.json` keeps its key and
endpoint: pressing Save before testing rewrites `provider`/`base_url`/`model` with whatever the
screen shows, so check those two fields first.
