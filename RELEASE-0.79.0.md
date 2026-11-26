# Cobbie 0.79.0 — every verb tells the truth, and no retry is unbounded

Artifact: `cobbie-0.79.0.jar`.

Nine slices have each fixed one verb's honest ending, and every one was found by a probe or a run. This is
the slice that stops patching verbs one at a time and asks the whole question: **for every verb he can
execute, what does he claim, and what is that claim checked against?** The answer is in
`docs/AUDIT.md` as a table, and it is where the next false success was hiding.

The count is the spine of it:

| | verbs |
|---|---|
| registered in `ActionVocabulary` | **36** |
| that make no claim about the world (`answer`, `chat`, `wave`, `follow`, `stay`, `explore`, `wait`, `lookup`) | 8 |
| that change or read the world | **28** |
| whose success is **read back off the world** after the change | **17** |
| whose success is taken from **a call's own return value** and never re-read | **11** |

Seventeen and eleven, not twenty-eight and zero. The eleven are not dishonest — a menu answering "yes" is
real evidence — but they are a different *grade* of evidence, and the difference is what decides which verb
misreports next. A station's menu returning a result is a fact about that menu. The bag afterwards is a fact
about him.

## The defect the table found first: arriving is not writing

`startWrite` walks him into range when the sign is further away than his arm. The hand-over at the end of
that walk, `performNow`, had **no case for `write`** — so the walked write fell through to a `default` arm
that answered `ok(verb, "arrived")`. Arriving is where a verb starts.

```
before:  write "coal for sale" on a sign 8 blocks away -> COMPLETED — all done      (sign: blank)
after:   write "coal for sale" on a sign 8 blocks away -> COMPLETED — wrote "coal for sale" on the sign
```

A plan completed, the narrator said "all done", and the sign was blank — the exact failure this project
exists to prevent, in a verb nobody had driven at range. The fix is a `WRITE` case that re-reads the sign and
writes it, and a `default` arm that **fails**:

```java
default -> {
    clearActive();
    yield ActionOutcome.fail(verb, "i got there but do not know how to " + verb);
}
```

A verb that walks to its work and has no arm here is a verb whose work nobody wrote. The old default turned
that gap into a success, which is the allowlist-miss family inverted: a miss that says *done*.

## The second: a count taken from a call nobody read

`farm` incremented `harvested` directly after `level().destroyBlock(...)`, discarding its boolean. A world
that refuses the break still incremented the count, so the sentence said "harvested 2" over two crops still
standing. It continues on a false return now, and counts a replant only when the young crop is really in the
world.

## The third, found by the test rather than by reading: the closing line said nothing

The endings test asks, for every refusal, that he **said** the reason. Eight refusals in a row and only the
first one's reason survived:

```
said: [that didn't work — i need an enchanting table and there is none near me,
       partly done — 1 I couldn't manage,
       partly done — 1 I couldn't manage, ...]
```

The reason existed — the narrator had it — but the narrator is paced (`chat_cooldown_ms`, 900 ms) and drops
anything offered inside its cooldown, and a *retried* step offers two failures a tick apart, so the second is
always dropped. What the owner heard for every job after the first was "partly done — 1 I couldn't manage",
which names nothing. `Narrator.jobLine` now carries the reason, scrubbed of coordinates like every other
spoken line:

```
partly done — 1 I couldn't manage — i need an enchanting table and there is none near me
```

## Three more, from a second opinion's list, verified first

A model read the public repository at 0.78.0 and returned five ranked findings. Two were the defects above —
it found the write and the farm independently, from source. The other three were checked against the code
before anything was changed, and two and a half of them were real:

**A handler that throws used to leave him unusable.** `begin()` answers `"still busy with " + active.action()`
whenever a step is in flight, and only `interrupt()` clears that state. `TaskRunner.tick` catches what a
handler throws and fails the *plan*, so the plan always ended — but the entity's `active`, `activeTarget`,
`workTarget` and walk counters were left set, and every later instruction was answered "still busy with
<verb>" until the game was reloaded. He was present, alive, and could not be asked to do anything. Both entry
points are wrapped now: a throw interrupts the step and reports
`something went wrong on my side (RuntimeException)`.

