package com.flicknames.service.util.charactername;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Word lists used by {@link CharacterNameParserV2}, loaded once from versioned classpath
 * resources under {@code parser/v2/}. Entries are stored accent-folded and lowercased, see
 * {@link #fold(String)}. Immutable after construction, so it can be shared between threads.
 */
public final class CharacterNameLexicon {

    public static final String RESOURCE_DIR = "parser/v2/";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static volatile CharacterNameLexicon defaultInstance;

    private final Map<String, Integer> givenNames;
    private final Map<String, Integer> surnames;
    private final Map<String, Integer> personGivenNames;
    private final Set<String> englishWords;
    private final Set<String> stopwords;
    private final Set<String> personNouns;
    private final Set<String> roleNouns;
    private final Set<String> occupationalSurnames;
    private final Set<String> titlesSurname;
    private final Set<String> titlesGiven;
    private final Set<String> agePrefixes;
    private final Set<String> adjectives;
    private final Set<String> demonyms;
    private final Set<String> articles;
    private final Set<String> particles;
    private final Set<String> cjkSurnames;
    private final int longestTitleWords;

    private CharacterNameLexicon(ClassLoader loader) {
        Map<String, Integer> names = new HashMap<>();
        readLines(loader, "given-names.tsv", line -> {
            int tab = line.indexOf('\t');
            names.merge(fold(line.substring(0, tab)), Integer.parseInt(line.substring(tab + 1).trim()), Integer::sum);
        });
        this.givenNames = Map.copyOf(names);
        Map<String, Integer> family = new HashMap<>();
        readLines(loader, "surnames.tsv", line -> {
            int tab = line.indexOf('\t');
            family.merge(fold(line.substring(0, tab)), Integer.parseInt(line.substring(tab + 1).trim()), Integer::sum);
        });
        this.surnames = Map.copyOf(family);
        Map<String, Integer> people = new HashMap<>();
        readLines(loader, "person-given-names.tsv", line -> {
            int tab = line.indexOf('\t');
            people.merge(fold(line.substring(0, tab)), Integer.parseInt(line.substring(tab + 1).trim()), Integer::sum);
        });
        this.personGivenNames = Map.copyOf(people);
        this.englishWords = readSet(loader, "english-words.txt");
        this.stopwords = readSet(loader, "stopwords.txt");
        this.personNouns = readSet(loader, "person-nouns.txt");
        this.roleNouns = readSet(loader, "role-nouns.txt");
        this.occupationalSurnames = readSet(loader, "occupational-surnames.txt");
        this.titlesSurname = readSet(loader, "titles-surname.txt");
        this.titlesGiven = readSet(loader, "titles-given.txt");
        this.agePrefixes = readSet(loader, "prefixes-age-kin.txt");
        this.adjectives = readSet(loader, "adjectives.txt");
        this.demonyms = readSet(loader, "demonyms.txt");
        this.articles = readSet(loader, "articles.txt");
        this.particles = readSet(loader, "particles.txt");
        this.cjkSurnames = readSet(loader, "surnames-cjk.txt");
        this.longestTitleWords = titlesSurname.stream().mapToInt(t -> t.split(" ").length).max().orElse(1);
    }

    /** The lexicon shipped with the application (loaded lazily, then cached). */
    public static CharacterNameLexicon loadDefault() {
        CharacterNameLexicon lexicon = defaultInstance;
        if (lexicon == null) {
            synchronized (CharacterNameLexicon.class) {
                lexicon = defaultInstance;
                if (lexicon == null) {
                    lexicon = new CharacterNameLexicon(CharacterNameLexicon.class.getClassLoader());
                    defaultInstance = lexicon;
                }
            }
        }
        return lexicon;
    }

    /** Lowercase, accent-free form used for every lookup: "Célestine" -> "celestine". */
    public static String fold(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("")
            .replace('ı', 'i').replace('ł', 'l').replace('ø', 'o').replace('ß', 's')
            .toLowerCase(Locale.ROOT);
    }

    /**
     * How many actors and crew in the database have this folded word as the first word of their name
     * (0 when fewer than two do). Covers given names from every country, unlike the SSA counts.
     */
    public int personGivenNameCount(String folded) {
        return personGivenNames.getOrDefault(folded, 0);
    }

    /** US Census 2010 bearers of a folded surname (0 when unknown or under 500). */
    public int surnameCount(String folded) {
        return surnames.getOrDefault(folded, 0);
    }

    /** SSA birth count for a folded given name (0 when unknown). Hyphenated names fall back to their first part. */
    public int givenNameCount(String folded) {
        String key = folded.replaceAll("[^\\p{L}-]", "");
        Integer count = givenNames.get(key.replace("-", ""));
        if (count == null && key.indexOf('-') >= 3) {
            String firstPart = key.substring(0, key.indexOf('-'));   // "Jean-Bob" -> jean, but not "Can-do" -> can
            count = englishWords.contains(firstPart) ? null : givenNames.get(firstPart);
        }
        return count == null ? 0 : count;
    }

    public boolean isEnglishWord(String folded) {
        return englishWords.contains(folded);
    }

    public boolean isStopword(String folded) {
        return stopwords.contains(folded) || personNouns.contains(folded);
    }

    public boolean isPersonNoun(String folded) {
        return inflected(personNouns, folded);
    }

    public boolean isRoleNoun(String folded) {
        return inflected(roleNouns, folded);
    }

    /** A role or person noun that can head a description: "Guide" in "Swamp Tour Guide". */
    public boolean isRoleHead(String folded) {
        return isRoleNoun(folded) || isPersonNoun(folded) || demonyms.contains(folded);
    }

    public boolean isOccupationalSurname(String folded) {
        return occupationalSurnames.contains(folded);
    }

    public boolean isSurnameTitle(String folded) {
        return titlesSurname.contains(folded);
    }

    public boolean isGivenTitle(String folded) {
        return titlesGiven.contains(folded);
    }

    public boolean isAgeOrKinPrefix(String folded) {
        return agePrefixes.contains(folded);
    }

    public boolean isAdjective(String folded) {
        return adjectives.contains(folded);
    }

    public boolean isDemonym(String folded) {
        return demonyms.contains(folded);
    }

    public boolean isArticle(String folded) {
        return articles.contains(folded);
    }

    public boolean isParticle(String folded) {
        return particles.contains(folded);
    }

    public boolean isCjkSurname(String folded) {
        return cjkSurnames.contains(folded);
    }

    public int longestTitleWords() {
        return longestTitleWords;
    }

    /** Matches plural forms too: guards -> guard, henchmen -> henchman, ladies -> lady. */
    private static boolean inflected(Set<String> set, String w) {
        if (set.contains(w)) {
            return true;
        }
        if (w.endsWith("men") && set.contains(w.substring(0, w.length() - 3) + "man")) {
            return true;
        }
        if (w.endsWith("ies") && set.contains(w.substring(0, w.length() - 3) + "y")) {
            return true;
        }
        if (w.endsWith("es") && set.contains(w.substring(0, w.length() - 2))) {
            return true;
        }
        return w.length() > 3 && w.endsWith("s") && set.contains(w.substring(0, w.length() - 1));
    }

    private static Set<String> readSet(ClassLoader loader, String file) {
        Set<String> set = new HashSet<>();
        readLines(loader, file, line -> set.add(fold(line.trim())));
        return Set.copyOf(set);
    }

    private static void readLines(ClassLoader loader, String file, Consumer<String> sink) {
        String path = RESOURCE_DIR + file;
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing parser lexicon resource: " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    sink.accept(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }
}
