package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A directed relationship between two given names, read "from TYPE to": Nick DIMINUTIVE_OF Nicholas,
 * Johanna FEMININE_OF John, Mary Kate COMPOUND_OF Mary. SPELLING_VARIANT and COGNATE are symmetric and
 * stored once, with from_id below to_id. Each source keeps its own row, so a rebuild of one source never
 * touches another, and an ADMIN row (ACCEPTED or REJECTED) overrides the others for the same pair and
 * type. Only ACCEPTED links are meant to feed name families.
 */
@Entity
@Table(name = "given_name_links",
    uniqueConstraints = @UniqueConstraint(name = "uk_given_name_link",
        columnNames = {"from_id", "to_id", "type", "source"}),
    indexes = {
        @Index(name = "idx_given_name_link_from", columnList = "from_id, type"),
        @Index(name = "idx_given_name_link_to", columnList = "to_id, type"),
        @Index(name = "idx_given_name_link_status", columnList = "status, source")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GivenNameLink {

    public enum Type {
        DIMINUTIVE_OF, SPELLING_VARIANT, COGNATE, FEMININE_OF, MASCULINE_OF, COMPOUND_OF;

        public boolean symmetric() {
            return this == SPELLING_VARIANT || this == COGNATE;
        }
    }

    public enum Source { RESEARCH, COMPOUND_LIST, PUBLIC_DATASET, ADMIN }

    public enum Status { PROPOSED, ACCEPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "to_id", nullable = false)
    private Long toId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** 0-100 */
    @Column(nullable = false)
    private int confidence;

    /** Where it came from: "research:43", "compound:12", "admin" */
    @Column(length = 100)
    private String evidenceRef;

    private LocalDateTime createdAt;
}
