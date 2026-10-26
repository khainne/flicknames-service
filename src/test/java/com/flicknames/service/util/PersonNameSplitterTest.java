package com.flicknames.service.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        Joe Jr                      | Joe      | Jr
        '  Zendaya  '               | Zendaya  | ''
        '  Emma    Stone '          | Emma     | Stone
        ''                          | Unknown  | ''
        NULL                        | Unknown  | ''
        """)
    void splitsFirstAndLastName(String fullName, String first, String last) {
        assertThat(PersonNameSplitter.split(fullName)).containsExactly(first, last);
    }
}
