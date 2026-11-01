package com.flicknames.service.service;

import com.flicknames.service.service.MovieNamesService.Ranking;
import com.flicknames.service.service.MovieNamesService.Tier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class MovieNamesRankingTest {

    // 1,500 names given to 1,000,000, 999,900, ... babies
    private final Ranking ranking = new Ranking(LongStream.range(0, 1500).map(i -> 1_000_000 - i * 100).toArray(), Map.of());

    @Test
    void tiersFollowAllTimeRankThenBirths() {
        assertThat(ranking.tier(1_000_000 - 49 * 100)).isEqualTo(Tier.EVERYDAY);      // rank 50
        assertThat(ranking.tier(1_000_000 - 499 * 100)).isEqualTo(Tier.COMMON);       // rank 500
        assertThat(ranking.tier(1_000_000 - 1199 * 100)).isEqualTo(Tier.UNCOMMON);    // rank 1,200, 880,100 births
        assertThat(ranking.tier(500)).isEqualTo(Tier.RARE);
        assertThat(ranking.tier(0)).isEqualTo(Tier.NEVER_REGISTERED);
    }

    @Test
    void rankCountsNamesGivenMoreOftenAndTiesShare() {
        assertThat(ranking.rank(1_000_000)).isEqualTo(1);
        assertThat(ranking.rank(999_950)).isEqualTo(2);
        assertThat(ranking.rank(999_900)).isEqualTo(2);
        assertThat(ranking.rank(0)).isNull();
    }

    @Test
    void initialsAndBlanksAreNotNames() {
        assertThat(MovieNamesService.key("J.")).isNull();
        assertThat(MovieNamesService.key("A")).isNull();
        assertThat(MovieNamesService.key(" ")).isNull();
        assertThat(MovieNamesService.key("José")).isEqualTo("jose");
    }

    @Test
    void percentileIsTheShareStrictlyBelow() {
        assertThat(MovieNamesService.percentile(new double[] {0.1, 0.5, 0.5, 0.9}, 0.5)).isEqualTo(0.25);
    }

    @Test
    void givenNameShapeKeepsRealNamesAndDropsCompoundsStageNamesAndFragments() {
        assertThat(MovieNamesService.looksLikeGivenName("Ncuti")).isTrue();
        assertThat(MovieNamesService.looksLikeGivenName("Millie-Rose")).isTrue();
        assertThat(MovieNamesService.looksLikeGivenName("LaToya")).isTrue();
        assertThat(MovieNamesService.looksLikeGivenName("McKenna")).isTrue();
        assertThat(MovieNamesService.looksLikeGivenName("D'Andre")).isTrue();
        assertThat(MovieNamesService.looksLikeGivenName("Leslie Ann")).isFalse();
        assertThat(MovieNamesService.looksLikeGivenName("BloodPop")).isFalse();
        assertThat(MovieNamesService.looksLikeGivenName("Ed")).isFalse();
        assertThat(MovieNamesService.looksLikeGivenName("R2-D2")).isFalse();
    }

    @Test
    void unregisteredSpellingsThatArentNamesAreUnclassifiedAndNotUnusual() {
        assertThat(MovieNamesService.tierOf(ranking, 0, "Onyemachi")).isEqualTo(Tier.NEVER_REGISTERED);
        assertThat(MovieNamesService.tierOf(ranking, 0, "BloodPop")).isEqualTo(Tier.UNCLASSIFIED);
        assertThat(MovieNamesService.tierOf(ranking, 500, "BloodPop")).isEqualTo(Tier.RARE);
        assertThat(MovieNamesService.isUnusual(Tier.NEVER_REGISTERED)).isTrue();
        assertThat(MovieNamesService.isUnusual(Tier.UNCLASSIFIED)).isFalse();
        assertThat(MovieNamesService.isUnusual(Tier.COMMON)).isFalse();
    }

    @Test
    void sizeBandsGroupFilmsBySize() {
        assertThat(MovieNamesService.sizeBand(30)).containsExactly(20, 50);
        assertThat(MovieNamesService.sizeBand(363)).containsExactly(200, 400);
        assertThat(MovieNamesService.sizeBand(476)).containsExactly(400, -1);
    }
}
