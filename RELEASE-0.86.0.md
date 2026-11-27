# Cobbie 0.86.0 — memory that outlives the session

Artifact: `cobbie-0.86.0.jar`.

He forgot the world between sessions. Where home was, which chest held the iron, that the anvil by the
door was chipped, that the last attempt to reach the roof failed and why, what the owner said he
wanted — a player carries all of that in their head, and he carried none of it. Learned skills were half
the puzzle (how to do a thing); this is the other half (what is true about this world, and what you
asked me for).

The rule of this codebase applied to a note as much as to a blockstate: **a memory is a hint about where
to look, never evidence for a claim.** Nothing in the mod builds an `ActionOutcome` out of a memory.

## Five kinds, because a string bag cannot be checked

| kind | what it is | created by | checked against the world? |
|---|---|---|---|
| `PLACE` | a name and coordinates — home, the mine mouth | the owner | yes: it must still have ground within three blocks of the feet |
| `CONTAINER` | a container and what is in it | using one (`take`, `put`) | yes: the slots are re-read and the fact is rewritten from them |
| `BLOCK` | a durable fact about a station — this anvil is chipped | working at one (`anvil`) | yes: the block is re-read; another block or air retires the memory |
| `OUTCOME` | what a job did, and why it stopped | every finished plan | no — it is history, and it is never used as evidence |
| `PREFERENCE` | something the owner said he wanted | the owner | no — it is his word, and only he can contradict it |

The kind is the rule for how the entry may be checked, which is why the kinds are explicit rather than
one string: two of them are not facts about the world at all, and pretending otherwise would be the
"allowlist that means nothing happened when it misses" this project's audit is mostly about.

## Where it is written, and what it survives

`config/cobbie_memories.json`, keyed by owner, written as it changes (temp file + `ATOMIC_MOVE`, an
unreadable file moved aside rather than deleted — the same storage rules `config/cobbie_memory.json`
already follows).

Deliberately **not** in the entity's save data. A body that dies takes its save data with it, so a
memory stored there is memory for one life; keyed by owner, the next body knows what the last one
learned. Reload and dimension change are the easy half — the file is not the world's.

## Retrieval is scored, not dumped

An 8B model with forty irrelevant lines is worse than one with three good ones. Every entry is scored
against the instruction and the situation, and only the best fit into a stated budget:

```
score = 3 x words shared with the instruction (capped at 3)
      + 2 x the instruction's own verb points at this kind
      + 2 x the entry is in the dimension he is standing in
      + 1 x the entry is within 48 blocks of him
```

Ties go to the more recent memory. An entry with none of those is not shown at all. The block is capped
at **400 characters (about 100 tokens) and 5 lines**, and the prompt labels it as notes that must be
looked at before they are trusted.

The measured example, from `OperationalMemoryTest` — ten memories, one question:

```
rank for "put the iron into the chest":
  11  chest        (2 shared words, a container instruction, same dimension, nearby)
   4  barrel       (no shared word: a container instruction in the right dimension)
   3  anvil        (nothing shared at all: merely nearby)
```

The prompt for that instruction names the chest and its contents and does not mention the boat across
the map, the failed roof attempt, or the portal in the nether.

## The world wins

Every check has three outcomes and acts on each: **the same thing** (the memory stands), **something
else** (it is corrected in place, never duplicated), **nothing there** (it is dropped). A square that is
not loaded, or a memory from another dimension, is a question he cannot ask from here — not a
contradiction — so it is left alone rather than deleted for being out of sight.

Checks happen at both ends: the entries about to go into the prompt are verified first, and the whole
book is swept every 400 ticks so `/cobbie memory` stops listing a chest that was emptied an hour ago.

## The owner can see it and change it

- **`/cobbie memory`** lists what he knows, numbered, with where and how he came to know it:
  `1. home (place): at 12 64 -7 in overworld — you told me`.
- **`/cobbie forget <n>`** drops exactly that row.
- **chat, without a model**: `cobbie remember home is at 12 64 -7`, `cobbie remember my base is at 320 64 -11`,
  `cobbie remember the iron is in the barrel by the door`. With coordinates that is a `PLACE`; without,
  it is the owner's word kept as a `PREFERENCE` — never a fact about the world he has not checked.
