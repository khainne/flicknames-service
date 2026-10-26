package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One character in a staged re-parse: its name parts before and after. A dry run fills this table
 * without touching characters; applying copies the "new" values over, and the "old" values are the
 * backup a revert restores. Read and written with plain JDBC (see CharacterNameReparseService); the
 * entity exists so Hibernate creates the table.
 */
@Entity
@Table(name = "character_reparse_rows", indexes = {
    @Index(name = "idx_reparse_changed", columnList = "changed"),
    @Index(name = "idx_reparse_applied_at", columnList = "appliedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class CharacterReparseRow {

    public enum Source {
        /** From the parser. */
        PARSER,
        /** From a decisive human label, which wins over the parser. */
        LABEL,
        /** From a model, for a credit the parser was unsure of; a parser refresh leaves it alone. */
        MODEL
    }

    @Id
    private Long characterId;

    @Column(nullable = false)
    private String fullName;

    private String oldFirstName;
    private String oldLastName;
    /** The raw stored value, legacy names included, so a revert restores it exactly. */
    @Column(length = 20)
    private String oldNameType;
    @Column(length = 16)
    private String oldParserVersion;
    private Double oldParseConfidence;

    private String newFirstName;
    private String newLastName;
    @Column(length = 20)
    private String newNameType;

    private Double confidence;

    @Column(length = 16)
    private String parserVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    /** True when first name, last name or name type differ. */
    private boolean changed;

    private LocalDateTime appliedAt;

    /**
     * What the last apply wrote. A refresh re-plans an applied row without touching old*, so the next
     * apply and a revert recognise the row by these values.
     */
    private String appliedFirstName;
    private String appliedLastName;
    @Column(length = 20)
    private String appliedNameType;
}
