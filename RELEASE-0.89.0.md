# Cobbie 0.89.0 — what he may do when nobody is watching

Artifact: `cobbie-0.89.0.jar`.

0.88 gave him a decision: an assessor says what is possible, the model says what is worth doing, and he
starts it while the owner is somewhere else. That inversion is only safe if acting unprompted cannot cost
the owner anything they would mind, and two holes were named and proven in the capability document: he
would not touch an animal, so a companion with a hunger bar starved beside a herd, and he would prime TNT
wherever he was pointed, including beside the owner's house. This slice closes both and audits the rest of
the same question.

## The hunting rule

> **He may take an animal only while he is hungry and carrying nothing to eat, and never an animal that is
> someone's pet or standing in a pen.**

Each clause is something the game answers, and each lives in `safety/Hunting.java` so the sentence, the
radius and the predicate are written once:

- *hungry and carrying nothing to eat* — his hunger bar is at or below `Hunger.EAT_AT` (14, the number his
  own eating policy already uses) **and** no bag slot holds anything with a `DataComponents.FOOD` nutrition.
  A fed companion never looks at a cow; a companion with bread in his bag neither.
- *someone's pet* — `OwnableEntity.getOwner() != null`, the check that already protected tamed wolves.
- *in a pen* — a fence, fence gate or wall within eight blocks of where the animal stands. That is as close
  as a blockstate rule can come to "a pen the owner built", since nothing in the game records who placed a
  block, and the limit is named rather than hidden: **a large pen's middle reads as open ground.**

`isLegalTarget` now answers for two kinds of target and no third: a hostile, and a huntable animal. Every
threat question — what is coming for me, what may I go after, how many hostiles are near — was moved to
`isThreat`, which is `Enemy && isLegalTarget`. Without that split, his hunger bar would have started
deciding how many enemies he thinks are around him, which is a situation fingerprint and a defensive plan
reading a fact that is not about defence.

The autonomy path is real, not just permitted: the assessor now offers a `replenish` candidate for a hunt,
gated by the rule itself (`huntableNear` answers only while the rule says yes), so he never proposes a kill
the executor would refuse. Kill, then the meat comes to him through the ordinary reach pickup, and his own
eating policy eats it within a second because that is what the hunger is for.

## The danger rule

> **He never primes TNT, sets a fire or pours lava within sixteen blocks of the owner, the owner's bed or
> respawn point, or a place the owner named that he remembers.**

The list is deliberately short and read off the item rather than off the verb, so a new path to the same
item cannot slip past it: flint and steel, a fire charge, a lava bucket. The block only decides whether
lighting is a fire or a fuse (`safety/Hazards.java`).

The guard holds **whoever asked**. A fire beside your own house is a fire beside your own house whether it
was your idea or his, and a companion who does it because you said so is a hazard with good manners. An
owner who wants it done walks the work outside the radius, which is the honest way to say "not here" — and
the two in-game classes that prove "he can prime TNT" and "he can light a fire" now stand the owner 32
blocks clear, exactly as an owner who wanted it would.

What it protects, in one place: the owner themselves, their respawn point (`ServerPlayer.RespawnConfig`),
any bed within the radius, and every `Kind.PLACE` entry in his memory for the dimension he is in — which is
the only honest reading of "anything built by the owner that he remembers", because he cannot see who placed
a block.

The refusal says what it refused and why, in one line, and names the nearest reason:

```
I will not prime that TNT 3 blocks from you: I keep fire, TNT and lava 16 blocks clear of you,
your bed and anything you have built that I remember
```

## The audit: every verb, one row

The question for each: *if he did this unprompted while the owner was away, could it cost them something
they would mind?* "Unprompted" is not a mood — it is exactly one of nine assessor families (`protect`,
`return`, `mine`, `build`, `replenish`-by-collect, `replenish`-by-hunt, `farm`, `fight`, `find`), so a verb
no family produces cannot happen without an instruction, and that is a reason rather than a hope.

