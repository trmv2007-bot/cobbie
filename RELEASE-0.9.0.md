# Cobbie 0.9.0 — equip, honest inventory, drop, pooled endpoint

Target: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.162.0+26.3**, Java **25**.
Artifact: `cobbie-0.9.0.jar`. 91 unit tests, 11 in-game tests, all green against the pooled endpoint.

## "He says he has a sword he doesn't have"

His bag *was* already in the prompt, so this was the model answering from imagination. Two changes:

- the snapshot line is now **`YOUR INVENTORY (exactly what you carry; never claim anything else)`**
- the reply rules gained **rule 9**: never claim to own something that is not in that line; say so, or
  use `collect` / `drop` / `equip` to actually change what he carries.

The label change broke two unit tests that assert the rendered snapshot — updated deliberately, because
the prompt text is a contract and a change to it should fail something.

## New verb: `equip`

"take this armour and wear it" had nothing behind it. `equip <item>` now:

- sends armour to the slot the game assigns it (head/chest/legs/feet), anything else to the main hand
- puts whatever was in that slot back in his bag instead of deleting it
- reads the slot back and verifies; if the game refuses, the item returns to the bag

Verified: **helmet on head, sword in hand**, both by reading the equipment slots afterwards.

## Already in from this line of work

| Verb | Status |
|---|---|
| `drop` | verified — "dropped 3 diamonds", bag emptied, 3 item entities on the ground |
| `give` | verified — owner gained 1 stone, his stack went 3 → 2, no duplication |
| `light` | verified — torch placed from his bag, stack 4 → 3 |
| `equip` | verified — iron helmet to HEAD, iron sword to MAINHAND |

The capability sweep now covers **16 verbs**, each proven to reach an executor and to appear in the
prompt.

## Endpoint and context

- Runs on the OpenAI-compatible pool: `http://127.0.0.1:8787/v1`, `pooled-model`, key in
  `config/cobbie.json` only.
- Requests are pinned to **HTTP/1.1** — Java's default HTTP/2 cleartext upgrade made that proxy answer
  `400 Request body must be valid JSON` on a valid body.
- Context ceilings: `max_chat_history` up to 200, `max_tokens` up to 8192, `read_timeout` up to 600s.
- **`max_tokens` matters on this pool**: at 400 the reasoning consumes the whole budget and `content`
  comes back empty. 1200 is comfortable. Empty replies are retried once regardless.

## Answering "make him a player instead"

No. The inventory was not the problem: the drop, give and equip results above all move real stacks
correctly, and the one false statement was the model inventing a sword. A real `ServerPlayer` would
need a fake connection, network state and login handling, and would put ownership, chat routing,
pathfinding and multiplayer safety all back in play for no gameplay gain. The player-like behaviour he
was missing — carrying, wearing, handing over, dropping — is now there as verbs.

## Still not verified

- **How he looks.** Screenshots captured every run; unreviewed.
- **Plan quality on the pooled model.** One clean reply and a handful of single-step plans is not the
  same as choosing good five-step plans. Worth a dedicated test.
- Multiplayer, dedicated servers, long sessions.

## Install note

Only one cobbie jar may exist in `mods/`. If an older jar is locked by a running game, quit Minecraft
first, then delete it.
