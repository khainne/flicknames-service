package com.flicknames.service.util.charactername;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Targeted examples for each pipeline stage, taken from the parser audit's error classes. */
class CharacterNameParserV2Test {

    private final CharacterNameParserV2 parser = new CharacterNameParserV2();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
        Peter Parker / Spider-Man              | Peter
        Batman / Bruce Wayne                   | Bruce
        Emma / Additional Voices (voice)       | Emma
        Gwen/Nicole                            | Gwen
        Young Anna                             | Anna
        Young Nancy                            | Nancy
        Little Kris                            | Kris
        Auntie Rani                            | Rani
        Princess Leia                          | Leia
        Sir William Beechey                    | William
        Sister Mary Eunice                     | Mary
        Dr. Roshan Manik                       | Roshan
        Group Capt. Maj. James Stagg           | James
        Warden Bernadine Williams              | Bernadine
        Dean Winchester                        | Dean
        Earl Hindman                           | Earl
        Duke Ellington                         | Duke
        Carter                                 | Carter
        Hunter                                 | Hunter
        Kevin Butler                           | Kevin
        Dave Bird                              | Dave
        Fred the Janitor                       | Fred
        Billy the Kid                          | Billy
        Francesca 'Frankie' Quinn              | Francesca
        Ricky "Railroad" Reynolds              | Ricky
        Sam (as John Doe)                      | Sam
        Lucy [archive footage]                 | Lucy
        Princess Miranda - Special Guest Star  | Miranda
        Virgile, le vigile                     | Virgile
        Huang Mingjin                          | Mingjin
        Yang Soo-min                           | Soo-min
        Kim Basinger                           | Kim
        Han Solo                               | Han
        JOHN SMITH                             | John
        célestine                              | Célestine
        Jean-Bob                               | Jean-Bob
        Boyfriend Rick                         | Rick
        Self - Host                            | null
        Himself                                | null
        Herself (archive footage)              | null
        Cop #2                                 | null
        Woman at the Bar 4                     | null
        Fedaykin                               | null
        Harkonnen Soldier                      | null
        French                                 | null
        Stock Broker                           | null
        Swamp Tour Guide                       | null
        Man                                    | null
        Woman                                  | null
        Girl                                   | null
        Boy                                    | null
        Erika's Son                            | null
        TV Announcer                           | null
        VIP Spectator                          | null
        L.A.P.D. Officer                       | null
        Officer Daniels                        | null
        Nurse Ratched                          | null
        The Bride                              | null
        Il Cacciatore                          | null
        enfermera                              | null
        Segretaria di edizione                 | null
        Young Businessman                      | null
        Kid Rock Body Double                   | null
        Calendar Man / Penguin (voice)         | null
        签证官                                   | null
        """)
    void examples(String input, String expected) {
        assertThat(parser.parse(input.strip()).firstName()).isEqualTo(expected);
    }

    /** Errors found in the first production dry run (2.0.0); see CharacterNameReparseService. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
        Stormtrooper                           | null
        Townsperson                            | null
        Laketowner                             | null
        Dogwalker                              | null
        Neuroscientist                         | null
        Neurofisiologo                         | null
        Debutante                              | null
        EMT                                    | null
        On-Set Dresser                         | null
        Marquis de Sade                        | null
        Guy Gardner                            | Guy
        Christian Weller                       | Christian
        Zafer                                  | Zafer
        PACO                                   | Paco
        Je-jo                                  | Je-jo
        Porteiro Roberto                       | Roberto
        Oncle Fred                             | Fred
        Watanabe Seiji                         | Seiji
        Romain                                 | Romain
        Halloran                               | null
        Tsumugu Kawamura                       | Tsumugu
        Female Berzerker                       | null
        Female Celt                            | null
        Male Med-Tech                          | null
        """)
    void productionDryRunCases(String input, String expected) {
        assertThat(parser.parse(input.strip()).firstName()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "(voice)", "(uncredited) [archive]"})
    void emptyInputs(String input) {
        CharacterNameParseResult r = parser.parse(input);
        assertThat(r.firstName()).isNull();
        assertThat(r.nameType()).isEqualTo(NameTypeV2.EMPTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"'", "\"\"", "-", "#", "(", ")", "/", " / / ", "a/b/", "'s", "O'", "\u2014", "\ud83c\udfac",
        "(unclosed", "x)", "[]", "Self", "SELF - HOST", "A", "Mr.", "#1", "de la", "The", "' quoted '", "Anna -", "- Anna"})
    void oddInputsNeverThrow(String input) {
        CharacterNameParseResult r = parser.parse(input);
        assertThat(r.confidence()).isBetween(0.0, 1.0);
        assertThat(r.reasons()).isNotEmpty();
    }

    @Test
    void resultCarriesTypeReasonsNicknameAndVersion() {
        CharacterNameParseResult r = parser.parse("Francesca 'Frankie' Quinn (uncredited)");
        assertThat(r.firstName()).isEqualTo("Francesca");
        assertThat(r.nickname()).isEqualTo("Frankie");
        assertThat(r.nameType()).isEqualTo(NameTypeV2.PERSONAL_NAME);
        assertThat(r.cleanedName()).isEqualTo("Francesca 'Frankie' Quinn");
        assertThat(r.reasons()).contains(ParseReason.ANNOTATION_REMOVED, ParseReason.NICKNAME_EXTRACTED, ParseReason.LEXICON_COMMON);
        assertThat(r.confidence()).isGreaterThanOrEqualTo(0.9);
        assertThat(r.parserVersion()).isEqualTo(CharacterNameParserV2.VERSION);
    }

    @Test
    void typesForCommonShapes() {
        assertThat(parser.parse("Self - Host").nameType()).isEqualTo(NameTypeV2.SELF);
        assertThat(parser.parse("Cop #2").nameType()).isEqualTo(NameTypeV2.NUMBERED_ROLE);
        assertThat(parser.parse("Erika's Son").nameType()).isEqualTo(NameTypeV2.RELATIONAL_ROLE);
        assertThat(parser.parse("Officer Daniels").nameType()).isEqualTo(NameTypeV2.TITLE_SURNAME);
        assertThat(parser.parse("Princess Leia").nameType()).isEqualTo(NameTypeV2.TITLED_NAME);
        assertThat(parser.parse("Young Anna").nameType()).isEqualTo(NameTypeV2.AGE_OR_KIN_VARIANT);
        assertThat(parser.parse("Swamp Tour Guide").nameType()).isEqualTo(NameTypeV2.ROLE_DESCRIPTION);
        assertThat(parser.parse("Boudica").nameType()).isEqualTo(NameTypeV2.SINGLE_NAME);
        assertThat(parser.parse("Peter Parker").nameType()).isEqualTo(NameTypeV2.PERSONAL_NAME);
    }

    @Test
    void confidenceSeparatesLexiconNamesFromGuesses() {
        assertThat(parser.parse("Peter Parker").confidence()).isGreaterThanOrEqualTo(0.9);
        assertThat(parser.parse("Aberknacky").confidence()).isLessThan(CharacterNameParserDryRun.LOW_CONFIDENCE);
        assertThat(parser.parse("Self - Host").confidence()).isGreaterThanOrEqualTo(0.9);
    }
}