| verb | unprompted today | could it cost the owner | what stands in the way |
|---|---|---|---|
| `answer` | no | nothing — words | never a plan; nothing to verify |
| `chat` | no | nothing — words | never a plan |
| `follow` | yes (the follow goal) | tramples farmland it lands on; walks into hazards | the game's own farmland trampling, which applies to any living entity; `hazardRescueTick` walks him out of fire and lava, and the fall limit is derived from his health |
| `stay` | yes | nothing | moves nothing |
| `wave` | no | nothing | never a plan |
| `walk-to` | yes (`return`) | the same as `follow` | every leg pre-checked with `canStandAt`, then re-checked by its own step |
| `mine` | yes (`mine`) | **a block out of their build** | **guard added**: no unprompted mining within `Hazards.BUILD_RADIUS` (8) of the owner, their respawn or a remembered place; block entities, lava and fire refused outright; the drop gate refuses a block the tool cannot harvest |
| `place` | yes (`build`) | **a block in their floor or doorway** | **guard added**: the same eight-block radius; `canPlace` and flat, uncrowded sites only |
| `write` | no | wrong words on a sign | never unprompted; his own text on a block they gave him |
| `attack` | yes (`protect`, `fight`, hunt) | aggro; **a creeper's blast in a build** | legality (never the owner, never players, never a pet, never a penned animal) and **guard added**: he will not go after a creeper within eight blocks of the owner's things, while still fighting one already coming for him |
| `collect` | yes (`replenish`) | takes something off the ground | it goes into his bag and `give` puts it back; 1.6-block reach, nothing destroyed |
| `explore` | yes (`find`) | wanders into a hazard or a mob | fall limit from his health, hazard rescue, retreat below a third health |
| `light` | no | a torch in the wrong place | never unprompted; the placement gate |
| `give` | no | — | never unprompted; hands over to the owner only |
| `drop` | no | **loses items on the ground** | never unprompted; the verb verifies both sides and re-adds on failure |
| `equip` | no | — | never unprompted |
| `craft` | no | spends their materials | never unprompted |
| `smelt` | no | burns their fuel | never unprompted |
| `take` | no | **empties their chest** | never unprompted; the `containers` switch; the ender chest stays off by default |
| `put` | no | fills a container | never unprompted |
| `use` | no | **fire, TNT, lava, water, a spawn egg, bone meal** | never unprompted, and the hazard guard on every path; **named limit**: water and spawn eggs are not on the list — a bucket can still flood a room, because a bucket is a tool the brief did not ask to ban |
| `repair` `dye` `enchant` `anvil` `brew` `smith` `grind` `weave` `map` | no | spends levels, lapis, materials; grind strips enchantments | never unprompted |
| `board` | no | carried somewhere he did not choose | never unprompted |
| `farm` | yes (`farm`) | takes their harvest into his own bag | ripe crops only, and it sows the cell again; the yield is food for a companion they own, and the trampling note under `follow` applies |
| `breed` | no | spends their wheat, adds a calf | never unprompted |
| `trade` | no | spends items on a villager | never unprompted |
| `remember` | no | — | never unprompted; a note in his own head |
| `lookup` | no | — | never unprompted; a network read |
| `wait` | no | nothing | rejecting the shortlist already means wait |

Two rows needed new code (`mine`, `place`, `attack`-vs-creeper); the rest needed the reason written down, and
the reasons are of three kinds: it cannot happen unprompted, it spends only what an instruction spent, or
the damage it does is the game's own and no policy of his can prevent it. The three guards share one list of
"the owner's things" with the fire rule, at a radius that suits each: **16 blocks for anything that burns or
blasts** (blast and spread scale), **8 blocks for rearranging their build** (a broken block's radius is the
build).

## Evidence

| line from the run | what it proves |
|---|---|
| `permission probe: mayHunt=true food=6 legal=true dist=6 huntable=true bag=nothing` | hungry, carrying nothing, a cow in the field: the rule says yes, and it says so through the shipped predicate |
| `"hunting the Cow for food" killed the cow and fed him to food 6` | **he feeds himself unprompted**: the assessor offered the hunt, the injected model answer chose it, the plan ran, the cow died, and the beef came to him — the food bar is still 6 only because the assertion reads before his next eating tick |
| `the same cow reads legal on open ground and illegal inside a fence, and the penned one is untouched at 10.0` | **the pen clause, as a control**: one cow, one hunger state, one fence — legal outside it, illegal inside it, no damage over 100 ticks |
| `I will not prime that TNT 3 blocks from you: I keep fire, TNT and lava 16 blocks clear of you, your bed and anything you have built that I remember` | **the refusal, with its reason and its distance**, and no `PrimedTnt` anywhere within eight blocks afterwards |
| `the same flint and steel primes TNT with the owner out of range, so the rule is a radius and not a ban` | the same block lit by the same hand 24 blocks from the owner lights |
| `I will not pour that lava 2 blocks from you … \| I will not set that alight 2 blocks from you …` | the list is the list: lava and a bare fire refuse on their own words, not through the TNT case |
| `the same stone is offered outside eight blocks of a remembered place and refused inside it — op-1(return) op-2(mine) op-3(find)` | **his own tidying stops at a place the owner named**, while identical stone outside it is still offered — the difference is memory, not blockstate |
| `the creeper beside the workshop is left alone and the one twelve blocks out is offered` | he will not walk up to a creeper standing in what the owner named, and the same creeper out of range is a fight worth offering |

## What the harness cannot drive honestly

- **The model choosing a hunt.** There is no model at 11434 on this machine and the suite must run without
  one, so the answer is injected at the boundary it would cross — `OpportunitySelector.parse` then
  `OpportunityCycle.apply`, the shipped parser and dispatcher. Everything else in the hunt case is real:
  the assessor, the plan runner, the swing, the drops, the pickup. That lfm2.5, handed this shortlist,
  chooses to eat rather than to tidy is measured by nothing in this repository.
