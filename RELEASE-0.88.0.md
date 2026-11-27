# Cobbie 0.88.0 — he chooses among opportunities instead of inventing a goal

Artifact: `cobbie-0.88.0.jar`.

Until now his autonomy was one deterministic picker: look six blocks around, and if a pile of logs or
stone is there, that *is* the project. It worked, and it was the wrong split. An 8B model with about
1200 output tokens was being asked to invent the next goal — to rediscover, in prose, what the game
already knows — and the answer was either vague or a repeat of the picker's own choice. The inversion
this slice ships: **a deterministic assessor produces what is possible, the model chooses what is
worthwhile, one component at a time, and no component inherits another's authority.**

The old picker's defect is worth naming because it is the audit's shape in a new place. `Initiative`
iterated a `LinkedHashMap` of block types and returned the first entry with three blocks — so
**iteration order was the decision**, made by whoever last touched the map, and the second-best thing in
sight was never considered at all. Nothing looked wrong: the project it started was real and it
finished. What was missing was the question.

## Four parts, and what each one owns

| part | who | decides | never does |
|---|---|---|---|
| assessor | `OpportunityAssessor` | **what can start right now** | rank, score, or pick |
| model | `OpportunitySelector` (JSON mode) | **what is worth doing** | plan, describe, or invent |
| dispatcher | `OpportunityCycle.apply` | **whether the answer is still valid** | substitute a candidate |
| executor | the existing `TaskRunner` | **what actually happened** | accept a claim without evidence |

## The candidate

Five to ten validated opportunities, each carrying the fields the brief names: `id`, `kind` (`find`,
`help`, `protect`, `return`, `replenish`, `interact`, `build`, `farm`, `mine`, `fight`, `trade`,
`wait`), a one-line `reason`, `benefit`, `requires`, `estimatedTicks`, `risk`, `ownerRelevance` (0-3),
`cancelsOn`, and the exact steps that would start it.

**Validation is the executor's own gate, not a second opinion.** A candidate is built only after the
check its first step will run — `canStartMining` for a block, `canPlace` for a square, legality plus
line of sight for a fight, a real path for a walk, `permits(verb)` for the switch. An opportunity that
cannot take its first step is not offered at all, which is why the shortlist is **capped at ten and
never padded**: filling a short list to reach five would mean offering something that cannot start,
which is the assessor choosing by another route.

Eight families are produced today. Five are named in the enum and not produced, each for a reason
stated in the class: `replenish`-by-eating is already his body's own policy at `EAT_AT`, `interact` and
`trade` have no deterministic gate that says the station or the offer is his to use unasked, `help` is
covered by `protect` (a fight is the only help the game lets him read), and `wait` is not a candidate
because rejecting the list already means WAIT. `replenish` does appear — for something on the ground he
can walk to, which is restocking rather than eating.

## The choice

One JSON object, out or back. The request carries the server's own structured-output mode —
`format: "json"` on Ollama, `response_format: {"type":"json_object"}` on an OpenAI-compatible server —
with the same one-protocol-correction retry the `"think"` field already had, so a server that does not
know the field costs a retry rather than the turn.

```
{"choice":"op-3"}                                   → op-3 starts
{"choice":"wait","why":"my owner is building here"} → nothing starts
```

Everything else is WAIT, with the reason recorded: an empty answer, prose, an array, an object with no
`choice`, an id that was never offered, a request that failed, a brain that is not configured. **No
path falls back to a candidate.** A fallback would be indistinguishable from a model that chose, and it
would quietly put the assessor back in charge — the one failure this slice exists to prevent.

## The cadence

Re-evaluation is an event, not a poll. A situation fingerprint of eight parts:

| part | the event it stands for |
|---|---|
| `ownerBucket` | the owner moved away — inside 4 blocks, to 12, to 32, beyond, or not here |
| `healthBucket` | his health in fourteenths — about one full heart |
| `foodBucket` | his hunger in quarters |
| `threats` | **a state, not a count**: 0 nothing, 1 something engaging, 2 something in sight, 3 both |
| `bagHash` | the bag and the hand in powers of two, so one more of something is not a change |
| `busy` | a job started or finished |
| `planId` | a different job ran |
| `ownerSpokeTick` | the owner spoke |

**Heartbeat: 1200 ticks — one minute** — a floor for an unchanged scene. **Minimum gap: 40 ticks**, so a
flapping scene cannot become a poll. **After he starts something of his own: 2400 ticks of quiet**, the
cooldown the old picker kept. **After a WAIT: the heartbeat stretches** to 90 s and two minutes on
repeated rejections, while a genuine change still wakes him at once. Counts are bucketed on purpose: a
raw bag hash would wake the model every time he picked up a flower, and a raw hostile count would call
"a second zombie walked into view" a change worth a model call.

What is deliberately *not* in the fingerprint: the time of day (the plan runner already refuses a fight
it cannot win) and his exact coordinates (walking three blocks is not a new situation).

## What he is carrying from before

A remembered `OUTCOME` for the same job appends its failure to the candidate's reason — "three oak logs
within reach (last time: failed — nowhere to stand)". It is information for the model, never a veto: the
store has no clock, so a veto would suppress a job forever on the strength of one bad afternoon.

## The decision is visible

One log line per decision, written **before** the plan starts, so a session can be read back afterwards
and a crash mid-job still leaves the choice on record:

