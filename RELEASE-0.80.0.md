# Cobbie 0.80.0 — a settled step carries its evidence

Artifact: `cobbie-0.80.0.jar`.

`TaskRunner.settle()` marked any outcome whose `success()` was true as DONE. "The world agrees" was therefore
a property each handler kept by hand, and four false successes shipped in one week because three handlers did
not keep it. This is the slice that makes the guarantee structural, and it does it in one commit rather than
one verb at a time — because the type change cannot be half-applied.

```
before:  ActionOutcome.ok("write", "arrived")                       -> DONE
after:   ActionOutcome.ok("write", summary, world("the sign reads…")) -> DONE
         ActionOutcome.ok("write", "arrived", ownAct("i got there"))  -> FAILED, with the reason said
```

## One type, one check, one table

- **`ActionEvidence`** has exactly two kinds: `World` (something was read off the world after the change) and
  `OwnAct` (the verb claims nothing about the world — he spoke, waved, waited). Both require a sentence saying
  what was seen and refuse a blank one. A menu's answer, a returned boolean and "the step arrived" are none of
  those, which is the whole point.
- **`ActionOutcome`** refuses to exist as a success without evidence: one constructor, and it throws. That
  makes the four defects unwritable rather than merely discouraged, and it is why the migration is 43 sites in
  one commit — the compiler will not let a handler stay behind.
- **`ActionVocabulary.provesWorld(verb)`** is the endings inventory's last column turned into code, and
  fail-closed: the eight verbs that claim nothing are named, and a verb nobody has classified — or a null —
  has to show the world.
- **`settle()`** applies the bar. A success below it becomes a failure that says so ("i said write was done
  with only "i got there" to go on, and write has to be read back off the world"), takes the retry budget
  every other failure takes, and is reported rather than promoted.

Two fall-throughs that answered `ok(verb, "done")` and `ok(verb, "arrived")` are now failures, for the reason
`performNow`'s default arm already failed: a verb whose finishing move nobody wrote is not finished.

## The inventory, re-counted

| | verbs |
|---|---|
| registered in `ActionVocabulary` | 36 |
| claiming nothing about the world | 8 |
| that change or read the world | 28 |
| whose success is read back off the world **before it is claimed** | **28** |

Seventeen and eleven at 0.79.0. The eleven were not dishonest, and the fix was not to relabel them: 0.79.0
had already given ten of them a world read in `stored()` ("the thing I made is in my bag or on the ground"),
and the remaining paths had a read within reach. What each one gained:

| verb or path | reads now |
|---|---|
| `give` | the owner's inventory count before and after, not `add()`'s answer |
| `mine`, full-bag path | the square is air, on the path that used to trust `destroyBlock` |
| `collect` | the bag total grew by what came off the ground |
| `farm` | the squares that no longer hold the crop — the count **is** that read |
| `attack`, target gone | the hunted entity by identity: not alive, or not in the world |
| `use` on an entity | his hand after the use — an unchanged hand is a failure, not a success |
| `anvil`, `brew` | what he carries, and the stand's own contents |
| ten station verbs | `stored()`'s location, now carried into the sentence |

`stored()` became `store()` and returns *where* a made thing went — bag, ground, or nowhere — because the
success message and the evidence sentence are both written from it. A boolean could say `true`; a location
can say "in my bag".

## Proof that it bites

`ActionContractTest` (unit, 9 tests) feeds the runner the four 0.79.0 defect shapes **as they were** — a
success with only its own act behind it — and requires each to be refused with the retry budget any failure
gets. Disabling the check in `settle()` turns two of them red; that is the negative control, run and recorded.

`CobbieEvidenceTest` (registry entry **67**) re-induces each defect in a real world and checks the **world**,
never the sentence:

| defect | what the test requires |
|---|---|
| the walked write that reported `arrived` | the sign eight blocks away reads his words, and the step's evidence is those words |
| the farm that counted over a discarded boolean | the number he reports equals the squares that lost their crop, checked against the field |
| the ten sites that discarded `addFreshEntity` | the mended pickaxe is findable in his bag or on the ground, and he says where |
| the item resolved to the wrong slot | "sharpness 5" among two swords that id, count and damage cannot tell apart puts the enchanted one in his hand |

## What this contract cannot do

It makes the *shape* unwritable; it cannot stop a handler from writing a world sentence it did not read. So
each of the four is also pinned by a test that reads the world itself. One limit is named rather than hidden:
the farm case cannot induce a refused break, because a vanilla flat world has no protection system — which is
why the count is derived from the squares the world reports empty instead of from `destroyBlock`'s answer at
all.

## Verified

- `JAVA_HOME=… ./gradlew cleanTest test` — 276 tests, 0 failed, 6 skipped (was 267).
- `./gradlew runClientGameTest --no-build-cache --rerun-tasks` — the class count and any reds are quoted in
  the pull request.
