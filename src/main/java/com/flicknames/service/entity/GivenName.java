package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per exact first-name spelling seen anywhere: people, characters, SSA, research and the
 * compound list. People and characters keep their first_name text and join on {@code spelling}, which
 * the existing first_name indexes serve. {@code folded} (lowercase, accent-free) groups spellings such
 * as José and Jose without being a key. The counts are evidence, refreshed by GivenNameCatalog: SSA
 * births by sex, and people by recorded gender, rather than one gender per name, since a spelling can
 * be masculine in one language and feminine in another (Andrea, Jean).
 */
@Entity
@Table(name = "given_names", indexes = {
    @Index(name = "idx_given_name_spelling", columnList = "spelling", unique = true),
    @Index(name = "idx_given_name_folded", columnList = "folded")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GivenName {

    public enum Quality {
        /** In the SSA data, borne by two or more people, or researched */
        LEXICON,
        UNVERIFIED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String spelling;

    @Column(nullable = false, length = 100)
    private String folded;

    @Column(nullable = false)
    private int peopleCount;

    @Column(nullable = false)
    private int characterCount;

    /** SSA births, all years, recorded as male */
    @Column(nullable = false)
    private long ssaMale;

    @Column(nullable = false)
    private long ssaFemale;

    @Column(nullable = false)
    private int peopleMale;

    @Column(nullable = false)
    private int peopleFemale;

    @Column(nullable = false)
    private boolean compound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Quality quality;

    private LocalDateTime refreshedAt;
}
