# Cobbie 0.47.0 — riding a boat, and the thing I could not build

Artifact: `cobbie-0.47.0.jar`. **179 unit tests green** (185 counting the 6 opt-in live-provider
checks), 0 failures.

This closes travel. It is a small release with one deliberate hole in it, and the hole is the
interesting part.

## He rides; he does not drive

```
you: get in the boat
Cobbie: in the Oak Boat

[gametest] ride ok: boarded the boat, was a passenger and was carried 2.0 blocks, then got out when
           asked to follow
```

A `board` verb for boats and minecarts (`ride`, `get-in`, `sit`, `hop-in` are the aliases he will
recognise). He picks the nearest empty boat or minecart within ten blocks.

It is **riding, not driving**, and that is a finding rather than a shortcut. A fake actor cannot
board a boat at all:

```
[probe] boat boarded=false passengers=0
[probe] boat moved -1.00 blocks with the actor driving
```

`startRiding` from Fabric's `FakePlayer` returns false and the boat ends with **zero passengers**, so
there is nothing in the driver's seat to steer with, and the second probe shows the boat drifting
while the actor believed it was piloting it. **Steering is impossible by construction, not
unimplemented** — no amount of input plumbing fixes an empty seat. What he can honestly do is sit in
the craft and come along, which for a companion crossing a lake beside you is most of what matters.

## What is asserted

- **He is a passenger**, asked of the *craft's* passenger list, never of the return value of the call
  that put him there. A call that returns true over an empty boat is exactly the failure this slice
  exists to avoid.
- **The craft carries him.** The boat is moved two blocks and his own position is checked; a
  passenger who falls out on the first tick cannot be caught by a boolean.
- **He gets out.** Any other action dismounts him first. A companion who cannot leave a boat is stuck
  in the water for good, which is a worse bug than never boarding.

## Verification

**Unit:** 179 green, 6 skipped, 0 failures. **In game:** named in the pull request with the class
count, not a bare green summary — a suite that reports success over zero classes looks exactly like a
pass, and that happened in this project.

**Not verified:** minecarts. The verb covers them (same `AbstractMinecart` lookup, same boarding call)
but no run has put him in one. Minecarts need no driver, so the mechanism is the simpler of the two,
but until a test says otherwise that sentence stays a claim about the code and not about the game.
