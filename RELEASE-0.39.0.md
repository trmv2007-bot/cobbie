# Cobbie 0.39.0 — he said he would, so he must have said he was doing it

Artifact: `cobbie-0.39.0.jar`. **178 unit tests green** (184 counting the 6 opt-in live-provider
checks), and the narration gate green at **6 of 6** on a cache-free run.

Renumbered from 0.38.0 because the portal slice took that number. This release carries production
code, not just tests.

## The defect this is about

Asked to brew, anvil, grind and weave, he answered *"On it — weaving the creeper pattern onto your
white banner"* and queued **nothing**. Measured across runs at 4 of 6 and 2 of 6 produced plans. A
miss is honest; a promise with no action behind it is the failure this project exists to prevent.

## What was ruled out, with numbers

**Not the parser.** `steps=0 model calls=1` and zero "dropped actions" warnings: the reply carried no
action rather than the executor losing one.

**Not history length.** Same six prompts, same world, same model, only the seeded history varying —
0 / 3 / 5 / 15 prior exchanges produced 5, 3, 5 and 5 plans of six. No threshold, no trend, and seed
zero loses an ask too. The 15-exchange run printed `the model will see 12 of them`, confirming the
12-message ceiling: five and fifteen are the same regime by construction. **My earlier
"session-length" explanation for the 6 → 4 → 2 spread was wrong, and I withdraw it** — the spread was
sampling noise over roughly a one-in-six per-ask rate concentrated in two phrasings ("zoom my map
out" 3 of 4, "this enchanted book" 2 of 4, grind/shear/weave 0 of 4).

**Not the pool, as far as can be told.** `pooled-deepseek` is pinned by name for every run since the
pool was found to rotate. The gateway log cannot attribute a request to an upstream model — every
line is `POST https://api.openference.com/v1/chat/completions 200` — so substitution can neither be
confirmed nor excluded retrospectively; pinning is what removes the confound.

**And not, in the end, reproducible on demand.** A cache-free run with the fixture completed and the
repair **off** produced **6 of 6** plans. The two asks that had failed did so because my fixture did
not give him what they needed — a glistering melon slice, and the sword he was asked to work on — and
his replies were correct: *"a potion of healing needs a glistering melon slice, and I have no melon"*.
Scoring those as lies was scoring my fixture. Every ask's items are now asserted at setup.

## The fix, and the second model's objection to it

The first version triggered a one-shot repair call on a **regex** over the answer's wording. Asked to
attack that design, the second model's verdict was that a regex repair should not ship as the
permanent fix: it can turn a missing action into an *unintended* one, because a phrase like "I'll need
a stand" fires it. That is right, and it changed the design.

**The promise is now a field the model declares about its own reply:**

```
{"answer": "...", "doing": true, "actions": [ ... ]}
```

Rule 3b in the system prompt: `doing` is required, true only when the answer says something is
happening, and if it is true `actions` must not be empty. The repair fires when `doing` is true and
the actions are empty — a contradiction the code can *see*, not a phrase it has to guess. The regex
survives only as a fallback for a reply that did not declare, and is labelled as such in the code.
The repair executes under the sentence already shown to the player, and the plan it produces is
parsed exactly like a first reply.

## Verification

**Unit (`./gradlew cleanTest test`):** 178 green, 6 skipped. Jar builds as `cobbie-0.39.0.jar`.

**In game, cache-free (`--no-build-cache --rerun-tasks`):**

```
[gametest] Narration ok  "cobbie brew me a potion of healing"          -> 3 step(s)  brew nether_wart → brew melon
[gametest] Narration ok  "cobbie put this enchanted book on my sword"  -> 1 step(s)  anvil, refused for 0 levels
[gametest] Narration ok  "cobbie take the sharpness off this axe"      -> 1 step(s)  grind, COMPLETED
[gametest] Narration ok  "cobbie shear that sheep"                     -> 1 step(s)
[gametest] Narration ok  "cobbie put a creeper pattern on my banner"   -> 2 step(s)  COMPLETED
[gametest] Narration ok  "cobbie zoom my map out with paper"           -> 1 step(s)  COMPLETED
[gametest] Narration summary: 6 of 6 plain requests produced a plan
```

The gate now also treats an honest blocker as honest: no plan is only a failure when the reply
claimed an action, judged by the same predicate the repair uses, so the two cannot drift.

**The repair firing is now observed, not inferred.** In the cache-free regression run at this head it
fired on its own:

```
[12:44:59] (cobbie) Cobbie repaired a narrated action for TestPlayer (2 step(s))
           "On it — back to the grindstone to strip the sharpness off your iron axe."
```

The model answered with a promise and no actions, the repair recovered two steps, and they ran
(`walk-to [done] → use [failed]` — the second step is the unrelated `use`-on-a-block problem, not a
narration one). Six brain-path classes in that run: `Status`, `Panel`, `Smoke`, `Chat`,
`VerbChoice`, `Narration` — **6 passed, 0 failed of 6**.

**Still not verified:** how often it fires in ordinary play. One observed firing proves the path
works; it does not give a rate, and with the fixture complete the gate needed it only once in six.

**A standing correction:** every in-game number I reported before the `--no-build-cache --rerun-tasks`
rule came from runs without it. Logs show the compiler executed after each source edit and behaviour
tracked every change, but that is consistency, not proof. Treat the earlier scores as provisional.
