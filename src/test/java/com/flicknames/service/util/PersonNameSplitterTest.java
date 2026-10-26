package com.flicknames.service.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameSplitterTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
        Tom Hanks                   | Tom      | Hanks
        Samuel L. Jackson           | Samuel   | Jackson
        Adam A. Smith               | Adam     | Smith
        Abbas Ali Khan              | Abbas    | Khan
        Mary Kate Olsen             | Mary     | Olsen
        Robert Downey Jr.           | Robert   | Downey Jr.
        Martin Luther King III      | Martin   | King III
        Ludwig van Beethoven        | Ludwig   | van Beethoven
        Guillermo del Toro          | Guillermo| del Toro
        Ana de la Reguera           | Ana      | de la Reguera
        Reuben De Jong              | Reuben   | De Jong
        Rob Van Norden              | Rob      | Van Norden
        Brooke St. Johns            | Brooke   | St. Johns
        Omar El Sayed               | Omar     | Sayed
        Omar el-Sayed               | Omar     | el-Sayed
        Joe Jr                      | Joe      | Jr
        '  Zendaya  '               | Zendaya  | ''
        '  Emma    Stone '          | Emma     | Stone
        ''                          | Unknown  | ''
        NULL                        | Unknown  | ''
        """)
    void splitsFirstAndLastName(String fullName, String first, String last) {
        assertThat(PersonNameSplitter.split(fullName)).containsExactly(first, last);
    }

    private static final BiPredicate<String, String> COMPOUNDS = (a, b) -> Set.of("juan carlos", "mary anne")
        .contains((a + " " + b).toLowerCase());

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        Juan Carlos Fresnadillo     | Juan Carlos | Fresnadillo
        Mary Anne Smith Jr.         | Mary Anne   | Smith Jr.
        Mary Anne de la Cruz        | Mary Anne   | de la Cruz
        Juan Carlos                 | Juan        | Carlos
        Mary Elizabeth Winstead     | Mary        | Winstead
        """)
    void keepsATwoWordGivenNameWhenAWordFollows(String fullName, String first, String last) {
        assertThat(PersonNameSplitter.split(fullName, COMPOUNDS)).containsExactly(first, last);
    }
}
