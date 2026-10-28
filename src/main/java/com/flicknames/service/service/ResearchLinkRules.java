package com.flicknames.service.service;

import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.util.charactername.CharacterNameLexicon;

import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Turns the related names on approved research cards into directed links. The research types are written
 * from the card's point of view, and earlier cards used VARIANT and COGNATE for short forms too ("Nick"
 * lists Nicholas as VARIANT, "Johnny" lists John as COGNATE), so the direction is recovered as follows:
 * <ul>
 *   <li>SHORT_FORM_OF on card S: S DIMINUTIVE_OF R, accepted.</li>
 *   <li>DIMINUTIVE on card S: R DIMINUTIVE_OF S, accepted; more confident when R's card agrees.</li>
 *   <li>VARIANT or COGNATE: if R's card lists S as a DIMINUTIVE, S DIMINUTIVE_OF R, accepted. Otherwise, if
 *       one name looks like a short form of the other (at least three letters shorter, same first three
 *       letters: Chris/Christopher, Alex/Alexander), a proposed DIMINUTIVE_OF for review. Otherwise a
 *       SPELLING_VARIANT (accepted only when the two look like spellings of each other) or a COGNATE,
 *       accepted; a COGNATE between spellings that also have a SPELLING_VARIANT becomes one.</li>
 *   <li>FEMININE_FORM / MASCULINE_FORM: R FEMININE_OF (MASCULINE_OF) S, accepted only when the SSA births
 *       lean that way at both ends, else proposed.</li>
 * </ul>
 * Names that fold to the same key (José, Jose) are not linked: that match is implicit.
 */
public final class ResearchLinkRules {

    public record Relation(long researchId, String card, String related, String type) {}

    public record Candidate(String from, String to, Type type, Status status, int confidence, String evidenceRef) {}

    public interface SexCounts {
        long male(String spelling);

        long female(String spelling);
    }

    private ResearchLinkRules() {
    }

    public static List<Candidate> derive(List<Relation> relations, SexCounts ssa) {
        Map<String, Map<String, Set<String>>> cards = new HashMap<>();
        for (Relation r : relations) {
            cards.computeIfAbsent(fold(r.card()), k -> new HashMap<>())
                .computeIfAbsent(fold(r.related()), k -> new HashSet<>()).add(r.type());
        }
        List<Candidate> out = new ArrayList<>();
        for (Relation r : relations) {
            String s = r.card().trim();
            String rel = r.related() == null ? "" : r.related().trim();
            if (rel.isEmpty() || fold(s).equals(fold(rel))) {
                continue;
            }
            String ref = "research:" + r.researchId();
            boolean rListsSAsDiminutive = listed(cards, rel, s, "DIMINUTIVE");
            switch (r.type()) {
                case "SHORT_FORM_OF" -> out.add(new Candidate(s, rel, Type.DIMINUTIVE_OF, Status.ACCEPTED, 80, ref));
                case "DIMINUTIVE" -> {
                    boolean agreed = listed(cards, rel, s, "SHORT_FORM_OF") || listed(cards, rel, s, "VARIANT")
                        || listed(cards, rel, s, "COGNATE");
                    out.add(new Candidate(rel, s, Type.DIMINUTIVE_OF, Status.ACCEPTED, agreed ? 85 : 70, ref));
                }
                case "VARIANT", "COGNATE" -> {
                    if (rListsSAsDiminutive) {
                        out.add(new Candidate(s, rel, Type.DIMINUTIVE_OF, Status.ACCEPTED, 85, ref));
                    } else if (looksShortFormOf(s, rel)) {
                        out.add(new Candidate(s, rel, Type.DIMINUTIVE_OF, Status.PROPOSED, 40, ref));
                    } else if (looksShortFormOf(rel, s)) {
                        out.add(new Candidate(rel, s, Type.DIMINUTIVE_OF, Status.PROPOSED, 40, ref));
                    } else if (r.type().equals("VARIANT")) {
                        boolean alike = looksLikeSpellingOf(s, rel);
                        out.add(new Candidate(s, rel, Type.SPELLING_VARIANT, alike ? Status.ACCEPTED : Status.PROPOSED,
                            alike ? 70 : 40, ref));
                    } else {
                        out.add(new Candidate(s, rel, Type.COGNATE, Status.ACCEPTED, 60, ref));
                    }
                }
                case "FEMININE_FORM" -> out.add(gendered(rel, s, Type.FEMININE_OF,
                    leans(ssa::female, ssa::male, rel) && leans(ssa::male, ssa::female, s), ref));
                case "MASCULINE_FORM" -> out.add(gendered(rel, s, Type.MASCULINE_OF,
                    leans(ssa::male, ssa::female, rel) && leans(ssa::female, ssa::male, s), ref));
                default -> { }
            }
        }
        return cognatesBetweenVariantsAreVariants(out);
    }

    /**
     * Two spellings of one name differ in at most two letters in five (Jason/Jayson, Katherine/Catherine,
     * Kathryn/Katherine); a "variant" further apart (Collin listing Nicholas) is only proposed.
     */
    static boolean looksLikeSpellingOf(String a, String b) {
        String fa = fold(a);
        String fb = fold(b);
        return editDistance(fa, fb) * 5 <= 2 * Math.max(fa.length(), fb.length());
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** a looks like a short form of b: at least three letters shorter, sharing its first three letters. */
    static boolean looksShortFormOf(String a, String b) {
        String fa = fold(a);
        String fb = fold(b);
        return fa.length() >= 2 && fa.length() + 3 <= fb.length()
            && fb.startsWith(fa.substring(0, Math.min(3, fa.length())));
    }

    private static boolean listed(Map<String, Map<String, Set<String>>> cards, String card, String related, String type) {
        return cards.getOrDefault(fold(card), Map.of()).getOrDefault(fold(related), Set.of()).contains(type);
    }

    private static boolean leans(ToLongFunction<String> more, ToLongFunction<String> less, String spelling) {
        return more.applyAsLong(spelling) > less.applyAsLong(spelling);
    }

    private static Candidate gendered(String from, String to, Type type, boolean supported, String ref) {
        return new Candidate(from, to, type, supported ? Status.ACCEPTED : Status.PROPOSED, supported ? 70 : 40, ref);
    }

    /** Nicholas lists Nicolas as VARIANT and Nicolás as COGNATE: both are the same spelling variant. */
    private static List<Candidate> cognatesBetweenVariantsAreVariants(List<Candidate> links) {
        Set<String> variantPairs = new HashSet<>();
        for (Candidate c : links) {
            if (c.type() == Type.SPELLING_VARIANT) {
                variantPairs.add(pair(c.from(), c.to()));
            }
        }
        return links.stream().map(c -> c.type() == Type.COGNATE && variantPairs.contains(pair(c.from(), c.to()))
            ? new Candidate(c.from(), c.to(), Type.SPELLING_VARIANT, c.status(), c.confidence(), c.evidenceRef())
            : c).toList();
    }

    private static String pair(String a, String b) {
        String fa = fold(a);
        String fb = fold(b);
        return fa.compareTo(fb) <= 0 ? fa + "|" + fb : fb + "|" + fa;
    }

    private static String fold(String s) {
        return CharacterNameLexicon.fold(s == null ? "" : s.trim());
    }
}