- **chat, through the model**: the new `remember` verb carries `target` = what it is about, `data` = the
  sentence, and `x,y,z` when it is a place. Its evidence is `OwnAct`, because what it claims is that the
  owner said something, not that the world changed.
- A statement about the same thing **replaces** the old one: a correction is one row, not two.

The deterministic chat path exists because a correction that depends on a model call is a correction
that sometimes does not happen. It declines rather than guesses — a sentence it cannot read goes to the
model exactly as before, so a miss costs a model call and never a false "noted".

## Bounded

**24 entries per owner.** When a write would pass it: the oldest entry he learned from the world goes
first, and what the owner told him is dropped only when nothing else is left. A companion forgetting
where a chest was long before he forgets what you asked for is the right order.

## Evidence

| line from the run | what it proves |
|---|---|
| `remembered: 1. home (place): at 12 64 -7 in overworld — you told me` | stored from chat with no model, and the listing names the source |
| `corrected, not duplicated` | the owner's second statement overwrote the first; one row, new coordinates |
| `reload ok: 1. home (place): …` | the same memory, coordinates intact, after the world is saved and reopened |
| `forget ok: "Forgot number 1."` | `/cobbie forget <n>` drops exactly that row |
| `death ok: the next body still knows …` | the book is keyed by owner, so a body's death does not lose it |
| `container learned from the world: 1. chest (container): holds 30 iron_ingot at …` | 40 iron in the chest, a real `take` of 10, and the memory is what the slots said afterwards |
| `retrieved for "put the iron in the chest": …` | the live prompt carries the chest and its contents, within 400 chars and 5 lines |
| `corrected by the world: the chest now holds 35 iron_ingot` | the contents changed underneath him and the memory followed |
| `memory retired: the verb refused …, the iron stayed in his bag` | a memory of a chest that is gone is not evidence: `put` refused, nothing moved, the entry was dropped |
| `station learned from the world: anvil is chipped` / `station followed the world: chipped -> intact -> gone` | a block fact is read off the block, corrected when it is repaired, retired when it is gone |
| `rank for "put the iron into the chest": 11 chest, 4 barrel, 3 anvil` | the scoring rule, picking three of ten |

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | **325 tests, 0 failed, 6 skipped** (301 before this slice) |
| `runClientGameTest -PcobbieTests=MemoryRecall,MemoryWorld --no-build-cache --rerun-tasks` | **2 passed of 2** |
| `runClientGameTest --no-build-cache --rerun-tasks` (whole suite, exact pushed tree) | **80 passed, 2 failed of 82** — `CobbieGameTests` registers 82 classes, was 80 at 0.85.0 |
| the two reds | `CobbieSmokeTest` and `CobbieChatTest`, both "never reached a connected brain": nothing answers at 11434 on this machine, and the preflight said so before the run. They are the two brain-gated classes and fail for that reason alone, exactly as the 0.85.0 run recorded them |

Three things the run taught, recorded rather than papered over:

- **A chat line is not a command.** `cobbie remember …` dispatched through `performPrefixedCommand` is
  parsed as the `/cobbie` command with an unknown argument and never reaches the chat handler. The test
  now sends it through `mc.getConnection().sendChat`, which is the path a person uses — the first green
  I could have claimed here was measuring a command that does not exist.
- **`/cobbie memory` with nothing in it still contains the word "home"** (its own example sentence), so
  "the listing no longer mentions home" proved nothing. The assertion is on the numbered row being gone.
- **A take empties a slot, not a chest.** The correction half of the world test added five iron to a slot
  the take had already emptied, so the contents did not change and the memory correctly did not either.
  The test was wrong, not the memory.

## What could not be driven honestly

- **A dimension change in the same breath as the memory.** The store is not in the entity, so a portal
  cannot lose it; what the tests prove is the save/reload and death round trips. What is not driven is
  an actual walk through a portal and a recall on the other side.
- **A memory the brain chooses to use.** Model-gated and therefore not in the harness: the prompt now
  carries the remembered lines, and what the model does with them is measured by nothing in this repo.
- **The owner's own natural phrasing.** `cobbie remember …` is parsed without a model; "no, the home is
  at 3 64 9" is not, and reaches memory only if the model queues `remember` for it.
