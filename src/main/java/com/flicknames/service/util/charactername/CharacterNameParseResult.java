package com.flicknames.service.util.charactername;

import java.util.List;

/**
 * Output of {@link CharacterNameParserV2#parse(String)}.
 *
 * @param input         the raw character string as received
 * @param cleanedName   the string after normalisation and annotation removal (all roles kept)
 * @param segment       the role segment the first name was taken from (or the first segment)
 * @param firstName     the given name a parent could use, or {@code null}
 * @param nameType      v2 classification of the chosen segment
 * @param confidence    0-1 estimate that {@code firstName} (including a {@code null}) is right
 * @param reasons       reason codes, in pipeline order
 * @param nickname      a quoted nickname, if the credit had one ("Frankie" in Francesca 'Frankie' Quinn)
 * @param parserVersion version of the parser and lexicons that produced this result
 */
public record CharacterNameParseResult(
        String input,
        String cleanedName,
        String segment,
        String firstName,
        NameTypeV2 nameType,
        double confidence,
        List<ParseReason> reasons,
        String nickname,
        String parserVersion) {

    public CharacterNameParseResult {
        reasons = List.copyOf(reasons);
    }

    public boolean hasFirstName() {
        return firstName != null;
    }
}
