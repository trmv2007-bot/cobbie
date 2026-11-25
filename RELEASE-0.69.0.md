# Cobbie 0.69.0 — the config screen a player can read

Artifact: `cobbie-0.69.0.jar`. **229 unit tests green** (6 new), `ConfigUi` green in game.

The screen already had toggles, pickers, a masked key and a Test Connection button — what it lacked was a structure
a person can walk through and a save path that cannot lie.

## Four sections, and the numbers folded away

| Section | What is in it |
|---|---|
| **Brain** | provider presets, the key (masked to its tail), base URL, model, Test Connection |
| **Behaviour** | personality, autonomy mode, chat, initiative, plan limits |
| **Permissions** | a toggle per capability, off by default and *visible as off* |
| **Advanced** | temperature, tokens, timeouts, retries, movement numbers — **collapsed by default** |

The Advanced toggle is in the footer rather than at the top of the list, so it does not move when the list scrolls.

## Nothing changes unless Save is pressed

This is the panel-versus-chat lesson applied to a settings file: **a screen that writes as you type is a screen that
can lie about what is configured.** Edits live in the screen's in-memory object; the file is written only by Save.

Two things were wrong with the old path, and both are fixed:

- the write failure was swallowed — `saveConfig` logged nothing a player would see and returned, so a failed save
  looked exactly like a successful one. It now returns a boolean, puts the reason in the status line, and **the
  screen stays open on failure** rather than closing over the reason;
- writing lived inside the screen. It is now `ConfigStore.write(path, object)` — atomic, and testable without
  drawing anything.

## What is tested, and which half

- **The arithmetic**, in `ConfigLayout`: a collapsed section hides its fields and keeps its header (or it can never be
  reopened), rows stack one row apart, scrolling is bounded by visible content and clamped both ways, and a section
  with no fields is recognised as a dead end.
- **The file**, in `ConfigStore`: saving writes, **closing without saving leaves the file byte-identical**, and a
  write that cannot happen returns a reason instead of throwing.
- **The screen in game**: `ConfigUi` opens it on a real config, shows the preset the config implies, keeps the key in
  the field while drawing only its tail, and applies a preset. It passed unchanged with the new sections and the
  collapse.
- **Not tested**: whether the layout *reads* well. There is a screenshot from the run
  (`build/run/clientGameTest/screenshots/0000_cobbie_config_keys_first.png`) and I could not view it myself, so the
  drawing is for a person to judge — the arithmetic is what I can vouch for.

## Verification

- `cleanTest test`: **229 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=ConfigUi`: 1 passed of 1, `--no-build-cache --rerun-tasks`.
