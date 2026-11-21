# Cobbie 0.10.2 — the namespace bug behind "I have no netherite_pickaxe"

Artifact: `cobbie-0.10.2.jar`. 94 unit tests, 12 in-game tests, green on the pooled endpoint.

This release exists because of one line in a real session:

```
result - failed — drop me some of ur items: step 1 (drop) failed: I have no netherite_pickaxe
```

He was carrying a bag full of netherite pickaxes. The model asks for `netherite_pickaxe`; the registry
calls it `minecraft:netherite_pickaxe`; the matcher required the full id. So `drop`, `give` and `equip`
all denied carrying things he was holding — which is exactly what "his inventory is bugged" looked
like from the outside.

**Fix:** one matcher, namespace-insensitive (`netherite_pickaxe`, `minecraft:netherite_pickaxe` and
`NETHERITE PICKAXE` all match), used by all three verbs. The drop test now deliberately asks for
`diamond` without a namespace, so the regression cannot come back quietly.

## Death handover tells the truth

The same session's death reported *"only hand over 0"* of 230 items — your inventory was full — and he
announced "everything I was carrying is in your inventory." Losing the items is bad; lying about it is
worse. He now says exactly one of three things depending on what happened: everything went to you, N of
M went to you and the rest did not fit, or nothing fitted.

## Still open: he narrates actions he never queues

`break this obsidian` → "Breaking that obsidian now!" with no action, then "I'm on it! Let me switch to
my pickaxe…" with no action, twice. `come here` → "Coming right over!" with no follow action. Prompt
rule 10 did not stop it at this model size.

The honest fix is deterministic rather than hopeful: recognise the handful of imperative phrases a
player actually types (`wave`, `come here`, `drop …`, `mine …`) and queue the verb directly instead of
letting the model decide to narrate. That is the next piece of work, and it is the last thing standing
between Cobbie and "he does what he says".
