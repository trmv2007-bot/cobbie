# Cobbie 0.11.0 — he does what you type, and can look things up

Artifact: `cobbie-0.11.0.jar`. 104 unit tests, 13 in-game tests. Verified with the model servers
offline: everything below except the two tests that assert a live brain.

## 1. Typed commands actually run

The complaint was exact: `break this obsidian` → "Breaking that obsidian now!" and nothing happened;
`come here` → "Coming right over!" and he did not move. Prompt rules did not fix it, because the model
was answering instead of acting.

A deterministic matcher now runs **before** the model for short commands:

| You type | He does |
|---|---|
| `wave`, `wave bruh`, `cobbie wave hello` | waves |
| `come here`, `come to me`, `follow me`, `cobbie come` | follows |
| `stay`, `stop`, `wait there` | holds position |
| `pick up`, `pick up this item` | collects |
| `drop your netherite pickaxe`, `drop all your diamonds` | drops that stack |
| `inventory`, `what do you have` | reads his bag |
| `look up obsidian`, `search for starter house` | asks the wiki |

Conservative on purpose: a few words, imperative at the start, only permitted verbs, filler words
("cobbie", "bro", "please", plurals) stripped. Anything longer or ambiguous still goes to the model.
An explicit command also **pre-empts** a running task, because `drop your stone` was being refused
while he was still walking from `come here`.

Verified in survival through the real chat channel:
`imperatives ok: waved, walked to the owner, dropped his stone`.

## 2. Internet: the Minecraft wiki, nothing else

`lookup <question>` (typed, or emitted by the model as an action) queries `minecraft.wiki`'s public
search API and answers from it. One GET, fixed host, 8-second connect timeout, answer capped at 600
characters, results stored in memory.

Not a general web browser: arbitrary URL fetching driven by a model inside your game process is a
liability, and a fixed wiki host answers "what is obsidian / how do I build X" without it. Broader
search would need an API key and a deliberate decision.

Verified live: `wiki said: Obsidian: Obsidian is a block found naturally in all dimensions…`

## 3. Fault sweep in survival

`CobbieImperativeTest` runs in a survival world and asserts the wave animates, the walk closes
distance, the drop empties his bag, and the lookup returns real wiki text — all through chat, not
through internal calls.

The capability sweep also caught an inconsistency of mine: `lookup` is answered by the brain, not the
body, so verbs are now classified brain-side vs world and the test says which is which instead of
pretending every verb touches a block.

## Still open

- **Plan quality with many steps.** 24-step plans are allowed; nothing proves they are sensible.
- **Inventory truthfulness** is mitigated (bag restated last in the prompt), not guaranteed.
- **Death drops onto the ground** still do not work in this build; his bag goes to the owner instead.
- Two tests (smoke, chat) need a live model server; both were parked for this verification because
  your pool and Ollama were both down. They are restored in the suite.