**A made item could exist nowhere and still be reported as made.** Ten sites — repair, dye, enchant, anvil,
brew, smith, grind, weave, map, trade — tried his bag and then dropped what would not fit, discarding whether
the drop landed. A world that refused the entity meant the stack was gone while the sentence said "mended two
diamond swords into one". `craft`'s own store-or-drop already checked, which is why this was ten sites and
not eleven; one helper now answers "in his bag or on the ground", and a false answer ends the verb as a
failure instead of a success.

**And one correction, because the audit record matters more than the list.** The claim that
`resolveDescribed()` lets him "equip or use a different item from the one described" is not what the code
did: `slotOf()` searched bag slots only, so a description naming the thing already *in his hand* refused with
*"I lost track of my sword"* about the sword in his hand — a wrong sentence, not a wrong item. The real
hazard is the other half of the same method, and it is the stronger one: `slotOf` matched on **id, count and
damage**, so two diamond swords of the same count and damage answered to each other's description, and "my
sharpest sword" could take the Sharpness I. Both are fixed — the match is the whole six-field identity now,
and the hand is a known place rather than an unknown one.

**The one thing not fixed, on purpose.** `TaskRunner.settle()` marks any `success == true` outcome DONE
(`TaskRunner.java:509`), so "the world agrees" is still a convention each handler keeps by hand rather than a
property the executor enforces — which is exactly how both of this slice's defects were possible. The fix is
an `ActionOutcome` that cannot be constructed without saying what its claim rests on: 254 call sites, plus a
per-family answer to "what counts as evidence here". That is the next slice, not an appendix to this one;
half of it would give two definitions of success.

## Bounds: the reset that was not a bound

Every retry here is a count. `TaskRunner` bounds a step at `maxRetriesPerStep` (+1 attempt), a place at one
re-plan, a plan at `planStepLimit` steps and a wall-clock deadline, and its tick loop at
`steps × (retries + 2) + 8`. A walk is bounded at three re-hands to the navigator.

One exception: the climb assist. When the navigator came back empty with a climbable beside him and the goal
above, it **reset `walkRepaths` to zero** — handing the same three tries out again, for ever, bounded only by
the 60-second step deadline. A bound that resets itself when it is used is not a bound, and from outside it
looks exactly like one. It has its own budget now (`MAX_CLIMB_ASSISTS`), and the failure line says when the
climb is what he could not do.

Each of those numbers is asserted rather than described. `BoundsTest` (7 unit tests) counts hand-offs to the
world: exactly `budget + 1` for a step that always fails, one attempt at a budget of zero, `3 × 3` for three
steps at a budget of two, **a re-plan that does not hand the step a fresh budget**, a broken step handed over
zero times, a negative budget clamped to none, and a step the world never answers ended at the deadline with
the step interrupted. The in-game class asserts the same count where it is observable: a `place` with no
material is tried **exactly twice** and the square is still empty.

## Evidence: one induced failure per verb

`CobbieEndingsTest` — registry entry 66 — drives a deliberate failure for each verb and accepts exactly three
endings: **it succeeded anyway** (verified against the world, never against the report), **it recovered once
and succeeded** (proven in `CobbieStalePlanTest` case 3, not duplicated), or **it stopped and named what
stopped it** (the reason in the plan record *and* in what he said). The fourth ending — success over work the
world does not have — throws.

