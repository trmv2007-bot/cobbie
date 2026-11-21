# Cobbie 0.8.0 — drop, any OpenAI-compatible endpoint, large context

Target: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.162.0+26.3**, Java **25**.
Artifact: `cobbie-0.8.0.jar`, built by `./gradlew clean build`. 91 unit tests, 11 in-game tests, green.

## Verified against a real pooled endpoint

Cobbie now runs on an OpenAI-compatible key pool: `http://127.0.0.1:8787/v1`, model `pooled-model`,
`Authorization: Bearer <key>`, protocol OpenAI Chat Completions. In-game reply:

```
chat ok: reply length=29 first line="Hi there! Nice to see you! 😊"
Cobbie config: url=http://127.0.0.1:8787/v1 model=pooled-model provider=openai key=present
```

The endpoint lives in `config/cobbie.json` (`provider`, `base_url`, `model`, `api_key`) and in the
settings screen (press **C**). The key is written to that local file only — never to the repository,
never to a log line. The 400-diagnostic logs the *body* on rejection, never the header.

### The bug that made it fail: HTTP/2 cleartext upgrade
Every request from the mod came back `HTTP 400 {"detail":"Request body must be valid JSON"}` on a body
that was provably valid. The cause was not the JSON. Java's `HttpClient` defaults to HTTP/2 and, for a
plain `http://` URL, attempts a cleartext upgrade the proxy mishandles. Requests are now pinned to
**HTTP/1.1**, and every OpenAI-compatible server behaves.

Ollama never caught this because it tolerates the upgrade. If another client of yours fails against
that pool with a confusing JSON error, this is why.

### Large context
Ceilings were clamping real models, so:

| Setting | Was | Now |
|---|---|---|
| `max_chat_history` | 12 default, capped 40 | 12 default, capped **200** |
| `max_tokens` | capped 2048 | capped **8192** |
| `read_timeout` | 120s | configurable to 600s (set to 300 here) |

Both ceilings are exposed in the settings screen, so a big model is not throttled by the mod.

## New verb: `drop`

Your session showed the gap exactly: "cobbie drop all ur diamonds" got *"I'm unable to drop items
directly with my current tools"* — because there was no `drop` verb. There was `give` (hand to you)
and `place` (world blocks), nothing for putting things on the ground.

- `drop <item>` puts the **whole matching stack** on the ground where he stands (or at x,y,z)
- a number in the request drops that many instead
- verified on both sides: bag count down by exactly that amount, and a real item entity in the world
- if the world refuses the entity the items go back in his bag — nothing is ever deleted

Evidence: `abilities ok: … dropped 3 diamonds`, and the capability sweep is now **15 verbs**, every one
reaching an executor and named in the prompt.

## Reliability: empty replies are retried

`cobbie get me some wood` twice answered *"the AI is having trouble"*. That was lfm2.5:8b returning an
empty reply — usually a reasoning block swallowing the whole token budget. An empty reply is now
retried once instead of being reported as a failure, and the reasoning stripper handles replies cut off
mid-thought.

## Also in this build (from the 0.7.0 line)

- `lfm2.5:8b` stays the default model for a fresh install
- the settings screen rewritten: labels, sections, scrolling, **Test Connection**, applies on save
- `light` places a torch from his bag; `give` hands you one item, checked from both sides
- three built-in skills on a fresh install: `wave hello`, `light the way`, `gather some wood`
- follow no longer cancels task navigation, and no longer freezes when any plan is queued

## Still not verified

- **How he looks.** Screenshots are captured every run; nobody has reviewed them.
- **Plan quality on the pooled model.** One clean reply proves the pipeline. A 550B model behind a pool
  should plan better than the 8B, but that is a claim to test, not assume.
- Multiplayer, dedicated servers, long-session memory growth.

## Try this

1. Press **C** → **Test Connection**: should print `MODEL_AVAILABLE (pooled-model)`.
2. `cobbie drop all your stone` — items should land on the ground and leave his bag.
3. `cobbie hand me a torch` — should move into your inventory instead.
4. `cobbie light the way` — places a torch from his own bag.
