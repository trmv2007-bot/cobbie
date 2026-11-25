# Cobbie 0.55.0 — an item described, not named

Artifact: `cobbie-0.55.0.jar`. **219 unit tests green** (9 new), `Describe`, `Use`, `Redstone`, `Anvil`,
`Brew` green in game.

The gap this project kept walking around: perception has said `minecraft:iron_axe x1 (sharpness 2)` for
several releases, but an action could only take a registry id — so *"put the sharpness book on my best sword"*
and *"use my worst pickaxe"* had no route. He could see the difference and could not say it.

## The rules, each weaker than the last

`ItemQuery` resolves a description against **his own bag** and nothing else:

1. an exact id — `iron_axe` or `minecraft:iron_axe`;
2. a custom name the owner gave it — *"old faithful"*;
3. an enchantment it carries — *"the sharpness book"*;
4. a stated property — most or least durable, most numerous.

## Two constraints, both about honesty

**Two candidates that match equally well are a question, never a pick.** The refusal lists what the choices
were, in the owner's words:

```
more than one of them is carrying "diamond sword" — which one? diamond sword x1, diamond sword x1
```

**Nothing resolves that he is not carrying**, because the search is his bag: *"netherite_axe"* answers *"I have
no netherite_axe with me"* rather than inventing one.

## One route, not two

`equip` and `use` used to scan the bag for a registry id, and the description path was a fallback bolted on
beside it. That produced the exact failure the rule exists to prevent: **two diamond swords, and the words
"diamond sword" spelled an id, so the first one was taken silently** — an enchanted sword and a plain one are
different items and the owner gets to say which. Both verbs now go through the resolver, so an id and a
description obey one rule and two matching stacks are a question either way.

## Evidence

```
describe ok: "worst pickaxe" took the worn one (3 uses), "diamond sword" (two of them) asked which one,
             and "netherite_axe" was refused as not carried
summary: 6 passed, 0 failed of 6 — {Describe, Use, Redstone, RedstoneProbe, Anvil, Brew}
```

The pickaxe assertion reads the **remaining durability** of what ended up in his hand (3 uses left), not that
something was equipped; the ambiguity assertion reads the refusal's own words; and the six classes are there
because both verbs changed and every item-using suite had to still pass.

## Not done, and named

The **verb-choice harness** — the measure that asks a model to describe a thing rather than name its id — is not
run here: it needs a live model and this run directory has none. What is proven is the resolver and its route
through two verbs; the harness score is the sentence I cannot write until the brain is in the loop.

## Also in this release

`docs/AUDIT.md` now names the one shape behind three of the week's defects, because it is the transferable part:
**a condition written for one caller, applied to all of them** — the tool gate that refused a log, the cooldown
that made the negatives vacuous, the empty-hand guard that refused every lever. The paragraph tells the next
reader to ask *who else calls this?* at the moment a condition is narrowed.
