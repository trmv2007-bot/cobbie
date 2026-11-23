# Cobbie 0.16.1 — the in-game check runs, and says what it saw

Artifact: `cobbie-0.16.1.jar`, installed into `mods/`. 109 unit tests green. **15 of 15 in-game
classes green** in 2m16s on the recorded run; one class is flaky for a reason that is not Cobbie (below). No gameplay changes: this release exists to make "it works in game" a claim
the harness can back up, and to fix the reason the last attempt could not.

## What actually stopped 0.16.0

Not a texture bug, and not Cobbie's renderer. It was VRAM, and the arithmetic is worth writing down
because it will bite again:

```
GPU: NVIDIA GeForce RTX 3050, 8192 MiB
Ollama holding lfm2.5:8b resident:  6,411,434,065 B  ≈ 6.2 GiB
   (8.5B params at Q4_K_M, loaded with a 128k context — the KV cache is most of it)
free before anything launches:                                529 MiB
```

Ollama keeps the model on the card between requests. A live Minecraft client takes what is left of an
8 GB card, so the second client — the game-test one — ran out of VRAM part-way through allocating its
texture atlases and died there. It reads like an allocation bug inside a renderer because that is
where the memory ran out, not because a renderer was involved.

Measured rather than guessed: asking Ollama to unload the model moved free VRAM from **569 MiB to
6,744 MiB** in about four seconds. That is the whole collision.

## What the harness does about it now

`./gradlew runClientGameTest` runs a preflight before the client boots
(`tools/gametest-preflight.ps1`, readable and runnable on its own):

- **Refuses while a Minecraft client is already running**, naming the process and its pid. That
  session is yours; a test run does not get to kill it and cannot share the card with it.
- **Unloads whatever Ollama has resident on the GPU** (`keep_alive: 0`, an ordinary client call). Your
  next chat reloads the model; leaving it resident costs the whole test run.
- **Refuses unless the card actually has headroom**, printing free and total before it starts, so a
  red run is never again an out-of-memory dressed as a code defect.
- **Clears last run's worlds** from `build/run/clientGameTest/saves`, so every invocation starts from
  nothing. The rest of that directory stays: `downloads/` in it is the 1.7 GB asset cache, which is
  why `clearRunDirectory` is `false` in the first place.

Escape hatch if the guard is ever wrong about your machine: `-PskipGametestPreflight`.

## One failure no longer hides fourteen results

Fabric's client-test runner lets the first class that throws take the client process down with it, so
fifteen registered classes gave at most one result per launch and everything behind a failure never
ran. The suite now has a single entrypoint that runs each class behind a try/catch, prints one line
per class with its duration, prints a summary, and only then throws — the process still exits
non-zero, but a red class costs one line instead of the rest of the run.

```
[gametest] CobbieCapabilityTest ok (6034ms)
...
[gametest] summary: 15 passed, 0 failed of 15 — {CobbieCapabilityTest=ok, … CobbieChatTest=ok}
BUILD SUCCESSFUL in 2m 16s
```

Run one class while iterating: `./gradlew runClientGameTest -PcobbieTests=Status` (comma-separated
for several).

## One class is still flaky, and it is the model's fault

`CobbieChatTest` is the only in-game class that needs the local model to actually answer. Across four
launches of this harness it passed three times and failed once, from inside a full 15-class run:

```
CobbieChatTest FAILED after 93103ms: No reply reached memory within 90s;
brain state=REQUEST_FAILED detail=model returned no text
```

That is `lfm2.5:8b` returning an empty completion on both attempts — the brain reported the failure and
told the player, so he was not silent, but no reply reached memory and the assertion is right to
object. Alone, the same class passes in 25s with a real answer. So: **the harness is repeatable, one
class's result is not**, and the honest number is 15/15 on a good run and 14/15 when the model hiccups.
Making the test retry would hide a real never-silent regression, so it does not retry.

## Verified in game — the tag and the readout
`CobbieStatusTest`, observed passing inside a real Minecraft 26.3 client:

```
[gametest] status ok: idle="following you" working="working"
  shift=Success[...] plain=Pass[]
  report=[Right now: mining
        | Job: mine that stone [executing]
        |  1. mine minecraft:stone @0 -60 10 - running
        | Carrying: minecraft:iron_pickaxe x1]
```

`CobbieSmokeTest` printed the nameplate component itself, which is the tag above his head:

```
cobbie alive at BlockPos{x=162, y=73, z=162} name=literal{§eCobbie §8· §7following you}
screenshot=…\screenshots\0004_cobbie_smoke_spawned.png
```

What that establishes, item by item:

- Before anyone speaks to him the tag reads **`following you`**, not `thinking…`.
- While the job is executing it reads **`working`** — from the verb in flight, not from chat.
- The nameplate carries the **`·`** suffix above his head, asserted on the entity's own custom name.
- The readout has the current activity, the job's name and status, each step with its target and
  coordinates and its state, and what he is carrying.
- Shifting is what turns a right-click into that readout: shift-right-click returns `SUCCESS`, a
  plain right-click does not.

Two things in the test itself changed, and both are honest fixes rather than loosened assertions:

- It now waits for **no model request in flight** before asserting he is not thinking. The counter
  behind `thinking…` is process-wide and shared by every class in one run, and a local 8B model takes
  seconds to answer, so an earlier class can still have its reply out when this one starts — which is
  exactly how the previous run went red on a `statusWord()` that was telling the truth. If the counter
  ever *fails* to return to zero the test now fails naming it, so a genuinely stuck request cannot
  hide behind the wait.
- It exercises the gesture, not only the string, and asserts the readout has coordinates and a step
  state without pinning which state — catching a step at `pending` is as correct as catching it at
  `running`.

## Not verified

- **The rendered chat line.** The test drives the same server-side call the interaction packet lands
  on and asserts its result and its content; it does not read pixels or chat history to prove the line
  rendered in the HUD. The screenshot is kept as an artefact, not asserted on.
- **His behaviour with a human at the keyboard.** Everything here is a scripted client at 854×480.
- **Nothing about the brain, the pathfinder or the entity changed** in this release, so 0.15.0's
  caveats stand unchanged: plan quality on long jobs is unproven, and death drops onto the ground
  remain broken (his bag goes to the owner).

## Install

Installed here, with Minecraft closed, so the old jar was not locked:

```bash
cp build/libs/cobbie-0.16.1.jar "$APPDATA/.minecraft/mods/"
```

There was no earlier cobbie jar in `mods/` to replace — 0.16.0 was built but never copied in, which is
consistent with what 0.16.0's note said. `fabric.mod.json` inside the installed jar reports
`"version":"0.16.1"`.

## Still open

- The preflight is Windows-only; on other hosts it logs that it is skipping the VRAM guard.
- The startup log still prints `Cobbie 0.5.0 ready - say his name` while loading 0.16.1 — a hardcoded
  string from an early build. It does not affect behaviour, but it is a lie in exactly the place a
  verification run reads evidence from, so it should be the next thing fixed.
- `./gradlew` needs `JAVA_HOME` to point at a JDK 25. This machine's `JAVA_HOME` names a directory
  that no longer exists (`C:\Users\rishi\jdk\jdk-21.0.12.1+1`), so the wrapper only starts with it set
  per invocation: `JAVA_HOME="C:/Users/rishi/jdk25" ./gradlew …`.
