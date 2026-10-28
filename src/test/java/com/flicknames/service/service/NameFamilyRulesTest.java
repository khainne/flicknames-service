package com.flicknames.service.service;

import com.flicknames.service.entity.NameFamilyMember.Role;
import com.flicknames.service.service.NameFamilyRules.Family;
import com.flicknames.service.service.NameFamilyRules.Link;
import com.flicknames.service.service.NameFamilyRules.Member;
import com.flicknames.service.service.NameFamilyRules.Name;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class NameFamilyRulesTest {

    private final Map<String, Name> names = new LinkedHashMap<>();

    private long id(String spelling, long count) {
        return names.computeIfAbsent(spelling, s -> new Name(names.size() + 1,
            s, com.flicknames.service.util.charactername.CharacterNameLexicon.fold(s), count)).id();
    }

    private long id(String spelling) {
        return id(spelling, 10);
    }

    private Link link(String from, String to) {
        return new Link(id(from), id(to));
    }

    private Map<String, List<String>> families(List<Link> diminutives, List<Link> variants) {
        Map<Long, String> spelling = new HashMap<>();
        names.values().forEach(n -> spelling.put(n.id(), n.spelling()));
        Map<String, List<String>> out = new TreeMap<>();
        for (Family f : NameFamilyRules.build(names.values(), diminutives, variants)) {
            List<String> members = new ArrayList<>();
            for (Member m : f.members()) {
                members.add(spelling.get(m.nameId()) + (m.role() == Role.HEAD ? "*" : "") + (m.shared() ? "~" : ""));
            }
            out.put(f.label(), members);
        }
        return out;
    }

    @Test
    void aSharedShortFormDoesNotMergeItsFamilies() {
        // Kit is short for both Christopher and Katherine; Andy for both Andrew and Andrea
        Map<String, List<String>> families = families(List.of(link("Kit", "Christopher"), link("Kit", "Katherine"),
            link("Chris", "Christopher"), link("Kate", "Katherine"), link("Andy", "Andrew"), link("Andy", "Andrea")), List.of());
        assertThat(families).containsOnlyKeys("Andrea", "Andrew", "Christopher", "Katherine");
        assertThat(families.get("Christopher")).containsExactlyInAnyOrder("Christopher*", "Chris", "Kit~");
        assertThat(families.get("Katherine")).containsExactlyInAnyOrder("Katherine*", "Kit~", "Kate");
        assertThat(families.get("Andrew")).containsExactly("Andrew*", "Andy~");
    }

    @Test
    void spellingsFoldedFormsAndSecondStepShortFormsJoinTheFullForm() {
        id("Nicholas", 1034);
        id("Nicolas", 300);
        id("Nicolás", 20);
        Map<String, List<String>> families = families(
            List.of(link("Nick", "Nicholas"), link("Nicky", "Nick"), link("Nico", "Nicolas")),
            List.of(link("Nicholas", "Nicolas")));
        assertThat(families).containsOnlyKeys("Nicholas");
        assertThat(families.get("Nicholas")).containsExactlyInAnyOrder("Nicholas*", "Nicolas", "Nicolás", "Nick", "Nicky", "Nico");
    }

    @Test
    void theFamilyIsLabelledByItsMostCommonFullFormNotItsShortForm() {
        id("Nick", 1946);
        id("Nicholas", 1034);
        id("Jose", 370);
        id("José", 351);
        Map<String, List<String>> families = families(List.of(link("Nick", "Nicholas"), link("Pepe", "José")), List.of());
        assertThat(families).containsOnlyKeys("Jose", "Nicholas");
        assertThat(families.get("Jose")).containsExactly("Jose*", "José", "Pepe");
    }

    @Test
    void aLoneNameWithNoFormsIsNotAFamilyAndHugeVariantChainsAreNotMerged() {
        id("Zelda");
        assertThat(families(List.of(), List.of())).isEmpty();
        List<Link> chain = new ArrayList<>();
        for (int i = 0; i < NameFamilyRules.MAX_SPELLINGS + 2; i++) {
            chain.add(link("Name" + i, "Name" + (i + 1)));
        }
        assertThat(families(List.of(), chain)).isEmpty();
    }
}
