# Cobbie 0.67.0 — "put this book on my sword", end to end

Artifact: `cobbie-0.67.0.jar`. **223 unit tests green**, `AnvilEnd` and `Anvil` green in game.

```
anvil end ok: "put this book on my sword" completed — enchanted=true levels=9 books=0 —
              anvil my sword [done] — put an enchantment on minecraft:iron_sword for 3 level(s)
anvil choice ok: a book the item cannot take refused by name, and two equal books asked as a question —
              more than one of my books would go on the iron sword — which one? sharpness 1, sharpness 1
```

## The real defect, which was not the one I hunted

The anvil was **reporting failure on work that had succeeded**: after applying the book it checked the actor's
`remaining()` for an enchantment, and on a working run that stack was **air** — the output is taken from the menu
after it has been cleared. So the sword came out enchanted, three levels were spent, and the step said *"the anvil
returned nothing enchanted"*, then retried and reported a second, wrong reason.

Success is now read off **what he carries**, which is the same rule the item frame taught: the receipt is the world,
never the interaction's answer.

That also resolves the mystery that cost this slice two false starts: the compatibility question answered *true*
everywhere it was printed, because compatibility was innocent all along. The probe that settled it printed the whole
candidate and the target the step carried, and showed the first attempt resolving — the retry was the failure.

## The other two rules, both from the week's record

- **The item gate is not `isEnchantable()`**: that answers false for an item already enchanted, so a retry reported
  *"nothing a book would improve"* about the very item it had just improved. A book can go on anything damageable.
- **The book is chosen by the game's own rule** — an enchantment's `canEnchant` — never a hand-written list of what
  suits a sword. Among the books that fit, a new enchantment beats a duplicate, then the stronger wins; **a genuine
  tie is the owner's question** (above), and **no fitting book is a refusal that names the books he has** rather than
  a silent pick of the first one in the bag.

## Verification

- `cleanTest test`: **223 tests, 0 failed, 6 skipped**.
- `runClientGameTest -PcobbieTests=AnvilEnd,Anvil`: **2 passed of 2**, `--no-build-cache --rerun-tasks`; three
  cases — the description end to end, a mismatched book refused by name, and a tie put to the owner.
