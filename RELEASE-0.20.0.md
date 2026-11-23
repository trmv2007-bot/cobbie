# Cobbie 0.20.0 — a real bag

Artifact: `cobbie-0.20.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the in-game client classes green except the three that need a live model.

0.19.0 gave him containers and a furnace. Nine slots then became the thing standing between him and
them: "empty that chest into your bag" landed on the floor after nine stacks, and the 30-second
blacklist on items he fails to pick up made the pile look like a bug of its own. This is the bag.

## 45 slots

`inventory_size` now defaults to **45** — four inventory rows plus a hotbar's worth — instead of 9.

**Carrying capacity is player parity; selection is not.** A hotbar is a control with nothing wired to
it until item use exists, and the offhand is shields and totems, which is combat depth in phase 3.
So there is no hotbar and no offhand here, deliberately, and the held item is marked in the panel
until phase 2 gives selection a meaning.

## The save is the risk, and both directions are handled

An existing world has his bag saved as one list of stacks, written when that list was nine long.

- **Growing** 9 → 45: the old stacks land in the same slots and the rest are empty. Nothing moves.
- **Shrinking** (a lower `inventory_size` than the save was written with): the stacks past the last
  slot used to be deleted on load. They now go on the ground at his feet, and the log names exactly
  what moved:

```
Cobbie's bag holds 45 slots but that save has more: 37 (12x minecraft:cobblestone, 25x minecraft:oak_log)
will be put on the ground beside him rather than lost
```

## The panel tells the truth about it

The panel lays its grid out from the number of slots he actually has, so 45 slots draw as five rows
instead of one, and the slot holding what is in his hand is outlined in amber. Without that, a
companion mining with a pickaxe he had raised out of the bag looked like he was carrying nothing.

## The ring is a test now

`CobbieContainerTest` grew a third stage: forty cobblestone taken out of a chest in one request, and
the test fails by name if a single item lands on the ground. That is the regression this release
exists to prevent, so it is asserted rather than assumed.

## Verification — what was observed, and what was not

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game (`./gradlew runClientGameTest`):** per-class results are in the pull request.

**Not verified, and this one could still lose items:** the shrink path has never been run against a
real save. It is load-time code plus a log line, and exercising it means writing a world with a bigger
bag and lowering the config between save and load. That is a gametest I can write, and it is the next
thing on my list rather than a limitation to live with — but until it exists, this release note says
the path is unproven.

Also unverified: 45 slots have never been filled, so the five-row panel is drawn but has not been seen
with a full bag.
