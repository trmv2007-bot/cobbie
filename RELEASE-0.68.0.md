# Cobbie 0.68.0 — the answer that was lost crossing a portal

Artifact: `cobbie-0.68.0.jar`.

You ask him something, then step through a portal. He thinks for a few seconds, then says nothing at
all — no answer, no error, no line. That was a real hole, and it was one word wide.

## Died is not replaced

A crossing replaces the companion: `TeleportTransition` returns a new entity instance and removes the
old one, keeping the UUID. A model reply is composed against one particular instance and can take
seconds, so a portal taken inside that window means the reply is aimed at an object that no longer
exists. The guard meant to stop him answering after death was:

```java
if (body.isRemoved() || player.isRemoved()) return;
```

which cannot tell **died** from **replaced**, so it treated the second as the first and threw the
answer away in silence. He is not meant to speak after dying — that guard is deliberate and stays — but
a companion standing in the nether is not a dead companion.

`reattach` asks the right question instead: *is there a live companion with this UUID?*

```java
public static CobbieEntity reattach(CobbieEntity body, ServerPlayer player) {
    if (body == null) return null;
    if (!body.isRemoved()) return body;
    ... for each level: level.getEntity(id) instanceof CobbieEntity alive ? that : next
    return null;
}
```

Same answer as before for a death (nothing, so he stays quiet), the right answer for a replacement
(the new instance, so the reply lands). `deliver` routes through it before anything else, and the
speaker is a local so the reply's own callbacks cannot close over the stale body.

## Verification

```
[gametest] reattach ok across a crossing: live=the_nether sameInstance=false sameUuid=true alive=true
[gametest] reattach ok after death: nothing to deliver
```

Both endings, driven through the shipped function rather than around it. The first line is also the
proof that the two cases are genuinely different objects with the same identity, which is the whole
premise; the second is the promise that the death case still says nothing.

**What is not verified:** a real model reply surviving a crossing. That needs a configured provider
and this harness has none, so the routing is exercised directly and the model path is *inferred* from
the two facts above. It is the honest label: the defect's cause is proven, its visible symptom is not
observed by a machine.

**Units:** 222 passed, 6 skipped, 0 failures. **In game:** the class count and failures are in the
pull request, read out of the summary.

## The neighbour audit, for the record

Eight places store something about him; with the UUID confirmed to survive a crossing, exactly **two**
were holding the instance rather than the identifier, both in `CobbieBrain` and both time windows
rather than registries:

- the chat batch (`Batch.body`, a 350 ms window) — it reaches the model through `deliver`, so it is
  covered by this fix;
- the in-flight reply (`deliver`'s captured body, seconds) — the one this release fixes.

Everything else — `CrossDimensionFollow.WATCHES`, `lastChatAt`, `CobbieMemory.memories`,
`PENDING_DROPS`, and the stateless `Initiative`/`SkillExecutor`/`WorldPerception` — is keyed by UUID or
holds no entity at all, and survives a crossing untouched.