- **"Anything built by the owner" beyond what they named.** The game records who placed no block, so the
  memory is what he has: a bed he can find, a respawn point he can read, and the places the owner told him
  about. A house nobody ever named is not protected, and that is a limit rather than an oversight.
- **A pen's interior.** Eight blocks from a fence, gate or wall is the rule; a cow in the middle of a
  fifteen-block pen is not caught by it.
- **Water.** A bucket can flood a room and is not on the list, because the brief's list is about what burns
  and the bucket is a legitimate tool. Named rather than quietly included or quietly left out.

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | **378 tests, 0 failed, 6 skipped** in 44 classes (369 before this slice); the six skips are the opt-in live-provider checks |
| `runClientGameTest -PcobbieTests=Permission --no-build-cache --rerun-tasks` | **1 passed of 1**, 19.5 s and then 18.7 s — seven cases, twice in a row |
| `runClientGameTest -PcobbieTests=Permission,Initiative,Lifecycle,Interact --no-build-cache --rerun-tasks` | **4 passed of 4** — the four classes this slice's guards had broken and then fixed |
| `runClientGameTest -PcobbieTests=Tail,Interact --no-build-cache --rerun-tasks` | **2 passed of 2** — the two hazard abilities whose cases now stand the owner clear |
| `runClientGameTest --no-build-cache --rerun-tasks` (whole suite, exact pushed tree) | **82 passed, 3 failed of 85** — `CobbieGameTests` registers 85 classes (84 at 0.88.0, plus `CobbiePermissionTest` here) |
| the reds | `CobbieSmokeTest` and `CobbieChatTest`: "never reached a connected brain", because nothing answers at 11434 on this machine and `tools/gametest-preflight.ps1` said so before the run — the two brain-gated classes, red for that reason alone, exactly as the 0.86.0 and 0.88.0 runs recorded them. The third red was `CobbieFollowTest`, "did not close distance: before=14.14 after=14.14": it is green in both earlier full runs of this same tree and in `-PcobbieTests=FollowTest` on its own (**14.14 → 12.12**), and nothing in this slice touches the follow goal, so it is recorded as a flake rather than a pass |

The two earlier full runs are part of the record rather than hidden: **79 passed, 6 failed of 85** and
**81 passed, 4 failed of 85**, and every red in them was traced to a cause — three of them to this slice's
own guards being too broad or to stale memory in the run directory — before being fixed. Nothing was
re-rolled and hoped for.

Five things the runs taught, recorded rather than papered over:

- **`countOf` resolves a block, not an item.** The first pen-and-kill run blamed the code for not feeding
  him; the receipt was `countOf("minecraft:beef")`, which looks a block id up in the block registry and
  answers zero for anything that is only an item. `countItem` is the one that reads the bag. A test defect
  that looked exactly like the feature failing.
- **His memory persists between worlds and between runs.** The second run of the class failed three cases
  because the "workshop" the first run named was still in `config/cobbie_memories.json` at the same
  coordinates — and from this slice a remembered place is a guard radius. The same leak then turned
  `CobbieInteractTest`'s fire into a refusal "14 blocks from the place you call workshop". `spawn()` in the
  test support now empties the book, because the world is per class and the file is not.
- **A guard that protects the owner's body from his pickaxe protects him from his own usefulness.**
  `CobbieInitiativeTest` and `CobbieLifecycleTest` failed with "a pile of three logs beside him was not
  offered": the first version of the tidying radius included the owner's position, so a pile beside a
  standing owner was untouchable. What he must not rearrange is what was built, so the list is their
  respawn point, the places they named and any bed — not the person.
- **A cow wanders, and a test that names a square has to allow for it.** The kill case failed twice for
  placement: a pen six blocks away let a drifting cow enter the pen's radius and read as kept, and an
  eleven-block start let a fleeing cow die outside an eight-block search for its drops. The pen is eighteen
  blocks out, the grazing cow five, and the drops are searched around him.
- **A gametest can hang after every case has passed.** Closing a world halts the server from the render
  thread, and with his autonomy cycle still asking for work and a creeper still standing in the field, one
  run parked for forty minutes with the render thread and the server thread each waiting for the other.
  The class hands the world back quiet now — autonomy off, mobs cleared, brain drained, twenty ticks to
  settle. Any class that leaves work in flight can hit the same wall.

## What this slice deliberately did not do

- **No new verb and no new family.** The hunt rides `replenish` and `attack`; the guards ride verbs that
  already existed.
- **No metrics harness.** The counters that would measure how often he chooses to hunt or to tidy are the
  next slice.
- **Nothing about multiplayer, voice or model choice.**
- **Water, and the interior of a large pen** are named as limits rather than guarded, so the next thread
  inherits a decision instead of a surprise.
