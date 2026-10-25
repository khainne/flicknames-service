package com.flicknames.service.util.charactername;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version 2 of the character-name parser: turns a free-text TMDB character credit
 * ("Peter Parker / Spider-Man", "Young Anna", "Self - Host", "Cop #2") into the given first name a
 * parent could use, or {@code null}.
 *
 * <p>Pure and deterministic: no database or network access at parse time. All word lists come from
 * versioned resources under {@code src/main/resources/parser/v2} (see {@link CharacterNameLexicon}).
 * The pipeline is:
 * <ol>
 *   <li>normalise (NFC, quotes, dashes, whitespace, ALL-CAPS / all-lowercase input)</li>
 *   <li>extract annotations: "(voice)", "(as X)", "[...]", self credits</li>
 *   <li>split multi-role credits on "/", "|", ";", " aka ", " or "</li>
 *   <li>per segment: drop " - description" and ", description", pull out a quoted nickname,
 *       then classify (self, numbered, possessive, lowercase descriptor, article, acronym, age/kin
 *       prefix, given-name title, surname title, name + description, head-noun role, surname-first)</li>
 *   <li>validate the candidate against the given-name lexicon and stoplists, which sets confidence</li>
 *   <li>choose the most confident segment that has a name, and fix its casing</li>
 * </ol>
 *
 * <p>Runs side by side with {@link com.flicknames.service.util.CharacterNameParser}; nothing in the
 * application calls it yet.
 */
public final class CharacterNameParserV2 {

    /** Bumped whenever parsing logic or a lexicon file changes behaviour. */
    public static final String VERSION = "2.1.0";

    // SSA birth-count bands for the given-name lexicon
    private static final int COMMON = 1000;
    private static final int KNOWN = 50;
    private static final int RARE = 5;
    /** An English word is treated as a word, not a name, unless it has at least this many SSA births ("Hope", "Sue"). */
    private static final int WORD_AS_NAME = 500;
    /** A role noun this popular as a baby name is still accepted as a single-word name ("Hunter", "Angel"). */
    private static final int ROLE_AS_NAME = 50_000;
    /** A surname-type title this popular as a given name is ambiguous before a single word ("Justice Smith"). */
    private static final int TITLE_AS_NAME = 20_000;

    private static final Pattern SINGLE_QUOTES = Pattern.compile("[\u2018\u2019\u201A\u201B\u2032`\u00B4]");
    private static final Pattern DOUBLE_QUOTES = Pattern.compile("[\u201C\u201D\u201E\u201F\u2033\u00AB\u00BB]");
    private static final Pattern DASHES = Pattern.compile("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\p{Cc}\\p{Cf}]+");

    private static final Pattern BRACKETED = Pattern.compile("\\s*[(\\[]([^()\\[\\]]*)[)\\]]");
    private static final Pattern UNCLOSED_BRACKET = Pattern.compile("\\s*[(\\[][^)\\]]*$");
    private static final Pattern STRAY_BRACKETS = Pattern.compile("[()\\[\\]{}]");
    private static final Pattern AS_CREDIT = Pattern.compile("(?i)^\\s*as\\s+\\S.*");

