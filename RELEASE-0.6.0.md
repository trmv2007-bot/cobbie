# Cobbie 0.6.0 — in-game evidence, three navigation fixes, the test harness

Target: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.162.0+26.3**, Java **25**.
Artifact: `cobbie-0.6.0.jar` (209,034 bytes), built by `./gradlew clean build`.

This release is not a feature release. 0.5.0 claimed nine behaviours on the strength of unit tests and
had never run in a world. Every behaviour below is now either **observed working in Minecraft** or
still marked as not verified, and the harness that shows it ships with the mod.

## Evidence tier

| Tier | What it means | This release |
|---|---|---|
| Compiled | it builds | everything |
| Unit tested | pure logic holds | 85 tests, 0 failures (`./gradlew test`) |
| **In-game observed** | a real client, a real world, an assertion that fails when the behaviour is absent | **8 behaviours, all green** |

Run it yourself: `./gradlew runClientGameTest` boots a real 26.3 client, generates a world per test,
drives it, asserts, and writes screenshots to `build/run/clientGameTest/screenshots/`. About two
minutes for all eight. The chat test needs `ollama serve` with the configured model up.

```
[gametest] cobbie alive at BlockPos{x=162, y=76, z=162} name=literal{§eCobbie}
[gametest] follow ok: distance before=14.14 after=12.13
[gametest] commands ok: stay drift=0.00 follow distance 8.49 -> 6.40
[gametest] mine ok: block at 0, -60, 8 broke, final distance=1.62
[gametest] place ok: dirt at 0, -60, 6, stack 8 -> 7
[gametest] combat ok: zombie health 19.0, sheep untouched at 8.0, owner alive
[gametest] persistence ok: same uuid, same owner, stone intact at 7
[gametest] chat ok: reply length=96 first line="Hello there, friend! I'm Cobbie, happy to be here with you — what would you like to do together?"
```

## Per behaviour

| Behaviour | Verdict | What the test actually proved |
|---|---|---|
| Spawns as an entity | **observed working** | `/spawncobbie` produces exactly one companion, alive, owned by the asker |
| Reaches the client | **observed working** | the entity appears in the client's render list with a non-null renderer and is not flagged invisible |
| Connects to the model | **observed working** | probe reaches `MODEL_AVAILABLE`; nameplate goes `Connecting...` → `§eCobbie` about a second after spawn |
| Follows the owner | **observed working** | owner teleported 17 blocks out; he closes 14.14 → 12.13 |
| `cobbie stay` / `cobbie follow` | **observed working** | stay drift 0.00 blocks while the owner leaves; follow resumes and closes 8.49 → 6.40 |
| Chat reply, never silent | **observed working** | a real chat message produced a 96-character reply of plain prose, recorded in memory |
| Mining | **observed working** | walked to the block, broke it, ended 1.62 blocks away (proving he navigated rather than reaching) |
| Building | **observed working** | placed the requested dirt, block verified, stack went 8 → 7 |
| Combat, owner defence | **observed working** | a zombie attacking the owner took damage (20.0 → 19.0) |
| Never targets passives | **observed working** | a sheep standing nearby kept full health |
| Persistence across relog | **observed working** | world closed and reopened: same entity UUID, same owner, 7 stone still in the bag |
| **Model and texture appearance** | **NOT verified** | frames are captured but nobody has looked at them; see "Still unverified" |
| Wave animation, nameplate rendering | **NOT verified** | animation is visual only |
| Multiplayer with 2+ players | **NOT verified** | needs a second client |
| Dedicated server | **NOT verified** | client renderer path is never exercised headlessly |
| Long-session memory growth | **NOT verified** | needs hours of play |

## Four bugs the tests found

Three are why 0.5.0's agency claims did not work in practice, and the fourth is the failure mode the SPEC
named as the one that broke Verity.

