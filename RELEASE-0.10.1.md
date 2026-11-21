# Cobbie 0.10.1 — inventory honesty, death handover, no JSON in chat

Artifact: `cobbie-0.10.1.jar`. 94 unit tests, 12 in-game tests, green against the pooled endpoint.

Everything here came from reading one real session (23:09–23:15) rather than from guessing.

## 1. Death no longer deletes what he carries
Vanilla deletes a mob's inventory. His now goes to his owner, and he says so in chat:
*"I died at … — everything I was carrying is in your inventory."*

Why the owner and not the ground: in this build, item entities created from a dying entity are
accepted (`addFreshEntity` returns true, `Block.popResource` returns normally) and then **do not exist
in the world** — verified three ways, including by walking the player over the corpse and watching him
pick up nothing. Putting the stacks in the owner's inventory is the path `give` already proves, and the
test verifies it from the owner's side:
`death loot ok: owner recovered 8 items from the corpse`.

If the owner is absent or their inventory is full, the remainder is logged rather than silently lost.
The ground-drop variant is a known gap, not an oversight.

## 2. He stops inventing his inventory
The session: "my inventory is empty" at 23:10:15, then "netherite armour — helmet, chestplate,
leggings, boots — plus a netherite sword" at 23:11:03, then "16 items — one netherite axe and fifteen
netherite pickaxes" at 23:14:34. The bag was in the prompt every time.

- The bag is now restated as the **last line** of the prompt: "FINAL CHECK — your bag right now
  contains exactly: … Anything else you think you have is not real."
- Rule 9 forbids claiming anything not in that line.
- Every physical pickup is now reported into his next perception ("picked up 3 item(s): minecraft:diamond"),
  which is the "doesn't realise when it gets something" half of the complaint.

This is mitigation, not a cure: a 550B model can still embellish. The recency line is the strongest
lever available without a tool-call loop.

## 3. No more JSON in chat
`{"answer":"The armor didn't equip — it says I have no helmet` reached the player at 23:11. The model
had put a JSON object inside its own `answer` field. The parser now unwraps one level, with a unit test
for the nested case.

## 4. Test flakiness that was hiding real signal
The follow test and the commands test both measured distance before the owner's chunk finished
streaming in, and blamed Cobbie for standing still. Both now wait for the chunk. Two intermittent red
runs in this session were this, not the mod.

## Still open

**He promises actions he doesn't queue.** "wave bruh" → "Waving hello! 👋" with no action in the reply,
so nothing happened. The rules say not to claim a *completed* action, but nothing stops him narrating
one he is not queueing. This needs a prompt rule plus a test, and it is the next thing to fix because
it is indistinguishable from lying.

## Evidence, this build

```
capability ok: 16 verbs reach an executor and appear in the prompt
abilities ok: torch at 0,-60,4 (4->3), gave 1 stone (2 left), dropped 3 diamonds, helmet on head, sword in hand
death loot ok: owner recovered 8 items from the corpse
config ui ok: screen opened and closed
mine / place / combat / persistence / commands / follow / chat — all ok
```
