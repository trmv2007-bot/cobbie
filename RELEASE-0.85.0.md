# Cobbie 0.85.0 — his own hunger bar, and he eats

Artifact: `cobbie-0.85.0.jar`.

He had no hunger for seventy-five versions. `docs/PLAYER-CAPABILITIES.md` said what a real bar would
cost — *"a reimplementation of `FoodData.tick`, not an API call"* — and asked whether a companion should
need feeding at all. Rishi decided on 2026-11-27 that he should, as a player has it. The rule this
codebase lives by applies to a bar as much as to a blockstate: **the game decides, the code does not
guess.** Not one nutrition figure, exhaustion constant, regeneration rate or starvation threshold in
this slice was typed from memory.

## The bar is vanilla's `FoodData`, ticked by vanilla's tick

`FoodData` is the game's class, so the *values* are the game's. The difficulty was the tick:
`FoodData.tick(ServerPlayer)` asks a player how hurt it is, heals it, and hurts it with starvation
damage. A `PathfinderMob` is not a player, and a mob adds no exhaustion at all.

So a second, dedicated actor was added: `Hunger.Actor` extends Fabric's `FakePlayer`, and every question
the game asks it about the body is answered by the companion. The inherited `foodData` **field** is
Cobbie's bar — not a copy — because `Player.canEat` and `Player.causeFoodExhaustion` read the field
directly. Health, `heal`, `hurtServer`, `isHurt` and the effect methods all forward to him. Its
creative-mode abilities are stripped, because `instabuild` would stop the stack shrinking and `mayfly`
would make the game's sprint rule answer yes at zero food.

What the game then produces, unchanged:

| what | where it comes from |
|---|---|
| exhaustion above 4 drains, saturation before food | `FoodData.tick`, 4.0 is `FoodConstants.EXHAUSTION_DROP` |
| saturated regeneration: `min(saturation, 6) / 6` health every 10 ticks at food 20 | `FoodData.tick`, 10 is `FoodConstants.HEALTH_TICK_COUNT_SATURATED` |
| slow regeneration: 1 health every 80 ticks at food 18+ | `FoodData.tick`, 80 is `FoodConstants.HEALTH_TICK_COUNT`, 18 is `HEAL_LEVEL` |
| starvation: 1 damage every 80 ticks at food 0, gated on health and difficulty | `FoodData.tick`, 0 is `STARVE_LEVEL` |
| no sprinting below 6 food | `Player.hasEnoughFoodToDoExhaustiveManeuvers`, `SPRINT_LEVEL` |

The four fields are written and read by `FoodData`'s own save methods under a `CobbieFood` child of his
entity data, so a reload round-trips the game's numbers rather than a copy of them.

## Exhaustion is what the game charges for the same action

Every source is the game's own method or a `FoodConstants` field, applied where the game applies it:

| he does | the game's path | the number |
|---|---|---|
| walks, sprints, swims | `ServerPlayer.checkMovementStatistics(dx, dy, dz)` on his actor | `EXHAUSTION_WALK` 0.0, `EXHAUSTION_SPRINT` 0.1, `EXHAUSTION_SWIM` 0.01 |
| jumps | `ServerPlayer.jumpFromGround()` on his actor | `EXHAUSTION_JUMP` 0.05, `EXHAUSTION_SPRINT_JUMP` 0.2 |
| breaks a block | `causeFoodExhaustion` with the number `Block.playerDestroy` charges | `EXHAUSTION_MINE` 0.005 |
| lands a hit | the number `Player.attack` charges, on a hit that landed | `EXHAUSTION_ATTACK` 0.1 |
| takes damage | `source.getFoodExhaustion()`, the same call `Player.hurtServer` makes | the damage type's own figure |
| heals from food | inside `FoodData.tick` | `EXHAUSTION_HEAL` 6.0 |

Walking costs zero in this version of the game (`EXHAUSTION_WALK = 0.0f`), which is why a companion who
only strolls never gets hungry — exactly as a player who only strolls never does.

## Eating and drinking go through the item

`ItemStack.use` starts the gesture on the actor and asks the game whether it is allowed
(`Consumable.canConsume` → `Player.canEat`); `finishUsingItem` ends it. Nothing in the mod knows what
bread is worth or what milk does:

- bread, cooked meat, a golden apple: nutrition and saturation from the item's own `FOOD` component;
- a bowl of stew and a bucket of milk come back because the item's own `USE_REMAINDER` says so;
- milk clears a harmful effect through the game's `ClearAllStatusEffectsConsumeEffect`, on his effects;
- a potion applies the game's own instant health, on his health;
- bread is refused at a full bar and a golden apple is not, because `canAlwaysEat` is the item's answer.

