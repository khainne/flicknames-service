package com.flicknames.service.util;

import java.util.Arrays;
import java.util.Set;

/**
 * Splits an actor's or crew member's full name into the first and last name stored on Person.
 * The first name is the first word, so "Samuel L. Jackson" counts towards Samuel and "Mary Kate
 * Olsen" towards Mary. The last name is the last word, with any lowercase particles before it
 * ("Ludwig van Beethoven" -> "van Beethoven") and a generational suffix kept with the word it
 * follows ("Robert Downey Jr." -> "Downey Jr."). Middle names stay only in the full name.
 */
public final class PersonNameSplitter {

    private static final Set<String> SUFFIXES = Set.of("jr", "jr.", "sr", "sr.", "ii", "iii", "iv", "v");

    private static final Set<String> PARTICLES = Set.of(
        "van", "von", "der", "den", "de", "del", "della", "dei", "degli", "di", "da", "das", "dos", "do",
        "du", "la", "le", "les", "ter", "ten", "bin", "ibn", "al", "el", "y", "zu", "af", "av");

    private PersonNameSplitter() {
    }

    /** {first, last}; "Unknown" and "" for a blank name, the name and "" for a single word. */
    public static String[] split(String fullName) {
        String[] words = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return new String[] {"Unknown", ""};
        }
        if (words.length == 1) {
            return new String[] {words[0], ""};
        }
        int start = words.length - 1;
        if (start > 1 && SUFFIXES.contains(words[start].toLowerCase())) {
            start--;
        }
        while (start > 1 && PARTICLES.contains(words[start - 1])) {
            start--;
        }
        return new String[] {words[0], String.join(" ", Arrays.copyOfRange(words, start, words.length))};
    }
}
