# Cobbie 0.64.0 — the allowlist family, and a dated run of everything

Artifact: `cobbie-0.64.0.jar`. **222 unit tests green**, full in-game suite run at 0.63.0's tree.

## The fifth family, named

Four of this project's defects share a second shape, and it is worth its own entry because the fix is different:
**a rule written for the cases someone thought of, where a miss is silent and means "nothing happened".**

| Allowlist | What a miss looked like |
|---|---|
| the mining tool gate | a log refused as if it were obsidian, so a project could not start |
| the empty-hand guard | every bare-handed block use refused; a lever said *"I am not holding anything to use"* |
| the description path beside the id scan | "diamond sword" spelled an id, so the first of two swords was taken |
| the promise verb list | *"Equipping my worst pickaxe — the worn one — now"* was not a claim, so no repair ran and the sentence reported work as done |

The last is the dangerous version: a claim the list does not know is not merely unhandled, it is **reported as true**.
The fix that worked inverted the default — anything shaped like a claim gets one bounded repair call — rather than
extending the list. Where an allowlist cannot be avoided, the miss has to be loud: a refusal with a reason, or a
check that fails, never silence.

**The refusal test is as load-bearing as the positive ones.** *"I have no hoe"* is a report, not a claim, and must not
trigger a repair: a fix that turns every sentence into a model call would be a worse defect than the one it fixed.
That test is named `aRefusalIsNotAPromise` and it exists for whoever widens the rule next.

## The dated run

At 0.63.0 on the installed tree, `--no-build-cache --rerun-tasks`:

```
summary: 48 passed, 2 failed of 50
```

The two are the brain-gated pair, offline, and they say so: `never reached a connected brain; nameplate stayed
"§eCobbie §8· §7idle"`. `VerbChoice` and `Narration` degraded to `skipped: no live model` and sit inside the 48 —
"could not test" rather than "tested and passed".

**Two classes that failed the last verification now pass, and the naming work is why**: `CobbieStatusTest` and
`CobbiePanelTest` had been failing on a nameplate stuck at `Cobbie (LLM offline)`; the plate is now rebuilt from the
companion's name and status every ten ticks, so both assert against something true even with no brain. A side effect
worth recording: the naming slice made two tests honest that had been red for an unrelated reason.

## Verification

- `cleanTest test`: **222 tests, 0 failed, 6 skipped**.
- Full in-game run, 46 class names → 50 classes (the filter matches by name and swept in `Farm`, `Trade` and
  `Redstone` probes); 48 passed, the two brain-gated failures above.
- `tools/audit-bug-families.py`: no new family beyond the five documented; family E clean.
