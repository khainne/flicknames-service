package com.flicknames.service.service;

import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.util.charactername.CharacterNameParseResult;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterNameReparseMappingTest {

    private static final CharacterNameParserV2 PARSER = new CharacterNameParserV2();

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "Peter Parker, Peter, Parker, STANDARD",
        "Dr. Roshan Manik, Roshan, Manik, STANDARD",
        "Young Nancy, Nancy, null, STANDARD",
        "Officer Daniels, null, Daniels, TITLE_SURNAME",
        "Security Guard, null, null, ROLE_DESCRIPTION",
        "Cop #2, null, null, NUMBERED_ROLE",
        "Boudica, Boudica, null, SINGLE_NAME",
        "Francesca 'Frankie' Quinn, Francesca, Quinn, STANDARD",
    })
    void mapsV2ResultsToStoredParts(String input, String first, String last, NameType type) {
        CharacterNameParseResult result = PARSER.parse(input);
        assertThat(result.firstName()).isEqualTo(first);
        assertThat(CharacterNameReparseService.lastName(result)).isEqualTo(last);
        assertThat(CharacterNameReparseService.storedType(result)).isEqualTo(type);
    }
}
