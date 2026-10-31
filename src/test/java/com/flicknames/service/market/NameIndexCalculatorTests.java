package com.flicknames.service.market;

import com.flicknames.service.entity.Credit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NameIndexCalculatorTests {

    private static NameIndexCalculator.CreditRow row(String name, long movie, double revenue, double weight) {
        return new NameIndexCalculator.CreditRow(name, movie, revenue, weight);
    }

    @Test
    void weightsFollowBillingAndJob() {
        assertThat(CreditWeights.weight(Credit.RoleType.CAST, "Actor", 1)).isEqualTo(3.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CAST, "Actor", 3)).isEqualTo(3.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CAST, "Actor", 4)).isEqualTo(1.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CAST, "Actor", null)).isEqualTo(1.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CREW, "Director", null)).isEqualTo(1.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CREW, "Original Music Composer", null)).isEqualTo(1.0);
        assertThat(CreditWeights.weight(Credit.RoleType.CREW, "Sound Mixer", null)).isEqualTo(0.1);
    }

    @Test
    void aNameCountsOncePerFilmAtItsBestWeight() {
        // Twelve crew Michaels on one film count as one 0.1 credit; one lead Ana counts x3.
        List<NameIndexCalculator.CreditRow> credits = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            credits.add(row("Michael", 1, 100, 0.1));
        }
        credits.add(row("Ana", 1, 100, 3.0));
        credits.add(row("Michael", 2, 50, 1.0));

        List<NameIndexCalculator.IndexRow> rows = NameIndexCalculator.compute(credits, 10);

        assertThat(rows).extracting(NameIndexCalculator.IndexRow::name).containsExactly("Ana", "Michael");
        assertThat(rows.get(0).weightedRevenue()).isEqualTo(300);
        assertThat(rows.get(1).weightedRevenue()).isCloseTo(60, within(1e-9)); // 100x0.1 + 50x1
        assertThat(rows.get(1).movieCount()).isEqualTo(2);
        assertThat(rows.stream().mapToDouble(NameIndexCalculator.IndexRow::indexValue).sum()).isCloseTo(100, within(1e-9));
        assertThat(rows.get(0).rank()).isEqualTo(1);
    }

    @Test
    void mergesCapitalisationAndSkipsInitialsAndZeroRevenue() {
        List<NameIndexCalculator.IndexRow> rows = NameIndexCalculator.compute(List.of(
            row("MICHAEL", 1, 100, 1), row("michael", 2, 100, 1), row("Michael", 3, 100, 1),
            row("J.", 1, 100, 3), row("J", 1, 100, 3), row("Zoë", 1, 100, 1), row("D'Arcy", 1, 100, 1),
            row("Nobody", 4, 0, 3)), 10);

        assertThat(rows).extracting(NameIndexCalculator.IndexRow::name).containsExactly("Michael", "D'Arcy", "Zoë");
        assertThat(rows.get(0).nameKey()).isEqualTo("michael");
        assertThat(rows.get(0).movieCount()).isEqualTo(3);
    }

    @Test
    void keepsOnlyTheTopNames() {
        List<NameIndexCalculator.IndexRow> rows = NameIndexCalculator.compute(List.of(
            row("Ana", 1, 300, 1), row("Ben", 1, 200, 1), row("Cy", 1, 100, 1)), 2);
        assertThat(rows).extracting(NameIndexCalculator.IndexRow::name).containsExactly("Ana", "Ben");
        // Shares are still of the whole market, not just the names kept.
        assertThat(rows.get(0).indexValue()).isCloseTo(50, within(1e-9));
    }

    @Test
    void emptyWindowGivesNoRows() {
        assertThat(NameIndexCalculator.compute(List.of(), 10)).isEmpty();
    }
}