```
[opportunity] offered [op-1(protect) op-2(return) op-3(mine) op-4(build) op-5(replenish) op-6(farm) op-7(fight) op-8(find)] -> op-4 [model]
[opportunity] offered [...] -> wait (my owner is building here) [model_rejected]
[opportunity] offered [...] -> wait (the choice request failed: HTTP 400 ...) [fallback_rejected]
[opportunity] nothing offered (unchanged (nothing changed), heartbeat in 1180)
```

`/cobbie opportunities` prints the current shortlist and the last decision in the same game it is
choosing in. It wakes nothing and calls no model: it is the instrument the next slice measures against,
not a feature.

## Evidence

| line from the run | what it proves |
|---|---|
| `[opportunity] shortlist (8): protect, return, mine, build, replenish, farm, fight, find` | a live flat world with four logs, cobblestone in the bag, an ingot on the ground, ripe wheat, a zombie already coming for him, a second hostile merely in sight, an owner twenty blocks off and ground he has not walked produces **eight** candidates, and every one of them was re-run through its own verb's gate from outside the assessor |
| `stone without a pickaxe was not offered, and the shortlist was not padded to reach a number: op-1(protect) … op-8(find)` | three stone blocks with no pickaxe in the bag appear nowhere in the shortlist, and the list stayed at eight rather than being filled to a quota |
| `offered [op-1(return) op-2(mine) op-3(build) op-4(replenish) op-5(farm) op-6(find)] -> wait (just keeping you company) [model_rejected]` | a rejection is a WAIT, with the model's own reason on record and the origin marked |
| `wait honoured` and no plan created, no log touched | WAIT starts nothing and moves nothing |
| `offered [...] -> op-3 [model]` then `choosing the second candidate ran it: "building a cairn of cobblestone here" — 3 cobblestone standing, 4 log(s) untouched` | **the second candidate on the list was chosen and the second candidate's work is what ran**, while the first candidate's work (the logs) was not touched |
| `offered [...] -> op-2 [model]` then `choosing the pile afterwards ran it: "gathering the oak log nearby" — 1 of 4 log(s) left` | from a freshly assessed list the first candidate also runs when chosen, so the pass is not a fixed preference for one position or one kind |
| `no brain (…): WAIT "the choice request failed: ConnectException: null" and no plan; 6 candidate(s) were offered and none was taken` | **fail-closed**: with the request pointed at a closed port, six validated candidates were offered and none was taken — no fallback to a candidate, and the origin is `fallback_rejected` so a session log tells the truth |

## What could not be driven honestly

- **The model choosing sensibly.** The harness cannot make `lfm2.5:8b` pick B because a test wants B,
  and the suite must run with no model at all. The in-game test injects the *raw answer* at the boundary
  it would cross — `OpportunitySelector.parse` then `OpportunityCycle.apply`, the shipped parser and
  dispatcher — so everything except the socket is real. That the model, handed this shortlist, chooses
  well is measured by nothing in this repository: it needs a live 20-minute session.
- **A hostile's own approach.** The zombie that counts as "already coming for me" has its target set
  directly, the same call its own AI makes. What is proved is what the assessor reads from that state,
  not the zombie's decision to walk over.
- **A second body's cadence.** The cadence is per entity and pure arithmetic over ticks, so its tests
  are exact; what is not driven in game is two companions' cadences interfering, because there is one.

## Verified

| what | result |
|---|---|
| `JAVA_HOME=C:/Users/rishi/jdk25 ./gradlew cleanTest test` | **369 tests, 0 failed, 6 skipped** in 40 classes (325 before this slice); the six skips are the opt-in live-provider checks |
| `runClientGameTest -PcobbieTests=Opportunity --no-build-cache --rerun-tasks` | **1 passed of 1**, 12.6 s |
| `runClientGameTest --no-build-cache --rerun-tasks` (whole suite, exact pushed tree) | **81 passed, 2 failed of 83** — `CobbieGameTests` registers 83 classes, was 82 at 0.86.0 |
| the two reds | `CobbieSmokeTest` and `CobbieChatTest`, both "never reached a connected brain": nothing answers at 11434 on this machine, and `tools/gametest-preflight.ps1` said so before the run. They are the two brain-gated classes and fail for that reason alone, exactly as the 0.86.0 run recorded them |
| `CobbiePerfTest` | green in the same run (243 s), so the fingerprint read twice a second did not put an entity query in every tick of every body |

Three things the runs taught, recorded rather than papered over:

- **A log counted by an offset from the body is a test that moves when the body moves.** The first run of
  `CobbieOpportunityTest` failed with "A WAIT moved blocks: 4 log(s) are gone". Nothing had moved them:
  a zombie fight in the same phase had knocked him four blocks off his square, so the four offsets
  pointed at empty air. The squares are recorded once by absolute coordinate now — and the hostiles are
  cleared before the plan phases, because a fight is a second reason for him to move.
- **The two in-game classes that used to assert "a project starts unprompted" had to change, and that is
  a coverage change, not a rename.** With the model in the loop and no model on this machine, nothing
  starts — which is the correct fail-closed answer. Both classes now inject the model's raw answer at the
  boundary it would cross (`OpportunitySelector.parse` then `OpportunityCycle.apply`), so the assessor,
  the parser, the dispatcher and the plan runner are all the shipped ones; what is lost is the claim that
  a running system starts work with nobody watching, and that claim is now measured only by a live session.
- **`/cobbie opportunities` is asserted, not assumed.** The instrument had to read back from inside the
  game — `read back 8 candidates and the last decision` — because an inspection surface nobody has
  opened is a log line with extra steps.