## He eats and drinks on his own

The policy is deliberately the only invented number in the slice, and it is named:

- **`EAT_AT = 14`** — at or below this many food points, once a second, he eats the **worst** food he
  carries (lowest `nutrition`, then lowest `saturation`), so steak is not spent at nineteen out of
  twenty. `18`, `6` and `0` in the game's own terms are `HEAL_LEVEL`, `SPRINT_LEVEL`, `STARVE_LEVEL`.
- **milk** when he has a harmful effect and carries it.
- **a healing potion** at or below `DRINK_AT_HEALTH = 8` health, if the potion's own effects include
  `MobEffects.INSTANT_HEALTH`.
- everything above still passes through the game's refusal: at a full bar the attempt makes no claim and
  spends nothing.

He says one line when he crosses into hungry and one when he crosses into starving, and nothing while he
stays there.

## What a person sees

- `/cobbie status` gains `Food: food 13/20, saturation 2.0, exhaustion 2.0`.
- Shift-right-click panel: the same line, under the job title.
- The brain's prompt says `hp 18.0/20, my food 13/20 (saturation 2.0), owner food 20, holding …`, and
  the world-knowledge block tells him he has a hunger bar and eats by himself — so he can say he is
  hungry and mean it.

## Evidence

`CobbieHungerTest` (registry 76). Every figure below is printed by the run and compared with the game in
the same run, never with a number written here:

| line from the run | what it proves |
|---|---|
| `sprint refused at food 6 and allowed at 7` | the game's own sprint predicate, through `FoodConstants.SPRINT_LEVEL` |
| `stew consumed, food +6, bowl returned` | the item's nutrition, the shrink, the `USE_REMAINDER` bowl |
| `one point of health in 10 ticks at full food (health 10 -> 11.0)` | `FoodData.tick`'s saturated regeneration, at `HEALTH_TICK_COUNT_SATURATED` |
| `starvation took one health in 90 ticks and he said he was starving` | `FoodData.tick`'s starvation damage at `STARVE_LEVEL`, and the spoken line |
| `bread raised food by its own nutrition (5) and shrank 3 -> 2` | nutrition read from `DataComponents.FOOD` in the same run |
| `full bar refused bread and allowed a golden apple` | `Consumable.canConsume` → `Player.canEat`, not a rule of mine |
| `bread (5) eaten before beef (8) at food 6` | worst-first policy, in the game's nutrition terms |
| `milk cleared poison and returned its bucket` | the game's `ClearAllStatusEffectsConsumeEffect` on his effects |
| `a healing potion raised his health and returned its bottle` | the potion's instant health on his body |
| `one broken block added 0.0050001144 exhaustion (the game's 0.005)` | `EXHAUSTION_MINE` across a real break, tied to the block being air |
| `"food 13/20, saturation 2.0, exhaustion 2.0" on the panel and in /cobbie status` | the bar is visible where a person looks |
| `persistence ok` (CobbiePersistenceTest) | 12 / 3.0 / 1.5 saved beside the bag and read back after a relog |

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | 301 tests, 0 failed |
| `runClientGameTest -PcobbieTests=Hunger --no-build-cache --rerun-tasks` | 1 passed of 1 |
| `runClientGameTest -PcobbieTests=Panel,Persistence,Status --no-build-cache --rerun-tasks` | 3 passed of 3 |
| `runClientGameTest --no-build-cache --rerun-tasks` (whole suite) | **74 passed, 2 failed of 76** — `CobbieGameTests` registers 76 classes, was 75 |
| the two reds | `CobbieSmokeTest` and `CobbieChatTest`, both "never reached a connected brain": no Ollama is answering at 11434 on this machine (preflight said so before the run), and both fail identically on pristine `master` for that reason |

## What could not be driven honestly

- **The 1.6-second use duration.** A player holds the button for `consumeTicks`; a companion's action is
  synchronous. The test starts the gesture with `ItemStack.use` (so the game's refusal is consulted) and
  immediately calls the game's own `finishUsingItem`. The outcome asserted is the one the game produces
  at the end of that duration — nutrition, effects, shrink, remainder — and the duration itself is not
  spent in ticks.
- **Sprinting.** He has no sprint drive of his own today; the game's rule is enforced at
  `setSprinting` and proved by the predicate at food 6 and 7. No visible sprint exists to refuse yet.
- **Starving to death.** The health gate and the damage are the game's, and both were measured; the
  test stops one point short of a kill because dying hands his bag over and the slice has nothing to say
  about a corpse besides the borrow he already had.
- **Eating mid-job.** The policy runs while he works, and the meal is synchronous, so he never pauses a
  step to eat. A player's interruption is not modelled and is not claimed.