    private static final Pattern SELF = Pattern.compile(
        "(?i)^(?:self|himself|herself|themselves|themself|itself|myself|yourself|ourselves)(?=$|[\\s,:/(-])");
    private static final Pattern ROLE_SPLIT = Pattern.compile(
        "\\s*[/|;\\\\]\\s*|\\s+(?:(?i:aka|a\\.k\\.a\\.?)|or)\\s+");
    private static final Pattern DASH_DESCRIPTION = Pattern.compile("\\s+-+\\s+.*$");
    private static final Pattern SUFFIX_AFTER_COMMA = Pattern.compile("(?i)(?:jr|sr|ii|iii|iv)\\.?");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("(?<![\\p{L}\\p{N}])'([^']+)'(?![\\p{L}\\p{N}])");
    private static final Pattern LEADING_JUNK = Pattern.compile("^[\\s,;:!?*~.\"'-]+");
    private static final Pattern TRAILING_JUNK = Pattern.compile("[\\s,;:!?*~\"'-]+$");

    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?i)^(?:#|no\\.?)?\\d+(?:st|nd|rd|th|\\.)?$|^#\\S+$");
    private static final Pattern POSSESSIVE = Pattern.compile("^\\p{L}.*(?:'s|s')$");
    private static final Pattern ACRONYM = Pattern.compile("^(?:\\p{Lu}\\.){3,}$|^\\p{Lu}{2,6}\\d*$|^\\p{Lu}{2,}\\.$");
    private static final Pattern VOWELLESS = Pattern.compile("^[b-df-hj-np-tv-xz]{2,5}[.,:;]*$");
    private static final Set<String> CONNECTORS = Set.of(
        "the", "of", "at", "in", "on", "with", "from", "for", "and", "to", "by", "as",
        "de", "del", "della", "di", "da", "du", "des", "van", "von", "der", "le", "la", "el", "y");
    private static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");
    private static final Pattern NAME_SHAPE = Pattern.compile("^\\p{L}{2,}(?:['-]\\p{L}+)*\\.?$|^\\p{L}'\\p{L}{2,}$");
    private static final Pattern INITIAL = Pattern.compile("^(?:\\p{L}\\.?){1,2}$|^(?:\\p{L}\\.){1,3}$");
    private static final Pattern AGENTIVE_SUFFIX = Pattern.compile(".{3,}(?:er|or|ist|ian|ant|ent|eer)$");
    private static final Pattern COMPOUND_PERSON = Pattern.compile(".{2,}(?:man|woman|men|women|boy|girl|guy)$");
    /** Endings that make an unknown single word a role, not a name: "Stormtrooper", "Townsperson", "Neurofisiologo". */
    private static final Pattern ROLE_SUFFIX = Pattern.compile(
        ".{3,}(?:person|people|folk|trooper|ologo|ologa|ologist|ista|iste|eur|euse|ante|ier|iere|ière)$");
    /** Up to four letters with two different consonants together: "EMT", "FBI" (not "PACO", "TOPP"). */
    private static final Pattern CAPS_WORD = Pattern.compile("^(?=[A-Z]{2,4}$).*([B-DF-HJ-NP-TV-Z])(?!\\1)[B-DF-HJ-NP-TV-Z].*$");
    /** A long unknown word ending like an agent noun: "Stormtrooper", "Neuroscientist" (not "Zafer", "Lambor"). */
    private static final Pattern LONG_AGENTIVE = Pattern.compile(".{5,}(?:er|ist|eer)$");
    private static final Pattern PINYIN = Pattern.compile(
        "(?:(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])?(?:iang|iong|uang|ang|eng|ing|ong|ian|iao|uai|uan|ai|ei|ao|ou|an|en|in|un|ia|ie|iu|ua|uo|ue|ui|er|a|e|i|o|u)){2,3}");
    private static final Pattern KOREAN_GIVEN = Pattern.compile("^\\p{Lu}\\p{Ll}{1,5}-\\p{L}\\p{Ll}{0,5}$");

    private final CharacterNameLexicon lexicon;

    public CharacterNameParserV2() {
        this(CharacterNameLexicon.loadDefault());
    }

    public CharacterNameParserV2(CharacterNameLexicon lexicon) {
        this.lexicon = lexicon;
    }

    /** Parse one character credit. Never throws; a null or blank input yields an EMPTY result. */
    public CharacterNameParseResult parse(String input) {
        List<ParseReason> reasons = new ArrayList<>();
        if (input == null || input.isBlank()) {
            reasons.add(ParseReason.EMPTY_INPUT);
            return result(input, "", "", null, NameTypeV2.EMPTY, 1.0, reasons, null);
        }

        String cleaned = removeAnnotations(normalise(input), reasons);
        if (isUniformCase(cleaned) && CAPS_WORD.matcher(cleaned.toUpperCase(Locale.ROOT)).matches()
                && lexicon.givenNameCount(CharacterNameLexicon.fold(cleaned)) < RARE) {
            reasons.add(ParseReason.ACRONYM);   // "EMT" would otherwise be recased to the name "Emt"
            return result(input, cleaned, cleaned, null, NameTypeV2.ROLE_DESCRIPTION, 0.85, reasons, null);
        }
        if (isUniformCase(cleaned)) {
            cleaned = recase(cleaned);
            reasons.add(ParseReason.UNIFORM_CASE_RECASED);
        }

        if (SELF.matcher(cleaned).find()) {
            reasons.add(ParseReason.SELF_CREDIT);
            return result(input, cleaned, cleaned, null, NameTypeV2.SELF, 0.98, reasons, null);
        }

        List<String> segments = new ArrayList<>();
        for (String part : ROLE_SPLIT.split(cleaned)) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }
        if (segments.isEmpty()) {
            reasons.add(ParseReason.EMPTY_INPUT);
            return result(input, cleaned, "", null, NameTypeV2.EMPTY, 0.9, reasons, null);
        }
        if (segments.size() > 1) {
            reasons.add(ParseReason.MULTI_ROLE);
        }

        List<SegmentResult> parsed = new ArrayList<>();
        for (String segment : segments) {
            SegmentResult r = parseSegment(segment);
            if (r.type == NameTypeV2.SELF) {
                reasons.addAll(r.reasons);
                return result(input, cleaned, r.text, null, NameTypeV2.SELF, r.confidence, reasons, r.nickname);
            }
            parsed.add(r);
        }
        return choose(input, cleaned, parsed, reasons);
    }

    // ------------------------------------------------------------------ segment choice

    private CharacterNameParseResult choose(String input, String cleaned, List<SegmentResult> parsed,
                                            List<ParseReason> reasons) {
        // The earliest segment with a name wins unless a later one is clearly more certain
        // ("Batman / Bruce Wayne" -> Bruce; "Queen Elizabeth / Barbara Cartland" -> Elizabeth).
        double top = parsed.stream().filter(r -> r.firstName != null).mapToDouble(r -> r.confidence).max().orElse(-1);
        SegmentResult best = null;
        for (SegmentResult r : parsed) {
            if (r.firstName != null && r.confidence >= top - 0.1) {
                best = r;
                break;
            }
        }
        if (best == null) {
            // No segment has a name: the answer is only as certain as the least certain "no".
            SegmentResult first = parsed.get(0);
            double confidence = parsed.stream().mapToDouble(r -> r.confidence).min().orElse(first.confidence);
            reasons.addAll(first.reasons);
            return result(input, cleaned, first.text, null, first.type, confidence, reasons, first.nickname);
        }
        double confidence = best.confidence;
        for (SegmentResult r : parsed) {
            if (r != best && r.firstName != null && !r.firstName.equalsIgnoreCase(best.firstName)) {
                reasons.add(ParseReason.SEGMENTS_DISAGREE);
                confidence -= 0.05;
                break;
            }
        }
        reasons.addAll(best.reasons);
        return result(input, cleaned, best.text, best.firstName, best.type, confidence, reasons, best.nickname);
    }

    private CharacterNameParseResult result(String input, String cleaned, String segment, String firstName,
                                            NameTypeV2 type, double confidence, List<ParseReason> reasons,
                                            String nickname) {
        double rounded = Math.round(Math.max(0.0, Math.min(1.0, confidence)) * 100) / 100.0;
        return new CharacterNameParseResult(input, cleaned, segment, firstName, type, rounded, reasons, nickname, VERSION);
    }

    // ------------------------------------------------------------------ normalisation

    static String normalise(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFC);
        t = SINGLE_QUOTES.matcher(t).replaceAll("'");
        t = DOUBLE_QUOTES.matcher(t).replaceAll("\"");
        t = DASHES.matcher(t).replaceAll("-");
        return WHITESPACE.matcher(t).replaceAll(" ").trim();
    }

    /** True when every cased letter is upper case, or every one is lower case ("PETER PARKER", "talk show host"). */
    private static boolean isUniformCase(String s) {
        int upper = 0;
        int lower = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                upper++;
            } else if (Character.isLowerCase(c)) {
                lower++;
            }
        }
        return upper + lower >= 2 && (upper == 0 || lower == 0);
    }

    /**
     * Title-cases uniform-case input the same way whether it arrived upper or lower case:
     * connectors stay lowercase ("FRED THE JANITOR" -> "Fred the Janitor") and vowel-less words
     * become acronyms ("TV ANNOUNCER" / "tv announcer" -> "TV Announcer").
     */
    private String recase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        String[] words = s.split(" ");
        for (int i = 0; i < words.length; i++) {
            String lower = words[i].toLowerCase(Locale.ROOT);
            String recased;
            if (i > 0 && CONNECTORS.contains(lower)) {
                recased = lower;
            } else if (VOWELLESS.matcher(lower).matches() && !isTitleWord(lower.replaceAll("[.,:;]+$", ""))) {
                recased = lower.toUpperCase(Locale.ROOT);
            } else {
                recased = titleCase(lower);
            }
            out.append(i > 0 ? " " : "").append(recased);
        }
        return out.toString();
    }

    /** "o'brien" -> "O'Brien", "JEAN-LUC" -> "Jean-Luc", "erika's" -> "Erika's". */
    static String titleCase(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean startOfWord = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean afterLeadingApostrophe = i >= 2 && lower.charAt(i - 1) == '\''
                && Character.isLetter(lower.charAt(i - 2)) && (i < 3 || !Character.isLetter(lower.charAt(i - 3)))
                && c != 's';
            out.append(startOfWord || afterLeadingApostrophe ? Character.toUpperCase(c) : c);
            startOfWord = !Character.isLetterOrDigit(c) && c != '\'';
        }
        return out.toString();
    }

    private static String removeAnnotations(String text, List<ParseReason> reasons) {
        String s = text;
        Matcher m = BRACKETED.matcher(s);
        while (m.find()) {
            reasons.add(AS_CREDIT.matcher(m.group(1)).matches() ? ParseReason.AS_CREDIT_REMOVED : ParseReason.ANNOTATION_REMOVED);
            s = s.substring(0, m.start()) + " " + s.substring(m.end());
            m = BRACKETED.matcher(s);
        }
        Matcher unclosed = UNCLOSED_BRACKET.matcher(s);
        if (unclosed.find()) {
            reasons.add(ParseReason.ANNOTATION_REMOVED);
            s = s.substring(0, unclosed.start());
        }
        s = STRAY_BRACKETS.matcher(s).replaceAll(" ");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    // ------------------------------------------------------------------ per-segment pipeline

    private SegmentResult parseSegment(String raw) {
        List<ParseReason> reasons = new ArrayList<>();
        String seg = raw;

        if (SELF.matcher(seg).find()) {
            reasons.add(ParseReason.SELF_CREDIT);
            return new SegmentResult(seg, null, NameTypeV2.SELF, 0.98, reasons, null);
        }

        Matcher dash = DASH_DESCRIPTION.matcher(seg);
        if (dash.find() && dash.start() > 0) {
            seg = seg.substring(0, dash.start());
            reasons.add(ParseReason.DASH_DESCRIPTION_REMOVED);
        }

        int comma = seg.indexOf(',');
        if (comma > 0) {
            String rest = seg.substring(comma + 1).trim();
            seg = seg.substring(0, comma);
            if (!rest.isEmpty() && !SUFFIX_AFTER_COMMA.matcher(rest).matches()) {
                reasons.add(ParseReason.COMMA_DESCRIPTION_REMOVED);
            }
        }

        String nickname = null;
        for (Pattern quoted : List.of(DOUBLE_QUOTED, SINGLE_QUOTED)) {
            Matcher q = quoted.matcher(seg);
            StringBuilder kept = new StringBuilder();
            int last = 0;
            while (q.find()) {
                if (nickname == null && !q.group(1).isBlank()) {
                    nickname = q.group(1).trim();
                }
                kept.append(seg, last, q.start()).append(' ');
                last = q.end();
            }
            kept.append(seg.substring(last));
            seg = kept.toString();
        }
        seg = WHITESPACE.matcher(seg.replace("\"", " ")).replaceAll(" ").trim();
        if (nickname != null) {
            reasons.add(ParseReason.NICKNAME_EXTRACTED);
            if (seg.isEmpty()) {
                seg = nickname;
                reasons.add(ParseReason.NICKNAME_ONLY);
            }
        }

        seg = TRAILING_JUNK.matcher(LEADING_JUNK.matcher(seg).replaceAll("")).replaceAll("");
        if (seg.isEmpty()) {
            reasons.add(ParseReason.EMPTY_INPUT);
            return new SegmentResult(raw, null, NameTypeV2.EMPTY, 0.9, reasons, nickname);
        }
        if (SELF.matcher(seg).find()) {
            reasons.add(ParseReason.SELF_CREDIT);
            return new SegmentResult(seg, null, NameTypeV2.SELF, 0.98, reasons, nickname);
        }

        List<Token> toks = tokenise(seg);
        Classification c = classify(toks, reasons);
        if (nickname != null && toks.size() == 1 && !seg.equals(nickname) && (c.firstName == null || c.confidence < 0.6)) {
            // "\"Little Alice\" Eklund": the nickname carries the name, the rest is a bare surname.
            List<ParseReason> nickReasons = new ArrayList<>(reasons);
            Classification fromNick = classify(tokenise(nickname), nickReasons);
            if (fromNick.firstName != null && fromNick.confidence > c.confidence) {
                nickReasons.add(ParseReason.NAME_FROM_NICKNAME);
                return new SegmentResult(seg, fromNick.firstName, fromNick.type, fromNick.confidence - 0.1, nickReasons, nickname);
            }
        }
        return new SegmentResult(seg, c.firstName, c.type, c.confidence, reasons, nickname);
    }

    private Classification classify(List<Token> toks, List<ParseReason> reasons) {
        int n = toks.size();

        for (Token t : toks) {
            if (NUMBER_TOKEN.matcher(t.text).matches() || NUMBER_TOKEN.matcher(t.raw).matches()) {
                return none(reasons, ParseReason.NUMBERED, NameTypeV2.NUMBERED_ROLE, 0.95);
            }
        }
        for (Token t : toks) {
            if (POSSESSIVE.matcher(t.text).matches() && t.text.length() > 2) {
                return none(reasons, ParseReason.POSSESSIVE, NameTypeV2.RELATIONAL_ROLE, 0.9);
            }
        }
        Token first = toks.get(0);
        if (first.startsLowercase() && !(n > 1 && lexicon.isParticle(first.fold) && toks.get(1).startsUppercase())) {
            return none(reasons, ParseReason.LOWERCASE_DESCRIPTOR, NameTypeV2.ROLE_DESCRIPTION, 0.85);
        }
        if (n > 1 && lexicon.isArticle(first.fold) && count(first) < COMMON) {
            return none(reasons, ParseReason.ARTICLE, NameTypeV2.ROLE_DESCRIPTION, 0.9);
        }
        if (isAcronym(first)) {
            return none(reasons, ParseReason.ACRONYM, NameTypeV2.ROLE_DESCRIPTION, 0.85);
        }

        // Prefixes that precede a given name: "Young Nancy", "Princess Leia", "Sassy Sue".
        int i = 0;
        NameTypeV2 type = null;
        boolean adjectivePrefix = false;
        while (i < n - 1 && toks.get(i + 1).startsUppercase() && titleSpan(toks, i) == 0) {
            Token t = toks.get(i);
            Token next = toks.get(i + 1);
            boolean nameInItsOwnRight = count(t) >= COMMON && count(next) < COMMON;   // "Tia Carrere", "Rich Sommer"
            if (lexicon.isGivenTitle(t.fold)) {
                type = NameTypeV2.TITLED_NAME;
                reasons.add(ParseReason.GIVEN_TITLE_STRIPPED);
            } else if (lexicon.isAgeOrKinPrefix(t.fold) && !nameInItsOwnRight) {
                type = NameTypeV2.AGE_OR_KIN_VARIANT;
                reasons.add(ParseReason.AGE_KIN_PREFIX_STRIPPED);
            } else if (lexicon.isAdjective(t.fold) && !nameInItsOwnRight) {
                adjectivePrefix = true;
                reasons.add(ParseReason.ADJECTIVE_STRIPPED);
            } else {
                break;
            }
            i++;
        }

        // Surname-type titles, possibly behind a modifier: "Officer Daniels", "Hialeah Patrolman Junger".
        int titleAt = titleSpan(toks, i) > 0 ? i : (i == 0 ? modifiedTitlePosition(toks) : -1);
        if (titleAt >= 0) {
            return classifyAfterSurnameTitle(toks, titleAt, reasons);
        }

        List<Token> body = toks.subList(i, n);
        Token cand = body.get(0);
        int candCount = count(cand);

        // Name followed by a lowercase description: "Fred the Janitor", "Johnny the perv".
        if (body.size() > 1 && hasLowercaseDescription(body)) {
            if (isUsableKnownName(cand)) {
                reasons.add(ParseReason.DESCRIPTION_AFTER_NAME);
                double confidence = "the".equals(body.get(1).fold) ? 0.8 : 0.65;
                return found(cand, NameTypeV2.NAME_WITH_DESCRIPTION, confidence, reasons, ParseReason.LEXICON_KNOWN);
            }
            return none(reasons, ParseReason.LOWERCASE_DESCRIPTOR, NameTypeV2.ROLE_DESCRIPTION, 0.75);
        }

        // Head-noun rule: "Swamp Tour Guide", "Faculty Woman", "Kid Rock Body Double".
        if (body.size() > 1) {
            Token head = body.get(body.size() - 1);
            if (isRoleHead(head)) {
                boolean surnameLike = (lexicon.isOccupationalSurname(head.fold) || count(head) >= ROLE_AS_NAME)
                    && body.size() <= 3 && isUsableKnownName(cand);
                // "Dave Bird": a common given name that is not also a word, then a non-person role noun.
                boolean nameThenSurname = body.size() == 2 && candCount >= COMMON && isUsableKnownName(cand)
                    && !lexicon.isEnglishWord(cand.fold) && !lexicon.isPersonNoun(head.fold) && !lexicon.isDemonym(head.fold);
                surnameLike = surnameLike || nameThenSurname;
                if (!surnameLike) {
                    return none(reasons, ParseReason.ROLE_HEAD_NOUN, NameTypeV2.ROLE_DESCRIPTION, 0.85);
                }
            }
        }

        // Surname-first East Asian credits: "Huang Mingjin".
        if (body.size() == 2 && isSurnameFirst(body.get(0), body.get(1))) {
            reasons.add(ParseReason.SURNAME_FIRST);
            return found(body.get(1), NameTypeV2.PERSONAL_NAME, 0.7, reasons, ParseReason.LEXICON_UNKNOWN);
        }

        Validation v = validate(cand, body.subList(1, body.size()));
        if (!v.accepted && v.reason == ParseReason.ROLE_WORD && body.size() > 1) {
            Classification afterRole = nameAfterRoleWords(body, reasons);
            if (afterRole != null) {
                return afterRole;
            }
        }
        if (!v.accepted) {
            return none(reasons, v.reason, NameTypeV2.ROLE_DESCRIPTION, v.confidence);
        }
        if (adjectivePrefix && candCount < KNOWN) {
            return none(reasons, ParseReason.ADJECTIVE_DESCRIPTOR, NameTypeV2.ROLE_DESCRIPTION, 0.75);
        }
        double confidence = v.confidence;
        if (type == null) {
            type = body.size() == 1 ? NameTypeV2.SINGLE_NAME : NameTypeV2.PERSONAL_NAME;
        } else {
            confidence -= 0.05;
        }
        if (adjectivePrefix) {
            type = type == NameTypeV2.SINGLE_NAME ? NameTypeV2.PERSONAL_NAME : type;
            confidence = Math.min(confidence, 0.7);
        }
        return found(cand, type, confidence, reasons, v.reason);
    }

    /**
     * "Boyfriend Rick", "Security Operator Dave", "Reporter Suki Sanchez": role words used like a
     * title in front of a common given name. A lone trailing name may also be a surname
     * ("Ranger Jackson"), so that case gets a low confidence.
     */
    private Classification nameAfterRoleWords(List<Token> body, List<ParseReason> reasons) {
        int j = 0;
        while (j < body.size() && (lexicon.isRoleNoun(body.get(j).fold) || lexicon.isAdjective(body.get(j).fold))
            && count(body.get(j)) < ROLE_AS_NAME) {
            j++;
        }
        if (j == 0 || j >= body.size()) {
            return null;
        }
        Token name = body.get(j);
        List<Token> rest = body.subList(j + 1, body.size());
        if (count(name) < COMMON || !isUsableKnownName(name)
            || rest.stream().anyMatch(t -> !t.startsUppercase() || isRoleHead(t))) {
            return null;
        }
        reasons.add(ParseReason.ROLE_PREFIX_STRIPPED);
        return found(name, NameTypeV2.TITLED_NAME, rest.isEmpty() ? 0.6 : 0.75, reasons, ParseReason.LEXICON_COMMON);
    }

    /** Title (possibly a chain: "Group Capt. Maj. James Stagg") starts at {@code at}. */
    private Classification classifyAfterSurnameTitle(List<Token> toks, int at, List<ParseReason> reasons) {
        int n = toks.size();
        Token titleToken = toks.get(at);
        int k = at + titleSpan(toks, at);
        boolean singleWordTitle = k == at + 1;
        while (k < n && titleSpan(toks, k) > 0) {
            k += titleSpan(toks, k);
        }
        List<Token> rest = toks.subList(k, n);
        if (rest.isEmpty()) {
            return none(reasons, ParseReason.TITLE_ONLY, NameTypeV2.ROLE_DESCRIPTION, 0.85);
        }
        if (hasLowercaseWord(rest)) {
            return none(reasons, ParseReason.LOWERCASE_DESCRIPTOR, NameTypeV2.ROLE_DESCRIPTION, 0.8);
        }
        if (rest.size() == 1) {
            if (at == 0 && singleWordTitle && k == 1 && count(titleToken) >= TITLE_AS_NAME) {
                reasons.add(ParseReason.AMBIGUOUS_TITLE_OR_NAME);
                return found(titleToken, NameTypeV2.PERSONAL_NAME, 0.55, reasons, ParseReason.LEXICON_COMMON);
            }
            if (isRoleHead(rest.get(0))) {
                return none(reasons, ParseReason.ROLE_HEAD_NOUN, NameTypeV2.ROLE_DESCRIPTION, 0.85);
            }
            return none(reasons, ParseReason.SURNAME_TITLE, NameTypeV2.TITLE_SURNAME, 0.85);
        }
        if (isRoleHead(rest.get(rest.size() - 1))) {
            return none(reasons, ParseReason.ROLE_HEAD_NOUN, NameTypeV2.ROLE_DESCRIPTION, 0.85);
        }
        Validation v = validate(rest.get(0), rest.subList(1, rest.size()));
        if (!v.accepted) {
            return none(reasons, ParseReason.SURNAME_TITLE, NameTypeV2.TITLE_SURNAME, 0.7);
        }
        reasons.add(ParseReason.TITLE_BEFORE_GIVEN_NAME);
        return found(rest.get(0), NameTypeV2.TITLED_NAME, Math.min(0.9, v.confidence - 0.05), reasons, v.reason);
    }

    // ------------------------------------------------------------------ candidate validation

    private Validation validate(Token cand, List<Token> rest) {
        String f = cand.fold;
        if (!LATIN.matcher(cand.text).find()) {
            return Validation.reject(ParseReason.NON_LATIN_SCRIPT, 0.4);   // "签证官": cannot be checked against the lexicon
        }
        if (!NAME_SHAPE.matcher(cand.text).matches()) {
            return INITIAL.matcher(cand.text).matches()
                ? Validation.reject(ParseReason.INITIAL_ONLY, 0.6)
                : Validation.reject(ParseReason.MALFORMED_TOKEN, 0.5);
        }
        if (cand.startsLowercase() && lexicon.isParticle(f)) {
            return Validation.reject(ParseReason.NAME_PARTICLE, 0.8);   // "Marquis de Sade", "Padre di Marcello"
        }
        boolean single = rest.isEmpty();
        if ((lexicon.isStopword(f) || lexicon.isDemonym(f)) && !single && count(cand) >= KNOWN
                && restIsCapitalisedNonWord(rest)) {
            // "Guy Gardner", "Christian Weller": a given name that is also a word, before a surname
            return Validation.accept(ParseReason.LEXICON_KNOWN, 0.7);
        }
        if (lexicon.isStopword(f)) {
            return Validation.reject(ParseReason.STOPWORD, 0.85);
        }
        if (VOWELLESS.matcher(f).matches()) {
            return Validation.reject(ParseReason.ACRONYM, 0.8);   // "PB", "TV": no vowel, never a name
        }
        if (f.indexOf('-') > 0 && isRoleHead(cand)) {
            return Validation.reject(ParseReason.COMPOUND_ROLE_WORD, 0.75);   // "Spider-Man", "Metro-Goer"
        }
        if (lexicon.isDemonym(f)) {
            return Validation.reject(ParseReason.DEMONYM, 0.85);
        }
        int count = count(cand);
        if (lexicon.isRoleNoun(f) && count < ROLE_AS_NAME) {
            return Validation.reject(ParseReason.ROLE_WORD, 0.8);
        }
        if ((lexicon.isSurnameTitle(f) || lexicon.isGivenTitle(f) || lexicon.isAgeOrKinPrefix(f)) && count < ROLE_AS_NAME) {
            return Validation.reject(ParseReason.TITLE_ONLY, 0.8);
        }
        if (lexicon.isAdjective(f) && count < COMMON) {
            return Validation.reject(ParseReason.ADJECTIVE_DESCRIPTOR, 0.75);
        }
        if (lexicon.isEnglishWord(f) && count < WORD_AS_NAME) {
            // "Flip Zimmerman" is a name; "Wheel Tapper" and "Penguin" are not.
            if (!single && restLooksLikeSurname(rest)) {
                return Validation.accept(ParseReason.LEXICON_UNKNOWN, 0.55);
            }
            return Validation.reject(ParseReason.ENGLISH_WORD, single ? 0.6 : 0.65);
        }
        if (count >= COMMON) {
            return Validation.accept(ParseReason.LEXICON_COMMON, single ? 0.9 : 0.95);
        }
        if (count >= KNOWN) {
            return Validation.accept(ParseReason.LEXICON_KNOWN, single ? 0.8 : 0.85);
        }
        if (count >= RARE) {
            return Validation.accept(ParseReason.LEXICON_RARE, single ? 0.65 : 0.7);
        }
        if (single && COMPOUND_PERSON.matcher(f).matches()) {
            return Validation.reject(ParseReason.COMPOUND_ROLE_WORD, 0.65);   // "Robonaut" no, "Batman" yes
        }
        if (single && (LONG_AGENTIVE.matcher(f).matches() || ROLE_SUFFIX.matcher(f).matches())) {
            return Validation.reject(ParseReason.ROLE_HEAD_NOUN, 0.6);   // "Forgetter", "Neuroscientist", "Dogwalker"
        }
        if (f.indexOf('-') > 0 && !KOREAN_GIVEN.matcher(cand.text).matches() && hyphenPartIsWord(cand)) {
            return Validation.reject(ParseReason.COMPOUND_ROLE_WORD, 0.6);   // "On-Set", "Mid-shipman"
        }
        if (!single && !restLooksLikeSurname(rest) && AGENTIVE_SUFFIX.matcher(rest.get(rest.size() - 1).fold).matches()) {
            return Validation.reject(ParseReason.ROLE_HEAD_NOUN, 0.55);
        }
        return Validation.accept(ParseReason.LEXICON_UNKNOWN, single ? 0.5 : 0.55);
    }

    /** An unknown hyphenated word with a lowercase part or a part that is a common word: "On-Set", "Mid-shipman". */
    private boolean hyphenPartIsWord(Token t) {
        String[] raw = t.text.split("-");
        for (String part : raw) {
            if (part.isEmpty()) {
                continue;
            }
            String folded = CharacterNameLexicon.fold(part);
            if (Character.isLowerCase(part.charAt(0)) || lexicon.isStopword(folded)
                    || lexicon.isEnglishWord(folded) && lexicon.givenNameCount(folded) < WORD_AS_NAME) {
                return true;
            }
        }
        return false;
    }

    /** A known given name that is not also a stop / role / descriptive word. */
    private boolean isUsableKnownName(Token t) {
        int count = count(t);
        return count >= KNOWN
            && !lexicon.isStopword(t.fold) && !lexicon.isDemonym(t.fold)
            && !(lexicon.isRoleNoun(t.fold) && count < ROLE_AS_NAME)
            && !(lexicon.isSurnameTitle(t.fold) && count < ROLE_AS_NAME)
            && !(lexicon.isAdjective(t.fold) && count < COMMON)
            && !(lexicon.isEnglishWord(t.fold) && count < WORD_AS_NAME);
    }

    /** Capitalised words that are neither common English words nor role nouns ("Gardner", "Weller"). */
    private boolean restIsCapitalisedNonWord(List<Token> rest) {
        for (Token t : rest) {
            if (!t.startsUppercase() || lexicon.isEnglishWord(t.fold) || lexicon.isStopword(t.fold) || isRoleHead(t)
                    || lexicon.isRoleNoun(t.fold) || lexicon.isDemonym(t.fold)) {
                return false;
            }
        }
        return true;
    }

    private boolean restLooksLikeSurname(List<Token> rest) {
        for (Token t : rest) {
            if (!t.startsUppercase() || lexicon.isEnglishWord(t.fold) || isRoleHead(t)) {
                return false;
            }
        }
        return !AGENTIVE_SUFFIX.matcher(rest.get(rest.size() - 1).fold).matches()
            || count(rest.get(rest.size() - 1)) >= KNOWN;
    }

    private boolean isRoleHead(Token t) {
        if (lexicon.isRoleHead(t.fold) || t.fold.length() > 2 && lexicon.isSurnameTitle(t.fold) && count(t) < COMMON) {
            return true;
        }
        int hyphen = t.fold.lastIndexOf('-');
        return hyphen > 0 && hyphen < t.fold.length() - 1 && lexicon.isRoleHead(t.fold.substring(hyphen + 1));
    }

    private boolean isTitleWord(String folded) {
        return lexicon.isSurnameTitle(folded) || lexicon.isGivenTitle(folded);
    }

    private boolean isAcronym(Token t) {
        return ACRONYM.matcher(t.text).matches() && !isTitleWord(t.fold) && count(t) < 200 && !t.text.matches("^\\p{Lu}\\.\\p{Lu}\\.$");
    }

    private boolean hasLowercaseDescription(List<Token> body) {
        return hasLowercaseWord(body.subList(1, body.size()));
    }

    /** A lowercase word that is not a name particle, or a particle followed by one ("da giovane"). */
    private boolean hasLowercaseWord(List<Token> toks) {
        for (Token t : toks) {
            if (t.startsLowercase() && !lexicon.isParticle(t.fold)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSurnameFirst(Token surname, Token given) {
        if (!lexicon.isCjkSurname(surname.fold) || lexicon.isEnglishWord(given.fold)) {
            return false;
        }
        if (KOREAN_GIVEN.matcher(given.text).matches()) {
            return true;
        }
        return count(surname) < COMMON && count(given) < KNOWN
            && PINYIN.matcher(given.fold.replace("-", "")).matches();
    }

    /** Length in tokens of the longest surname-type title starting at {@code i}, or 0. */
    private int titleSpan(List<Token> toks, int i) {
        for (int len = Math.min(lexicon.longestTitleWords(), toks.size() - i); len >= 1; len--) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < i + len; j++) {
                sb.append(j > i ? " " : "").append(toks.get(j).fold);
            }
            if (lexicon.isSurnameTitle(sb.toString()) && !lexicon.isGivenTitle(sb.toString())) {
                return len;
            }
        }
        return 0;
    }

    /** "Hialeah Patrolman Junger": a title at position 1 or 2 behind capitalised non-name modifiers. */
    private int modifiedTitlePosition(List<Token> toks) {
        for (int j = 1; j <= Math.min(2, toks.size() - 1); j++) {
            Token before = toks.get(j - 1);
            if (!before.startsUppercase() || isUsableKnownName(before) && count(before) >= COMMON
                || lexicon.isAgeOrKinPrefix(before.fold) || lexicon.isGivenTitle(before.fold)) {
                return -1;
            }
            if (titleSpan(toks, j) > 0 && j + titleSpan(toks, j) < toks.size()) {
                return j;
            }
        }
        return -1;
    }

    private int count(Token t) {
        return lexicon.givenNameCount(t.fold);
    }

    // ------------------------------------------------------------------ results

    private static Classification none(List<ParseReason> reasons, ParseReason reason, NameTypeV2 type, double confidence) {
        reasons.add(reason);
        return new Classification(null, type, confidence);
    }

    private static Classification found(Token token, NameTypeV2 type, double confidence, List<ParseReason> reasons,
                                        ParseReason lexiconReason) {
        reasons.add(lexiconReason);
        String name = token.text.endsWith(".") ? token.text.substring(0, token.text.length() - 1) : token.text;
        String cased = fixCasing(name);
        if (!cased.equals(name)) {
            reasons.add(ParseReason.CASING_NORMALISED);
        }
        return new Classification(cased, type, confidence);
    }

    /** "JOHN" -> "John", "JEAN-LUC" -> "Jean-Luc"; mixed-case names are kept as written ("McKenzie"). */
    static String fixCasing(String name) {
        boolean hasLower = name.chars().anyMatch(Character::isLowerCase);
        boolean startsUpper = Character.isUpperCase(name.charAt(0));
        if (name.length() > 1 && !hasLower) {
            return titleCase(name);
        }
        if (!startsUpper && Character.isLowerCase(name.charAt(0))) {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    private static List<Token> tokenise(String segment) {
        List<Token> toks = new ArrayList<>();
        for (String raw : segment.split(" ")) {
            String text = raw.replaceAll("[,:;!?]+$", "");
            if (!text.isEmpty() && !text.equals(".") && !text.equals("-") && !text.equals("&")) {
                toks.add(new Token(raw, text, CharacterNameLexicon.fold(text.replaceAll("\\.$", ""))));
            }
        }
        if (toks.isEmpty()) {
            toks.add(new Token(segment, segment, CharacterNameLexicon.fold(segment)));
        }
        return toks;
    }

    private record Token(String raw, String text, String fold) {
        boolean startsLowercase() {
            int cp = text.codePointAt(0);
            return Character.isLowerCase(cp);
        }

        /** Upper case, or a letter from a script without case (CJK, Arabic, ...). */
        boolean startsUppercase() {
            int cp = text.codePointAt(0);
            return Character.isUpperCase(cp) || Character.isTitleCase(cp)
                || Character.isLetter(cp) && !Character.isLowerCase(cp);
        }
    }

    private record Classification(String firstName, NameTypeV2 type, double confidence) {
    }

    private record Validation(boolean accepted, ParseReason reason, double confidence) {
        static Validation accept(ParseReason reason, double confidence) {
            return new Validation(true, reason, confidence);
        }

        static Validation reject(ParseReason reason, double confidence) {
            return new Validation(false, reason, confidence);
        }
    }

    private record SegmentResult(String text, String firstName, NameTypeV2 type, double confidence,
                                 List<ParseReason> reasons, String nickname) {
    }

    @Override
    public String toString() {
        return "CharacterNameParserV2[" + VERSION + "]";
    }

}
