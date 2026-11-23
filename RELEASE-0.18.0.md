# Cobbie 0.18.0 — presence: he does it, then tells you about it

Artifact: `cobbie-0.18.0.jar`. 167 unit tests green (161 hermetic plus the 6 opt-in live-provider
checks), and the in-game client suite green against a hosted model.

Five things Rishi said after playing 0.17.0, and what each one turned into:

| What he said | What was actually wrong | What 0.18.0 does |
|---|---|---|
| "instead of saying thinking all the time, keep him on idle" | the tag counted any queued thought as him thinking, and every finished job queued one | the body wins; `idle` means idle |
| "let him msg what he is doing, and then let him do it" | the whole plan was announced before any of it happened | one short line, then the step, then a line about how it went |
| "he is taking too much time to do anything" | two model round trips per instruction: one to plan it, one to comment on the result | one round trip; the result line is generated, not asked for |
| "make a gui of him and his inventory" | shift-right-click pasted a 260-character chat dump | a real screen with his model, his job and his bag |
| "dont need him to msg where he is going and what cord he is on" | narration was his own bookkeeping: coordinates and step logs | he says what he sees: the mobs, the water, the place, the sky |

## The tag stops lying

`statusWord()` checked the in-flight counter first, and the counter is process-wide. So a companion
standing perfectly still read `thinking…` for as long as any request was out — including the one he
made after every job just to narrate it. And because "following" was a mode rather than a movement,
a companion standing beside you on follow said `following you` while doing nothing at all.

Now, in order:

```
an active step   -> mining / walking / building / fighting / lighting / picking up / ...
a running job    -> working
a request out    -> thinking…        (only while he is genuinely waiting on an answer)
walking to you   -> following you    (only while the navigator is actually moving him)
nothing at all   -> idle
```

`Connecting…` and `(LLM offline)` are untouched. The rule the tag now obeys is one sentence: nothing
in flight and nothing executing means `idle`.

## Act, then speak

A reply that contains work is cut to its first sentence — "On it." — and the work starts. After
that, every step is narrated at its two edges: one line as it begins, one line when it settles, and
one line when the job is done. A new line replaces an unsaid older one, and the gap between lines is
`chat_cooldown_ms`, so a five-step job that finishes in two seconds produces two lines, not ten.

What he does not do any more is read out his own log:

| 0.17.0 | 0.18.0 |
|---|---|
| `result - failed — put two blocks down and dig in the middle: 2/3 steps done, 1 failed, 1 retries. Details: place minecraft:dirt [done) x1 — placed minecraft:dirt at 3 -60 0 \| mine minecraft:stone [failed) x2 — there is nothing at 0 -40 0 \| …` | `going for that stone` … `got it` … `all done — 2 things sorted` |

Observed in the game runs, verbatim: a step that needed walking said `going for that stone` first and
then `got it`; a step he could already reach said only `got it`, because the start line was replaced
inside the same tick before it was ever spoken. One line per step, never two about the same step.

The full report still goes to memory and to skill learning, where a coordinate is worth keeping. A
test asserts that no line he says contains a position, in any of the shapes the game and the planner
print one. The same rule caught one more leak while the suite ran: his dying line said
`I died at BlockPos{x=2, y=-60, z=2}`; it now says what a player actually needs to hear, which is
whether their things survived.

## What he sees instead of where he is

