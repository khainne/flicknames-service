package com.flicknames.service.service;

import com.flicknames.service.util.charactername.CharacterNameLexicon;
import com.flicknames.service.util.charactername.CharacterNameParseResult;
import com.flicknames.service.util.charactername.ParseReason;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser results certain enough that a model pass would only add cost and risk. Each rule was right on
 * every one of ~1,970 labelled credits (golden set plus two blind production samples) when it was
 * added; CertainParsesTest keeps it that way. Anything a rule does not cover goes to the model.
 */
final class CertainParses {

    private static final Pattern NAME_WORD = Pattern.compile("^\\p{Lu}\\p{Ll}+(?:[-']\\p{Lu}?\\p{Ll}+)*$");
    /** A given name that is also an English word counts only when this common as a baby name ("Will", "Mark"). */
    private static final int WORD_AS_FIRST_NAME = 50_000;

    private CertainParses() {}

    /** The name of the rule that makes this result certain, or null when the model should see it. */
    static String rule(CharacterNameParseResult result) {
        List<ParseReason> reasons = result.reasons();
        if (reasons.contains(ParseReason.SELF_CREDIT)) {
            return "self credit";
        }
        CharacterNameLexicon lexicon = CharacterNameLexicon.loadDefault();
        String[] tokens = result.input() == null ? new String[0] : result.input().trim().split("\\s+");
        ParseReason last = reasons.isEmpty() ? null : reasons.get(reasons.size() - 1);
        if (last == ParseReason.NUMBERED && result.firstName() == null && !hasNameLikeWord(tokens, lexicon)) {
            return "numbered extra";   // "Cop #2", "Soldier 3"
        }
        if (reasons.equals(List.of(ParseReason.LEXICON_COMMON)) && result.confidence() >= 0.95
                && tokens.length >= 2 && tokens.length <= 3 && result.firstName() != null
                && tokens[0].equalsIgnoreCase(result.firstName()) && allNameWords(tokens)) {
            String first = CharacterNameLexicon.fold(tokens[0]);
            boolean firstOk = !lexicon.isEnglishWord(first) || lexicon.givenNameCount(first) >= WORD_AS_FIRST_NAME;
            boolean restOk = true;
            for (int i = 1; i < tokens.length; i++) {
                restOk &= !lexicon.isEnglishWord(CharacterNameLexicon.fold(tokens[i]));
            }
            if (firstOk && restOk) {
                return "first last";   // "Teri Montana", "Katrina Hampton"
            }
        }
        return null;
    }

    private static boolean allNameWords(String[] tokens) {
        for (String t : tokens) {
            if (!NAME_WORD.matcher(t).matches()) {
                return false;
            }
        }
        return true;
    }

    /** A capitalised word that is not an English word, i.e. one that might be a name ("Michel" in "Collègue Michel 2"). */
    private static boolean hasNameLikeWord(String[] tokens, CharacterNameLexicon lexicon) {
        for (String t : tokens) {
            if (NAME_WORD.matcher(t).matches() && !lexicon.isEnglishWord(CharacterNameLexicon.fold(t))) {
                return true;
            }
        }
        return false;
    }
}
