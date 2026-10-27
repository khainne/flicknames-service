package com.flicknames.service.service;

import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.service.ResearchLinkRules.Candidate;
import com.flicknames.service.service.ResearchLinkRules.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The direction rules on cases seen in the production research cards. */
class ResearchLinkRulesTest {

    private static final Map<String, long[]> SSA = Map.of(
        "John", new long[] {5_000_000, 20_000}, "Johanna", new long[] {300, 60_000},
        "Andrea", new long[] {20_000, 300_000}, "Andrew", new long[] {1_300_000, 3_000});

    private static final ResearchLinkRules.SexCounts COUNTS = new ResearchLinkRules.SexCounts() {
        public long male(String s) {
            return SSA.getOrDefault(s, new long[2])[0];
        }

        public long female(String s) {
            return SSA.getOrDefault(s, new long[2])[1];
        }
    };

    private static List<Candidate> derive(Relation... relations) {
        return ResearchLinkRules.derive(List.of(relations), COUNTS);
    }

    private static Relation rel(String card, String related, String type) {
        return new Relation(1, card, related, type);
    }

    @Test
    void aShortFormListedAsVariantIsTurnedRoundWhenTheFullFormsCardAgrees() {
        // Nick's card says Nicholas is a VARIANT; Nicholas's card says Nick is a DIMINUTIVE
        assertThat(derive(rel("Nick", "Nicholas", "VARIANT"), rel("Nicholas", "Nick", "DIMINUTIVE")))
            .extracting(Candidate::from, Candidate::to, Candidate::type, Candidate::status, Candidate::confidence)
            .containsOnly(org.assertj.core.groups.Tuple.tuple("Nick", "Nicholas", Type.DIMINUTIVE_OF, Status.ACCEPTED, 85));
    }

    @Test
    void cognateUsedForAShortFormIsTurnedRoundToo() {
        assertThat(derive(rel("Johnny", "John", "COGNATE"), rel("John", "Johnny", "DIMINUTIVE")))
            .allMatch(c -> c.from().equals("Johnny") && c.to().equals("John") && c.type() == Type.DIMINUTIVE_OF
                && c.status() == Status.ACCEPTED);
    }

    @Test
    void anUnconfirmedLookalikeShortFormIsOnlyProposed() {
        assertThat(derive(rel("Alex", "Alexander", "COGNATE")))
            .containsExactly(new Candidate("Alex", "Alexander", Type.DIMINUTIVE_OF, Status.PROPOSED, 40, "research:1"));
        assertThat(derive(rel("Annabelle", "Anna", "VARIANT")).get(0))
            .extracting(Candidate::from, Candidate::to, Candidate::status).containsExactly("Anna", "Annabelle", Status.PROPOSED);
    }

    @Test
    void diminutivesShortFormsAndTrueVariantsAndCognates() {
        assertThat(derive(rel("Nicholas", "Nicky", "DIMINUTIVE")).get(0))
            .isEqualTo(new Candidate("Nicky", "Nicholas", Type.DIMINUTIVE_OF, Status.ACCEPTED, 70, "research:1"));
        assertThat(derive(rel("Peggy", "Margaret", "SHORT_FORM_OF")).get(0))
            .isEqualTo(new Candidate("Peggy", "Margaret", Type.DIMINUTIVE_OF, Status.ACCEPTED, 80, "research:1"));
        assertThat(derive(rel("Jason", "Jayson", "VARIANT")).get(0).type()).isEqualTo(Type.SPELLING_VARIANT);
        assertThat(derive(rel("Nicholas", "Klaus", "COGNATE")).get(0).type()).isEqualTo(Type.COGNATE);
    }

    @Test
    void spellingsThatFoldTogetherAreNotLinkedAndAccentVariantsAgree() {
        assertThat(derive(rel("Jose", "José", "VARIANT"))).isEmpty();
        // Nicholas lists Nicolas as VARIANT and Nicolás as COGNATE: both are spelling variants
        assertThat(derive(rel("Nicholas", "Nicolas", "VARIANT"), rel("Nicholas", "Nicolás", "COGNATE")))
            .extracting(Candidate::type).containsOnly(Type.SPELLING_VARIANT);
    }

    @Test
    void genderedFormsAreAcceptedOnlyWithSsaSupportAtBothEnds() {
        assertThat(derive(rel("John", "Johanna", "FEMININE_FORM")).get(0))
            .isEqualTo(new Candidate("Johanna", "John", Type.FEMININE_OF, Status.ACCEPTED, 70, "research:1"));
        // Andrew MASCULINE_OF Andrea fits the US births; the reverse claim does not
        assertThat(derive(rel("Andrea", "Andrew", "MASCULINE_FORM")).get(0).status()).isEqualTo(Status.ACCEPTED);
        assertThat(derive(rel("Andrew", "Andrea", "MASCULINE_FORM")).get(0).status()).isEqualTo(Status.PROPOSED);
        assertThat(derive(rel("Kai", "Kaia", "FEMININE_FORM")).get(0).status()).isEqualTo(Status.PROPOSED);   // no SSA rows
    }
}
