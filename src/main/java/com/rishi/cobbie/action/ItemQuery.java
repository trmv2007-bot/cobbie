package com.rishi.cobbie.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What he is carrying, described as a person would describe it.
 *
 * <p>Perception has said `minecraft:iron_axe x1 (sharpness 2)` for a while, but an action could only name a
 * registry id — so *"put the sharpness book on my best sword"* and *"use my worst pickaxe"* had no route: he
 * could see the difference and could not say it. This resolves a description against his own bag.
 *
 * <p>The rules, in order, because each is weaker than the one before it:
 *
 * <ol>
 *   <li>an exact registry id, `iron_axe` or `minecraft:iron_axe`;</li>
 *   <li>a custom name the owner gave it;</li>
 *   <li>an enchantment it carries, by name;</li>
 *   <li>a stated property — most or least durable, most numerous.</li>
 * </ol>
 *
 * <p><b>Two constraints, both about honesty rather than convenience:</b> two candidates that match equally
 * well are a refusal that lists them, never a silent pick — a companion who hands over the wrong sword and
 * says nothing is worse than one who asks which sword — and nothing is ever resolved that he is not carrying,
 * because the search is his bag and nothing else.
 *
 * <p>Pure on purpose: the rules are arithmetic over descriptions, so the unit tier can test every ambiguity
 * without a world.
 */
public final class ItemQuery {

    /** One thing in his bag, reduced to what a description can refer to. */
    public record Candidate(String id, String customName, int count, List<String> enchantments,
            int damage, int maxDamage) {

        public Candidate {
            id = id == null ? "" : id.toLowerCase(Locale.ROOT);
            customName = customName == null ? "" : customName;
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        }

        /** The bare name, without the namespace: what a person says. */
        public String shortId() {
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }

        public int durabilityLeft() {
            return Math.max(0, maxDamage - damage);
        }

        public boolean isDamageable() {
            return maxDamage > 0;
        }
    }

    /** The answer: one candidate, or a refusal that says why — and, when it is ambiguity, lists them. */
    public record Result(Candidate chosen, String refusal, List<Candidate> candidates) {
        public boolean ok() {
            return chosen != null;
        }

        public static Result of(Candidate candidate) {
            return new Result(candidate, null, List.of(candidate));
        }

        public static Result refused(String why, List<Candidate> near) {
            return new Result(null, why, near == null ? List.of() : List.copyOf(near));
        }
    }

    private ItemQuery() {}

    public static Result resolve(String description, List<Candidate> bag) {
        if (bag == null || bag.isEmpty()) {
            return Result.refused("I am not carrying anything", List.of());
        }
        String asked = description == null ? "" : description.trim().toLowerCase(Locale.ROOT);
        if (asked.isEmpty()) return Result.refused("no item was named", List.of());

        List<Candidate> byId = new ArrayList<>();
        for (Candidate candidate : bag) {
            if (candidate.id().equals(asked) || candidate.id().equals("minecraft:" + asked)
                    || candidate.shortId().equals(asked)) {
                byId.add(candidate);
            }
        }
        Result byName = pick("named", byId, asked);
        if (byName != null) return byName;

        // A custom name: "the sharpness book" is what the owner called it, not what the game calls it.
        List<Candidate> named = new ArrayList<>();
        for (Candidate candidate : bag) {
            if (!candidate.customName().isBlank()
                    && candidate.customName().toLowerCase(Locale.ROOT).contains(asked)) {
                named.add(candidate);
            }
        }
        Result byCustom = pick("named", named, asked);
        if (byCustom != null) return byCustom;

        // An enchantment: "the sharpness book" when the name is the vanilla one, and "my sharpness sword".
        List<Candidate> enchanted = new ArrayList<>();
        for (Candidate candidate : bag) {
            for (String enchantment : candidate.enchantments()) {
                if (enchantment.toLowerCase(Locale.ROOT).contains(asked)) {
                    enchanted.add(candidate);
                    break;
                }
            }
        }
        Result byEnchantment = pick("enchanted", enchanted, asked);
        if (byEnchantment != null) return byEnchantment;

        // Loose descriptions: a word that is part of the item's name plus an order, or an order alone.
        List<Candidate> loose = new ArrayList<>();
        String noun = "";
        for (String word : asked.split("\\s+")) {
            if (ORDER.WORST.equals(word) || ORDER.BEST.equals(word) || ORDER.MOST.equals(word)
                    || ORDER.LEAST.equals(word) || ORDER.MOST_DURABLE.equals(word)
                    || "durable".equals(word) || "numerous".equals(word)) {
                continue;
            }
            noun = word;
        }
        if (!noun.isEmpty()) {
            for (Candidate candidate : bag) {
                if (candidate.shortId().contains(noun)
                        || candidate.customName().toLowerCase(Locale.ROOT).contains(noun)) {
                    loose.add(candidate);
                }
            }
        } else {
            loose.addAll(bag);
        }
        if (loose.isEmpty()) {
            return Result.refused("I have no " + description.trim() + " with me", List.of());
        }
        Candidate ordered = order(asked, loose);
        if (ordered != null) return Result.of(ordered);
        Result byLoose = pick("carrying", loose, asked);
        return byLoose != null ? byLoose
                : Result.refused("I have no " + description.trim() + " with me", List.of());
    }

    /** Words that ask for the best or the worst of a set rather than one of them. */
    private static final class ORDER {
        static final String WORST = "worst";
        static final String BEST = "best";
        static final String MOST = "most";
        static final String LEAST = "least";
        static final String MOST_DURABLE = "most-durable";
    }

    private static Candidate order(String asked, List<Candidate> candidates) {
        boolean worst = asked.contains(ORDER.WORST) || asked.contains(ORDER.LEAST);
        boolean best = asked.contains(ORDER.BEST) || asked.contains(ORDER.MOST);
        if (!worst && !best) return null;

        boolean byCount = asked.contains(ORDER.MOST) && asked.contains("numerous");
        Comparator<Candidate> comparator = byCount
                ? Comparator.comparingInt(Candidate::count)
                : Comparator.comparingInt(ItemQuery::rankingDurability);
        if (worst) comparator = comparator.reversed();
        return candidates.stream().max(comparator).orElse(null);
    }

    /** A damaged stack ranks below a fresh one; something that cannot be damaged at all ranks at the top. */
    private static int rankingDurability(Candidate candidate) {
        return candidate.isDamageable() ? candidate.durabilityLeft() : Integer.MAX_VALUE / 2;
    }

    /**
     * One candidate is an answer, two are a question. Null means "nothing matched here, try the next rule".
     */
    private static Result pick(String how, List<Candidate> matches, String asked) {
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return Result.of(matches.get(0));
        return Result.refused("more than one of them is " + how + " \"" + asked + "\" — which one? "
                + describe(matches), matches);
    }

    /** A short list for the owner: what the candidates are and how many he has. */
    public static String describe(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (sb.length() > 0) sb.append(", ");
            // Read by the owner, so the id is written the way a person says it: "diamond sword", not
            // "diamond_sword".
            String name = candidate.customName().isBlank()
                    ? candidate.shortId().replace('_', ' ')
                    : candidate.customName();
            sb.append(name).append(" x").append(candidate.count());
        }
        return sb.toString();
    }
}
