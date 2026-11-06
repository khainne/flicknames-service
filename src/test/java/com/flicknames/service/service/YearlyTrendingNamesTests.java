package com.flicknames.service.service;

import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The yearly all-names list looks up details only for the names it returns; the result must not change. */
@SpringBootTest
class YearlyTrendingNamesTests {

    @Autowired
    private NameService nameService;

    @Autowired
    private CharacterNameService characterNameService;

    @Autowired
    private UnifiedNameService unifiedNameService;

    @Test
    void matchesMergingFullyLookedUpLists() {
        for (int limit : new int[] {1, 3, 20}) {
            List<TrendingNameDTO> expected = unifiedNameService.mergeAndAggregate(
                    nameService.getTrendingNamesYearly(2023, limit * 2),
                    characterNameService.getTrendingNamesYearly(2023, limit * 2),
                    limit);
            List<TrendingNameDTO> actual = unifiedNameService.getAllTrendingNamesYearly(2023, limit);

            assertThat(expected).isNotEmpty();
            assertThat(actual).extracting(this::summary).containsExactlyElementsOf(expected.stream().map(this::summary).toList());
        }
    }

    @Test
    void filtersByReleaseYear() {
        assertThat(unifiedNameService.getAllTrendingNamesYearly(2023, 50)).isNotEmpty();
        assertThat(unifiedNameService.getAllTrendingNamesYearly(1800, 50)).isEmpty();
    }

    private String summary(TrendingNameDTO n) {
        return String.join("|",
                n.getName(),
                n.getTotalRevenue().stripTrailingZeros().toPlainString(),
                String.valueOf(n.getMovieCount()),
                String.valueOf(n.getPeopleCount()),
                String.valueOf(n.getPrimaryGender()),
                n.getTopPeople() == null ? "-" : n.getTopPeople().stream().map(PersonDTO::getId).toList().toString(),
                n.getRecentMovies() == null ? "-" : n.getRecentMovies().stream().map(MovieDTO::getId).toList().toString());
    }
}