1. **Following cancelled task navigation** (`CobbieEntity.followOwnerTick`, line 413). Whenever the
   owner stood within follow distance, he called `getNavigation().stop()` every 20 ticks, and repathed
   to the owner every 12 ticks otherwise. A task that needed him to walk away from the owner was
   fought by his own follow code: asking him to mine a block eight blocks away moved him zero blocks.
   Fixed with an `isBusy()` guard — a running task owns his feet.

2. **`walk-to` counted interaction reach as arrival** (`ARRIVE_RADIUS`, line 86). Arrival used
   `reachSquared()`, the radius at which he can *touch a block*, which defaults to 4 blocks. So
   "walk to that spot" succeeded from 3.5 blocks away without a single step, and reported
   "arrived". Walking now requires 1.5 blocks; touching things still uses interaction reach.

3. **The executor judged a walk dead before the navigator had computed one** (lines 87-88, 547). The
   planner handed a path request to the navigator and asked `isDone()` on the next tick. An empty
   path reports done, so every walk was declared unreachable within two ticks — silently, because the
   failure looked like "unreachable target" rather than "asked too early". Walks now get a ten-tick
   grace period and up to three bounded repaths before the target is called unreachable.

4. **A broken payload was read out loud** (`ActionParser.salvageAnswer`). Live on Spark, the model closed its
   action list as `[}]}`. The strict parse failed, and the graceful fallback showed the player the entire JSON
   payload: `{"answer":"Hello there, friend!","actions":[}]}`. Never-silent held — he was never silent — but
   "never speak JSON at your friend" did not. The parser now lifts the `answer` text out of a payload too
   broken to parse, and two new unit tests pin it. This one is only visible in game: the text is valid Java
   and a passing parse either way, and the defect lives in what the player reads.

## What else changed

- **Test harness** — `src/gametest/` with eight tests, a shared `CobbieTestSupport`, and Loom's client
  game test wiring in `build.gradle`. Tests dispatch commands from the owner's command source, because
  a console source cannot spawn or own a companion; they teleport with `teleportTo(...)`, because a
  server-side `absSnapTo` on a player is undone by the client's next position packet.
- **World presets** — smoke, follow, commands, persistence and chat run on the normal preset; the
  action tests (mine, place, combat) run on flat ground, so a red result means pathfinding broke
  rather than that he spawned against a hillside.
- **`.gitignore`** — `/run/` ignored (dev client worlds should never be committed).

## Still unverified, honestly

**Nobody has looked at the screenshots.** The pixel pipeline is wired (entity on the client, renderer
registered, not invisible) and `0001_cobbie_smoke_spawned.png` is written every run, but I could not
see it: this session's model can't view images. Proportions, texture mapping, nameplate placement and
the wave animation are exactly as unverified as they were in 0.5.0. Look at that PNG before trusting
anything visual.

Combat evidence is thin by design: the test proves he engages an attacker and leaves passives alone,
not that a fight is won. One hit landed in the observed run.

## Install

Close Minecraft first — the old jar stays locked while the game runs.

```bash
rm -f "/c/Users/rishi/AppData/Roaming/.minecraft/mods/cobbie-0.5.0.jar"
cp build/libs/cobbie-0.6.0.jar "/c/Users/rishi/AppData/Roaming/.minecraft/mods/"
```

Only one cobbie jar may exist in that folder.

## Manual acceptance pass (one TLauncher session)

1. Spawn him: `/spawncobbie`. Nameplate should read `Connecting...`, then `Cobbie`.
2. Say `cobbie hello` in chat. He answers in chat, in character.
3. Walk away 20 blocks. He follows without getting stuck on a wall or in water.
4. `cobbie mine that block <x> <y> <z>` on a block a few blocks away. He should walk to it and break
   it, not delete it from a distance.
5. `cobbie stay`, then walk away: he holds position. `cobbie follow` brings him back.
6. Punch a zombie near him. He should engage it and never touch your sheep, cow or dog.
7. Quit to title, reopen the world. He is the same Cobbie, same name, same bag contents.
8. Look at him properly, once: proportions, texture, nameplate. This is the one thing the automated
   suite cannot tell you.

Report anything that fails with the behaviour name rather than a line number, and the matching test
gets written before the fix.
