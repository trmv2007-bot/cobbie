# Cobbie 0.72.0 — the lifecycle of a project: interrupted, impossible, and not repeated

Artifact: `cobbie-0.72.0.jar`. **229 unit tests green**, `Lifecycle` green in game.

The fifth failure a player meets is the one that leaves no trace when it goes wrong: a project that vanishes without a
word, or one that starts itself again behind the owner's back.

## Two scenarios, two verified endings

```
the initiative project (1) is VALIDATED
interrupted: owner instruction done, the project recorded
  "gathering the oak log nearby -> CANCELLED — cancelled before it ran | mine [skipped] x3",
  and nothing restarted
impossible: FAILED — "mine that far log: 0/1 steps done — there is nothing at 1 -60 5"
  (and it did not re-announce)
```

- **An owner instruction cancels his own project** — the self-started gather was `CANCELLED` with every step marked
  *cancelled before it ran*, the owner's instruction then ran to completion, and **no plan started afterwards** inside
  the cooldown. That last assertion is the one that catches a cancelled project creeping back.
- **A project whose target disappears abandons once, naming the coordinate**, and the plan count confirms it did not
  re-announce: `plans went N -> N+1`, exactly one.

## The fixture mistake worth recording

The first version of the interruption test used **two owner instructions**, and both ran: the second queued behind the
first. The contract is narrower than it looked — *an owner instruction cancels a **self-started** project; two owner
instructions queue in order, because dropping the owner's own work would be worse than waiting*. The test now builds
its project from initiative, which is the pair the rule is about.

## Also

`docs/AUDIT.md` gains the fourth instance of the pattern, and the clearest one: **`CobbieConfig.isCreative()` is the
autonomy mode**, used in the mining gate where the *world's game mode* was meant — so a creative world with ASSISTIVE
autonomy refused to break a log by hand. The lesson is written as a claim: **a predicate's name is a claim about what
it answers**, and this one survived four slices because every world the tests used had creative *autonomy*.

## Two assertions the first version got wrong

Both were mine, and both were about what "the project came back" means:

- **A follow-up is not a re-announcement.** After a failed plan the brain asks the model what to do next, and that
  creates a plan of its own. Counting *any* new plan as the project repeating itself would have called legitimate
  behaviour a defect. The check now looks for a plan with **the same request**, not merely a newer id.
- **The impossible project is plan `before + 1`.** My first window treated the plan at the newest id as "newer", which
  compared the plan against itself. The loop now starts one past it.

## Not built yet, named

Two scenarios from the brief are still to come: **a project chosen while another is stale** (the world achieves the
first objective by itself), and **a restart mid-project** (the honest outcome is that it does not survive, provided he
says so on waking). Both need fixtures the current class does not have.

## Verification

- `cleanTest test`: **229 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Lifecycle`: 1 passed of 1, `--no-build-cache --rerun-tasks`, `./gradlew --stop`
  after.
