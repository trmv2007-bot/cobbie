# Cobbie 0.21.0 — he can use the world

Artifact: `cobbie-0.21.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and **20 of 24 in-game classes** — the four that failed are the ones that need a live model,
which this run directory has no key for.

Until now he could carry, mine, place, craft and store — but he could not *use* anything. Seeds,
bone meal, levers, buttons, buckets and shears are all implemented by methods that take a `Player` as
the actor, and Cobbie is a `PathfinderMob`, so the whole surface was closed. This release opens it.

## A player who is not there, for the length of one call

`FakeActor` stands Fabric's `FakePlayer` where Cobbie is standing, puts a stack in his hand, runs the
game's own `useItemOn` / `useItem`, and takes the stack back. That is the whole of it, and it is
deliberately the only place in the mod that pretends to be a player.

**Why Fabric's `FakePlayer` and not a hand-built `ServerPlayer`:** the jar's only constructor leaves
`connection` null, and `ServerPlayerGameMode.useItemOn` sends overlay messages and can open a
container menu. The first of those is a null-pointer crash in the middle of a world interaction. Fabric's
`FakePlayer` installs a do-nothing connection, returns an empty `OptionalInt` from `openMenu`, and
no-ops stats, sleeping, riding and sign editing — it exists for exactly this.

**What it costs, said plainly:**

- **Another mod will see him.** A `FakePlayer` is a real `ServerPlayer` on the event bus, so any mod
  listening to a use-block callback sees an actor it did not summon. His profile is `[Cobbie]` with a
  fixed UUID, so he is identifiable and he is always the same one.
- **Advancement triggers fire** for that profile. They live in memory and are never saved — he is not
  in the player list and writes no player data — but they are called.

## The `use` verb

```
you: use the seeds on that farmland
Cobbie: used minecraft:wheat_seeds
```

It raises the named item out of his bag if he is not already holding it, clicks the block on the face
a player would click, puts back whatever came of it — spent seeds leave his hand empty, a bucket leaves
the bucket, a tool comes back worn — and only reports success when the block or the hand actually
changed.

**Face matters and is not cosmetic.** Seeds plant on the face they are clicked against; the first run
of the test clicked the side of the farmland, the call reported success, and no crop appeared. He now
prefers the top face when there is air above and his eyes are over the block.

**Permission:** block use sits under `building_allowed` rather than a switch per verb — it is all
"change the world with your hands" — and the refusal names that switch.

## Verification — what was observed, and what was not

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped.

**In game (`./gradlew runClientGameTest`):** 20 of 24 classes. `StatusTest`, `PanelTest`, `SmokeTest`
and `ChatTest` fail on one precondition — the nameplate stays `§cCobbie (LLM offline)` because this
run directory has no provider key.

**`CobbieUseTest`, the class this release exists for:**

```
[gametest] use ok: sowed wheat on farmland, bone meal took age 0 -> 5; a throwing use left his bag
           and the actor's hand untouched; no phantom player data in the save
```

Three claims, each asserted rather than argued:

- **It works:** a wheat crop exists where airtight farmland was, and bone meal took its age from 0 to 5.
- **Nothing survives a call:** a deliberate `NullPointerException` inside the actor afterwards leaves
  the fake player's hand empty and his bag string byte-identical. The cleanup is a `finally`, so an
  `Error` cannot slip past it either.
- **No phantom in the save:** the test saves all players, then asserts `<profile-uuid>.dat` does not
  exist in the player data directory and the player list is length 1.

**Not verified, worth saying plainly:**

- **Only seeds and bone meal were exercised.** Buckets, flint and steel, shears, hoes, bows, potions
  and use-in-air all go through the same actor but no run has done any of them.
- **Levers and buttons are untested.** They need a bare hand on a block and are the next thing the
  actor should cover.
- **The actor has only ever run with this mod loaded.** The claim that another mod would see a
  phantom participant is from reading how Fabric's `FakePlayer` joins the event bus, not from running
  beside a mod that listens.
- **The face heuristic is tested on the top face only.** A wall-mounted button or a bucket against a
  side face would take the horizontal branch, which has no in-game evidence.
