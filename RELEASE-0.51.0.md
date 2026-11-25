# Cobbie 0.51.0 — signs, frames, and the two pieces that need a player

Artifact: `cobbie-0.51.0.jar`. **190 unit tests green**, `CobbieDecorTest` and `CobbieCapabilityTest` green
in game.

The decorative tail, probed before it was built. The probe split four items that look like one slice into
three different problems, and only one of them needed code.

## What the probe found

```
sign:   use=Success(…wasItemInteraction=true)   ← vanilla opened an editor the actor cannot use
frame:  placed=Success frames=1
frame:  insert=Success holds=minecraft:diamond
frame:  use-empty=Success holds=minecraft:diamond ← the empty right-click does NOT take it out
beacon: blockEntity=BeaconBlockEntity use=Success block=Beacon ← accepted, changed nothing
banner: Items.BANNER is a ColorCollection, not an item — colour and patterns are components now
```

## Signs: a `write` verb

The one piece that cannot be a hand gesture, because vanilla's interaction opens a screen for a player. The
text rides as the intent's target and the sign as its position:

- writes onto the **first empty line**, leaving what is already written alone;
- wraps at vanilla's **fifteen characters a line**, breaking a long word rather than losing it;
- refuses text that needs more than four lines with the numbers in it — *"it holds 4 lines of 15 and this
  needs 5"* — rather than overwriting somebody's sign;
- strips `§` from incoming text, because a sign is not a place to smuggle formatting.

`write` sits under the **building** switch, like every other verb that changes the world with his hands, so
it is refused by name when the owner has not allowed it.

## Item frames: already a hand, with one exception

Placing a frame and putting an item in it are `use`, and they worked the first time. Taking the item back
out is the exception: the probe showed an empty right-click leaves the frame loaded, and the gesture that
does empty it is a **punch**. So an empty hand on a loaded frame now takes the item through the frame's own
drop — the same result the punch produces — and success is read off the frame, not off an interaction
result.

Frames needed one more honest change: `use` refused an empty hand before it ever reached a frame (*"I am not
holding anything to use"*), which is exactly the empty hand a take-out needs. The refusal now waits until
the entity branches have passed.

## Named, not faked

- **Beacons** are out of scope. Activation goes through a player's menu — the payment item is placed into a
  slot on a screen — and the actor's interaction returns success while changing nothing. Driving it would
  mean writing beacon state directly, which is a different claim than "he can light a beacon".
- **Patterned banners** are not in this slice. The banner's colour and patterns are item components in this
  version (`Items.BANNER` is a `ColorCollection`), and while vanilla's own placement should carry them onto
  the block, I have not verified that in game — so it is named as unverified rather than claimed.

## Evidence

```
decor ok: sign line 0 "welcome home", line 1 "second line"; over-long text refused with its reason;
          frame held minecraft:diamond and gave it back (1 diamond carried)
capability ok: 35 verbs reach an executor and appear in the prompt
```

The write is read back off the sign's own front text, and the frame's contents are read off the frame.

## Verification

- `cleanTest test`: **190 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=Decor,Capability`: **2 passed of 2**, `--no-build-cache --rerun-tasks`.
- Four failed runs preceded the first green one, and three were mine: reading a plan before it settled
  (twice) and a "too long" fixture of six short words that fitted four lines comfortably. The fourth was
  the empty-hand gate above, which is a real behaviour finding.