```
[endings] enchant with no enchanting table: FAILED — "enchanting table"
[endings] anvil with no anvil: FAILED — "anvil"
[endings] smith with no smithing table: FAILED — "smithing table"
[endings] grind with no grindstone: FAILED — "grindstone"
[endings] weave with no loom: FAILED — "loom"
[endings] map with no cartography table: FAILED — "cartography table"
[endings] brew with no brewing stand: FAILED — "brewing stand"
[endings] drop a diamond he has not got: FAILED — "diamond"
[endings] give a diamond he has not got: FAILED — "diamond"
[endings] equip a pickaxe he has not got: FAILED — "diamond_pickaxe"
[endings] dye with no purple dye: FAILED — "purple"
[endings] mend with only one of a thing: FAILED — "two"
[endings] use shears he has not got: FAILED — "shears"
[endings] take from an empty square: FAILED — "nothing to take"
[endings] put into an empty square: FAILED — "nothing to put things"
[endings] board a boat that is not there: FAILED — "no boat or minecart"
[endings] trade with no villager: FAILED — "no villager"
[endings] breed with no cows: FAILED — "cow"
[endings] collect at an empty square: FAILED — "nothing to pick up"
[endings] farm with no crops: FAILED — "no crops"
[endings] attack with nothing to fight: FAILED — "nothing i am allowed to fight"
[endings] smelt at a square that is not a furnace: FAILED — "no furnace"
[endings] an unsatisfiable craft: refused, no plan — "I can't make that — I have no recipe for
           minecraft:oak_log and none in my bag"
[endings] mine a square that is already empty: completed, and the world shows the work
[endings] write to a sign he walked to: completed, and the world shows the work
[endings] write to a sign broken mid-walk: FAILED — "sign"
[endings] a step that fails every time: FAILED — "dirt"
[endings] a step that fails every time: tried 2 time(s) against a budget of 2, then FAILED
[endings] farm with nothing ripe: FAILED — "ripe"
[endings] farm: COMPLETED — farm: harvested 2 ripe wheat and had no seed to replant with
[endings] done: 26 verbs driven to failure (enchant, anvil, smith, grind, weave, map, brew, drop, give,
          equip, dye, repair, use, take, put, board, trade, breed, collect, farm, attack, smelt, craft, mine,
          write, place), every ending either the world's work or a reason naming what stopped it
```

Two of the lines are the ones that matter most, and they are opposite answers to the same question about the
same verb. **`write` to a sign he walked to** is a completion checked against the sign's own text. **`write`
to a sign broken mid-walk** is a stop naming the sign, with the sign really gone — the fixture asserts that
it broke, so the case cannot pass by inducing nothing.

Two of the 28 world verbs are driven elsewhere rather than omitted: `walk-to` by `CobbieStalePlanTest` case 1
(destination filled in before the step) and `light` by that class's case 3 (`place` with the block rewritten
to a torch). The eight verbs that make no world claim are not applicable — there is no world fact for them to
disagree with.

**What cannot be driven, and is named rather than hidden:** the `farm` false-return path needs a world that
refuses a break, which a vanilla flat world has no way to do; the climb-assist bound needs a navigator to
fail repeatedly beside a climbable, which a flat world does not produce. `docs/AUDIT.md` says both, and says
that `MAX_CLIMB_ASSISTS` is asserted by nothing that runs.

## Arithmetic versus observed

- **Arithmetic:** unit tier `cleanTest test` — **267 tests, 0 failed, 6 skipped, 31 classes** (259 before
  this slice, +7 in `BoundsTest`, +1 in `TaskRunnerNarrationTest`). One run mid-slice was red on
  `SapiVoiceEngineTest` — *"synthesis took 6845 ms"* against its cap, on a box holding a Gradle daemon and a
  game-test client — and green on the two runs since; the message is a measured duration rather than a
  constant, so that red was load.
- **Arithmetic:** in-game registry `--no-build-cache --rerun-tasks`, whole run — **64 passed, 2 failed of
  66**. The registry count is **66**, counted from `CobbieGameTests`: 65 before this slice plus
  `CobbieEndingsTest`. The two reds are `CobbieSmokeTest` and `CobbieChatTest`, which report *"Cobbie never
  reached a connected brain"* — the model, not the code.
- **One flake, named:** an earlier full run (before two of the fixes below) was 62 of 66, and
  `CobbieAnvilEndTest` failed there with *"I need an anvil and there is none near me"* on its second case. It
  passed alone, and it passed in the final full run. I could not reproduce the anvil's disappearance and I am
  not claiming to have fixed it; it is recorded here rather than quietly dropped.
- **Observed:** every `[endings]` line above is copied from a run log, and so is the "said" window that found
  the closing-line defect.
- **Not verified:** the two brain-gated classes (`Smoke`, `Chat`) need a model and are red in every run
  tonight; nothing in this slice touches them.
