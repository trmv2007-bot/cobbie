# Cobbie 0.16.0 — a tag that says what he is doing, and shift-right-click for his plan

Artifact: `cobbie-0.16.0.jar` (built; install step below). 109 unit tests green.

## Above his head
The nameplate now carries his activity:

```
Cobbie · mining
Cobbie · walking
Cobbie · thinking…
Cobbie · picking up
Cobbie · building
Cobbie · fighting
Cobbie · following you
Cobbie · idle
```

It is derived from what he is actually executing — the verb in flight, whether the model is thinking,
whether a plan is running — not from what he says. Refreshed every 10 ticks (twice a second), and it
leaves the connection states alone: "Connecting…" and "(LLM offline)" still read as they did, because
those are the spawn/connection code's business.

## Shift-right-click for the plan
Holding shift and right-clicking him answers in chat with:

- what he is doing right now
- the job's name and status
- every step, its target, its coordinates, and whether it is done / running / pending / failed
- how many jobs are queued behind it
- what he is carrying

## Verification status — honest
The automated check (`CobbieStatusTest`) could not complete: your Minecraft client was running, and the
second client died allocating textures on the same GPU. The code compiles and the logic is small, but
this is **not yet an observed-in-game claim** and the release says so. It will be verified as soon as a
test client can run alone.

## Install
The old jar is locked while the game runs, so 0.16.0 is built but not yet in place:

```bash
# with Minecraft closed
rm -f "$APPDATA/.minecraft/mods/cobbie-0.15.0.jar"
cp build/libs/cobbie-0.16.0.jar "$APPDATA/.minecraft/mods/"
```
