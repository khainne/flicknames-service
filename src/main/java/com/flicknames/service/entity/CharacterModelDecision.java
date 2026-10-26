package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A model's first-name decision for one staged character the parser was unsure of. Read and written with
 * plain JDBC (see CharacterNameModelService); the entity exists so Hibernate creates the table.
 */
@Entity
@Table(name = "character_model_decisions", indexes = {
    @Index(name = "idx_model_decision_batch", columnList = "batchId"),
    @Index(name = "idx_model_decision_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class CharacterModelDecision {

    public enum Status { PENDING, DONE, FAILED }

    @Id
    private Long characterId;

    @Column(nullable = false)
    private String fullName;

    @Column(length = 64)
    private String batchId;

    /** The batch request that carried this credit. */
    @Column(length = 64)
    private String customId;

    /** The parser's staged first name when the credit was sent. */
    private String parserFirstName;

    private String modelFirstName;

    @Column(length = 32)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 200)
    private String error;

    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
    /** When the decision was written into the re-parse staging table (then applied with /apply). */
    private LocalDateTime stagedAt;
}
