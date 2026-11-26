# Cobbie 0.82.0 — he looks after his own body

Artifact: `cobbie-0.82.0.jar`.

Ten slices have taught him to *tell* the truth about a job. This one is about the body doing the job: he has
20 health, and until now nothing in the mod made him look after it. Combat exists and the pathfinder prices lava
and fire as though they were walls, but nothing stopped a plan from walking him into a fire, from digging the
block he was standing on into a shaft, or from standing in melee at three health until he died.

Four behaviours, all of them things a player notices inside an hour:

| | what he does now | what he used to do |
|---|---|---|
| fire, lava, cactus | steps out of it, four blocks at most, and says which | stood in it, kept walking where the plan said |
| the block under his feet | refuses the dig when the drop would kill him, and says the drop and the price | broke it and fell |
| badly hurt with a hostile engaging him | falls back to his owner, once, bounded | stood and traded blows until he died |
| a walk down a drop | will not path down a fall that costs more health than he has | inherited the game's comfort rule, which is exactly one block too deep |

## Both numbers are the game's

`world/Falls` is pure and unit-tested, and the damage function it is given is the game's own
`LivingEntity.calculateFallDamage` — fall distance, jump boost and the fall-damage-immune tag all inside it:

- a drop is measured to the **top** of whatever he would land on, because that is where his feet stop;
- a fall that costs **exactly** his health is fatal, because the game applies damage at zero health;
- water ends a fall without damage; lava and the void need no arithmetic at all.

The fourth behaviour turns out to be a one-block defect in the game itself. `Mob.getMaxFallDistance()` answers
`getComfortableFallDistance(health)` = `floor(health + 3)`, and at 20 health the game's own arithmetic makes a
23-block drop worth 20 damage — the last fall he would ever take. Every mob inherits it.

```
health 20:  deepest drop he will path down 22   (23 blocks costs 20 = his whole bar)
health  8:  deepest drop he will path down 10   (11 blocks costs 8)
```

So `CobbieEntity.getMaxFallDistance()` asks for the deepest fall that leaves him standing. It is the
pathfinder's own hook, so it covers following, wandering and every walk a plan builds, not just the one verb
somebody remembered to guard.

## Where each one lives, and why not somewhere else

- **The hazard escape is on the tick**, beside the wall rescue: a body can be pushed into fire by a piston, a
  creeper, a teleport or by the walk it was given — and the walk it was given is precisely the plan that must
  not be obeyed. The plan is cancelled with a reason, so the sentence names the hazard *and* the stopped work.
  It is asked of his **bounding box**, not of the two blocks his feet and head are centred in: a body 0.6 wide
  standing a block away from a cactus is not being hurt by it, and the first version of this rule teleported
  him away from cactus he was never touching.
- **The dig refusal is in `mineBlock`**, the one place the walked and the in-reach paths both end, and
  `canStartMining` asks the same question so a project is never announced that cannot take its first step.
- **The retreat is a bounded state**, not a verb: `RETREAT_TICKS` = 160, then a forty-second cooldown. What it
  interrupts is a step, not a plan. The melee goal and the defence goal both stand down while `isRetreating()`,
  which is the half that stops a retreat becoming a fight he turns back into.
- **The threshold is the owner's**: `retreat_health_fraction`, default `0.35`, and `0` turns the instinct off.

```
before:  9/20 health, a zombie engaging -> keeps swinging, dies at 0
after:   "i am badly hurt, so i am falling back to you"
         -> beside his owner on 9/20, the zombie 4 blocks behind him
```

## Evidence

One in-game class per behaviour, each reading the world rather than the sentence (registry entries **69–72**):

| class | the assertion |
|---|---|
| `CobbieHazardEscapeTest` | his body no longer intersects the fire block / the lava block / the cactus he was put into; he is alive at 12/20; the line names the hazard each time |
| `CobbieFatalDigTest` | the block under his feet over lava is **still stone** and the reason names the lava; the *same* nine-block drop is dug at 20 health (and cost him six) and refused at 4 health with the drop and his health in the reason, and he never left the platform |
| `CobbieFatalDropTest` | `getMaxFallDistance()` is 22 at 20 health and 10 at 8; a walk to a spot 23 blocks below fails naming the drop and what it costs; he never descended and never took the damage |
| `CobbieDisengageTest` | the same wound and the same zombie twice: switched off he loses health standing still (6 -> 3); at 0.5 he says he is falling back, keeps 9, and ends within reach of his owner |

`FallsTest` (unit, 11 tests) settles the arithmetic — the free three blocks, the distance to the top of the
landing, water, lava, the void, the fall costing exactly his health, and the safe drop shrinking with it — and
`BodySpaceTest` gained 4 for the same search with a hazard predicate, including the square whose *floor* is what
hurts.

## Verified

- `JAVA_HOME=… ./gradlew cleanTest test` — 301 tests, 0 failed, 6 skipped (was 286).
- `./gradlew runClientGameTest --no-build-cache --rerun-tasks` — **70 passed, 2 failed of 72**. The two are
  `CobbieSmokeTest` and `CobbieChatTest`, both of which refuse with *"never reached a connected brain"*; they
  fail identically on 0.81.0 in this checkout (measured, not assumed), because the run's `config/cobbie.json`
  points at a hosted provider with an empty key. Nothing in this slice touches the brain or the nameplate, and
  the two nameplate tests (`CobbieStatusTest`, `CobbiePanelTest`) pass.

## What this slice does not cover

- **A natural cliff over lava** is not built in the harness; the dig test builds the column by hand (platform,
  six blocks of air, lava) because a flat test world has no cliffs and a hand-built cliff is a sculpture of one.
  The question the code asks is the same either way — what is in the column below.
- **A retreat cannot make his health go up.** No mob regenerates in vanilla, so "higher after than before" is
  not a fact this world can produce; the assertion is the pair instead (with the instinct he finishes on the
  health he started the retreat with, without it he does not).
- **Hunger, eating and combat balance** stay out, as decided: he is robot-themed and has no hunger bar.
