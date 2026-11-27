# Cobbie 0.84.0 — the damage is the game's, not a timer's

Artifact: `cobbie-0.84.0.jar`.

He fought, and he won, and every number in that fight was the mod's own. A mob base `ATTACK_DAMAGE` of 3.0
where a player has 1.0; a swing timer of five ticks started by the attack verb and a *second* timer of twenty
ticks inside the melee goal; no attack strength at all, so every swing dealt full damage the instant the
timer allowed it; and nothing that ever chose a weapon — whatever happened to be in his hand was what he
fought with, pickaxe included.

The hypothesis this slice tested was not "can he kill a zombie". It was **whose number is the damage**, and
the answer, from the owner's own swing as the oracle, was: ours, by three per hit, and at a pace no weapon on
the bar would choose.

## The defect, in four places

| what he did | what the game says | what the code did |
|---|---|---|
| a swing with an iron sword | 5.904 health off a zombie | 5.904 — with a base damage of 3.0, three more than a player's, under an item that added its own on top |
| a swing half way through the cooldown | 40% of it (`0.2 + strength² × 0.8`) | full damage: `Mob.doHurtTarget` has no strength term |
| an asked-for fight | the weapon's pace: 12.5 ticks for a sword, 22.2 for an axe | a five-tick timer of his own |
| the defensive fight | the same weapon's pace | the melee goal's flat twenty ticks — a second timer, in another file, for the same body |

Two clocks for one body is the shape this project has already named — *a condition written for one caller,
applied to all of them*. What a zombie cost him depended on which path reached it, and neither path asked the
game.

## The fix: one gate, and the game's own arithmetic

- **The damage is the game's.** The base `ATTACK_DAMAGE` is a player's 1.0, so the held item's own
  `ATTRIBUTE_MODIFIERS` component supplies the weapon's damage exactly as it does for the owner. `doHurtTarget`
  then does what it always did — attribute value, enchantment modification, item bonus — and that is the
  whole of the number.
- **The pace is the weapon's.** `attackStrength()` is the ticks since the last swing over `20 / attack_speed`,
  which is `Player.getCurrentItemAttackStrengthDelay`, and a swing is only allowed at 1.0 — the point where
  the game's scale factor is exactly 1. Both callers, the melee goal and the attack verb, go through one
  `trySwingAt`, so "may he swing" has one answer.
- **The weapon is the best one he carries**, by the `ATTACK_DAMAGE` the item carries for the main hand, then
  its `ATTACK_SPEED`, then a stated order: sword, then axe, then pickaxe. The stated order only breaks a tie
  the game leaves, and the test proves the tie-break can lose — a netherite pickaxe (damage 5) beats a golden
  sword (3).
- **The shield is the game's block.** With a shield in his off hand and a hostile engaging inside six blocks,
  he raises it (`startUsingItem` on the off hand) and turns to face the threat, because
  `applyItemBlocking` only stops what arrives inside the shield's angle. The five-tick block delay is the
  game's, and a blow inside it is a blow that lands.
- **Target selection is one stated rule**, used by the defensive goal and the attack verb alike: the owner's
  attacker outranks his own, then the nearer one, then the harder hitter by the game's attack-damage figure,
  then the more wounded. Never the owner, never a tamed pet, and never through a wall — a hostile he cannot
  see is not the pick while one he can see is on offer, and a swing refuses without sight in every case.
- **The retreat still owns him.** A wound below `retreat_health_fraction` mid-fight cancels the plan and the
  swing gate refuses while the retreat runs, so a fight that turns bad stops being a fight instead of
  continuing to nothing.

## Evidence

Four new in-game classes (`CobbieGameTests`: 75 → 79). The oracle for every damage number is the **owner's own**
`Player.attack` at full attack strength, standing on the same footing with the same item, and the health read
off the world; nothing of the companion's combat path is on it.

| item | owner's own swing | his swing |
|---|---|---|
| iron sword | 4 hits of `[5.904, 5.904, 5.904, 2.288]`, gaps `[13, 13, 13]`, cooldown 12.5 | identical, gaps `[13, 13, 13]` |
| iron axe | 3 hits of `[8.856, 8.856, 2.288]`, gaps `[22, 22]`, cooldown 22.2 | identical damage and count, gaps `[23, 23]` |
| bare hand | 22 hits of `0.94`, gaps `[10 × 21]`, cooldown 5.0 | identical, gaps `[10 × 21]` |

The bare hand is the row worth reading twice: a five-tick cooldown lands every **ten** ticks, because a target
cannot be hurt twice inside its own ten-tick hurt window. The cooldown is asserted as a floor; the cadence is
compared against the owner's own landed cadence. Both numbers are measured, neither is assumed.

- `CobbieCombatOracleTest` — the three items above, damage per hit, hits to kill, and the gap between landed
  hits.
- `CobbieWeaponTest` — the pick both ways (iron sword damage 5 over diamond pickaxe 4; netherite pickaxe 5
  over golden sword 3), the tool he did not choose still in his bag, and one zombie blow with and without the
  shield: **3.0 health without it, 0.0 with it raised**.
- `CobbieThreatTest` — nearer wins at 3 vs 7 blocks; the owner's attacker at 9 blocks beats his own at 2; the
  wounded one wins an equal-distance tie; the visible one at 6 beats the hidden one at 2 behind a three-course
  wall; an attack on players with player combat on is refused with *"nothing I am allowed to fight is
  nearby"*; a wolf tamed to the owner keeps 40 of 40 health through a fight beside it.
- `CobbieCombatRetreatTest` — wounded to 6.0 mid-fight, the zombie stayed at 14.096 health, the distance grew
  2.7 → 7.8 blocks, and the attack plan settled.

## What the harness cannot drive honestly

- **A mob's own decision to swing.** The shield measurement calls `Zombie.doHurtTarget` directly — the same
  call the zombie's melee goal makes — so it proves what the game does with a blow, not why a zombie chose to
  throw one.
- **Real pathing around a wall.** "Never through a wall" is asserted as selection and as the swing's own gate,
  not as "he failed to find a route": the pathfinder walking around is correct behaviour.
- **He does not crit.** A player's critical hit needs `fallDistance > 0` and `!onGround`; he fights standing,
  like the owner in every oracle above, so the 1.5× multiplier is in neither number.
- **`OwnableEntity` has no vanilla ownable hostile to exercise it.** The wolf case proves he fights the zombie
  rather than the pet beside it; the exclusion is a safety net for a future ownable enemy, and the audit
  section says so instead of implying it is exercised.
