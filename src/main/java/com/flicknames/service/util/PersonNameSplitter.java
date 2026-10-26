package com.flicknames.service.util;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Splits an actor's or crew member's full name into the first and last name stored on Person.
 * The first name is the first word, so "Samuel L. Jackson" counts towards Samuel and "Mary Elizabeth
 * Winstead" towards Mary; a two-word given name ("Juan Carlos Fresnadillo") is kept whole when another
 * word follows it. The last name is the last word, with any particles before it ("Ludwig van
 * Beethoven" -> "van Beethoven", "Rob Van Norden" -> "Van Norden") and a generational suffix kept with
 * the word it follows ("Robert Downey Jr." -> "Downey Jr."). Middle names stay only in the full name.
 */
public final class PersonNameSplitter {

    private static final Set<String> SUFFIXES = Set.of("jr", "jr.", "sr", "sr.", "ii", "iii", "iv", "v");

    /** Particles in any case: "van", "Van". */
    private static final Set<String> PARTICLES = Set.of(
        "van", "von", "der", "den", "de", "del", "della", "dei", "degli", "di", "da", "das", "dos",
        "du", "la", "le", "les", "ter", "ten", "st", "st.", "mac");

    /** Particles only when lowercase, since capitalised they are as often given names: "Al", "Do", "El". */
    private static final Set<String> LOWERCASE_PARTICLES = Set.of("do", "bin", "ibn", "al", "el", "y", "zu", "af", "av");

    private static final BiPredicate<String, String> NO_COMPOUNDS = (a, b) -> false;

    private PersonNameSplitter() {
    }

    /** {first, last}; "Unknown" and "" for a blank name, the name and "" for a single word. */
    public static String[] split(String fullName) {
        return split(fullName, NO_COMPOUNDS);
    }

    /** As {@link #split(String)}, taking the first two words as the first name when {@code compound} says so. */
    public static String[] split(String fullName, BiPredicate<String, String> compound) {
        String[] words = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return new String[] {"Unknown", ""};
        }
        if (words.length == 1) {
            return new String[] {words[0], ""};
        }
        int firstWords = words.length >= 3 && compound.test(words[0], words[1]) ? 2 : 1;
        int start = words.length - 1;
        if (start > firstWords && SUFFIXES.contains(words[start].toLowerCase())) {
            start--;
        }
        while (start > firstWords && isParticle(words[start - 1])) {
            start--;
        }
        return new String[] {
            String.join(" ", Arrays.copyOfRange(words, 0, firstWords)),
            String.join(" ", Arrays.copyOfRange(words, start, words.length))
        };
    }

    private static boolean isParticle(String word) {
        return PARTICLES.contains(word.toLowerCase()) || LOWERCASE_PARTICLES.contains(word);
    }
}