New `Sight`, built on the same world snapshot the model reads, produces one short observation in
Cobbie's voice: hostiles first and counted ("two zombies close by", "a creeper right on top of me"),
then a landmark worth remarking on (lava, water, gravel, an ore, a spruce tree), then the place
("spruce woods", "the badlands", "a riverbank"), then the sky ("light is going", "rain is coming
down", "there is a storm coming down"). With nothing else to do he offers one every 60 seconds —
exploration and idling both feed it, and it never claims a biome or a mob it has not actually
perceived.

The prompt says the same thing, once, in his own instructions: describe what you see, never read out
coordinates.

## Shift-right-click is a screen

`mobInteract` used to build a chat string. The server now sends a small payload — entity id, status,
the job, its steps with their states, and the real contents of his bag over the inventory stream
codec — and the client opens `CobbiePanelScreen`:

- his actual model, drawn with the vanilla inventory helper, turning toward the cursor;
- his name and a status pill that says what the tag says;
- the job and each step, with a colour per state (running / done / failed / skipped);
- his bag as a 9-wide slot grid with real item icons, stack counts and hover tooltips — the held
  tool included, because it is raised out of the container and would otherwise look like an empty
  hand while mining.

A client that cannot receive the packet (a dedicated server whose client has no mod) still gets the
old chat readout, so the gesture never goes silent. The screen does not pause the world and closes
on Done or Esc.

## The speed, measured

Instrumentation went in first, on 0.17.0's behaviour, and every number below is from a real client
run on this PC with the hosted gateway, logged as one `[timing]` line per instruction. Same machine,
same config, same day.

| Stage | 0.17.0 | 0.18.0 | Whose it is |
|---|---|---|---|
| chat arrives → request sent (`chat_batch_ms`) | 350 ms | 350 ms | config, untouched |
| request sent → model answered | 13 600 ms (first run), 2 027 ms (second) | 2 027 ms | the gateway, not the mod |
| answer → first action begins | ≤ 50 ms | ≤ 50 ms | one entity tick |
| first action → job settled (2 steps) | 1 798 ms | 1 799 ms | the navigator and the world |
| job settled → his line about it | one line at once, **plus a second model round trip** for the model's own comment | 0 ms, one line | this release |
| model calls per instruction | **2** | **1** (a second only after a job that failed part way) | this release |

So: the biggest term by far is the provider answering (2 s when the gateway is healthy, 13.6 s in the
run ten minutes earlier when it was not), and that is not something this release touches. The term
that was ours to remove was the second round trip per instruction, which is gone — the closing line
is now generated from the plan that actually ran, so it costs nothing and cannot contradict itself.
The permanent numbers in the table are the ones the tests can hold still: 400 ms of batch window,
2 027 ms of provider, ≤50 ms to the first movement, 1 799 ms of work, 0 ms to the closing line, one
call.

One thing to know about these numbers, because it is not a code fault: a `config/cobbie.json`
written by an older version keeps its own values until somebody presses **Save** in `/cobbie config`.
A long `read_timeout` from an older config turns a dead or throttled gateway into a five-minute hang
instead of a fast failure — 0.18.0's default is 30 seconds, and nothing in the mod rewrites a file it
did not create.

## Verification — what was observed, and what was not

**Unit (`./gradlew test`):** 167 tests green, 6 skipped (the opt-in live-provider checks). New:
`NarratorTest` (step phrasing, one-line-per-step cadence, the cooldown gap, coordinate scrubbing,
first-sentence cutting), `SightTest` (hostiles counted and ranked, landmarks, place and sky, an
unknown biome never invented, no coordinates, a broken snapshot still yields a sentence),
`ActionTimingTest` (the stage arithmetic and the two-call case), and `TaskRunnerNarrationTest` (both
edges of every step narrated, a retried step announced again but settled once, a throwing narrator
cannot stop the work, and the closing line for completed and cancelled jobs).

**In game (`./gradlew runClientGameTest`):** all 16 client classes green — the fifteen that existed
plus a new `CobbiePanelTest`:

```
[gametest] summary: 16 passed, 0 failed of 16

[gametest] status ok: idle="idle" working="working" shift=Success plain=Pass
  report=[Right now: mining | Job: mine that stone [executing] | 1. mine minecraft:stone @0 -60 10 - running | Carrying: minecraft:iron_pickaxe x1]
[gametest] panel ok: status="working" steps=[mining stone] items=[minecraft:iron_pickaxe, minecraft:dirt, minecraft:torch] idle-tag=idle
[gametest] smoke: cobbie alive at BlockPos{x=162, y=76, z=162} name=literal{§eCobbie §8· §7idle}
```

Two notes on the tag, both from that log: `idle` where 0.17.0 said `following you` for a companion
standing still, and the nameplate carrying the same word above his head.

`CobbiePanelTest` spawns him, gives him an iron pickaxe and eight torches, starts a mining job
through `offerPlan`, and then drives the real shift-right-click: the click must return SUCCESS,
the client screen must be `CobbiePanelScreen`, its status must be non-blank, its step list must
describe the job, its bag must draw the pickaxe and the torches. A plain right-click must still not
open it. Then the job is cancelled and the tag must read `idle` — both in `statusWord()` and in the
nameplate string. Finally every line the client received from him is checked: at least two of them
while working, and none containing a coordinate.

Two screenshots come out of that test: `cobbie-panel.png` (the panel over a live world) and
`cobbie-idle-tag.png` (the nameplate reading `§eCobbie §8· §7idle` above his head).

Two things this run needed, said plainly:

- The first in-game attempt crashed the client on a bug of mine, not a mystery: the payload type was
  registered as `"cobbie:panel"` through `CustomPacketPayload.createType`, which treats its argument
  as a *path* and prefixed `minecraft:` to it. It is a namespaced `Identifier` now.
- The second attempt died the same way on a different error of mine: the panel asked for
  `extractBackground` on a frame that had already blurred the world, and the game allows one blur per
  frame. The screen draws on the blurred frame now.

**Not verified, and worth saying plainly:**

- **The Groq, OpenAI and OpenRouter presets are still untested** — there is no key for any of them on
  this machine. Unchanged from 0.17.0.
- **The dedicated-server fallback was not exercised.** The chat readout is only reached when
  `ServerPlayNetworking.canSend` says the client cannot take the payload, and the game test has a
  client that can. The code path is the old, already-tested `describeWork()`; what is untested is the
  choice between the two.
- **The narration cadence was observed in a game test, not by playing.** The lines above are what the
  client actually received; how it feels over a twenty-minute session is a thing only Rishi can
  judge, and `chat_cooldown_ms` is the knob if it is still too talkative.
- **Sight was not driven against real hostile mobs.** The game test runs in a peaceful flat world, so
  the mob half of the observations is unit-tested against snapshots rather than observed in game.

## Install

```bash
# with Minecraft closed
rm -f "$APPDATA/.minecraft/mods/cobbie-0.17.0.jar"
cp build/libs/cobbie-0.18.0.jar "$APPDATA/.minecraft/mods/"
```

Then, in game: shift-right-click him to see the panel; `/cobbie config` → **Save** to adopt the 800
token and 30 second defaults.
