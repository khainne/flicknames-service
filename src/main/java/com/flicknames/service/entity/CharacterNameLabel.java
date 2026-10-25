package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A human verdict on what first name, if any, a raw TMDB character string carries
 * ("Young Anna" -> Anna, "Fedaykin" -> not a name). A label applies to every character whose
 * full_name is exactly {@link #fullName}. Labels are the ground truth that overrides the
 * parser and they double as golden test cases (see the JSONL export).
 */
@Entity
@Table(name = "character_name_labels", indexes = {
    @Index(name = "idx_name_label_verdict", columnList = "verdict"),
    @Index(name = "idx_name_label_updated_at", columnList = "updatedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterNameLabel {

    public static final int MAX_FULL_NAME = 255;
    public static final int MAX_FIRST_NAME = 100;
    public static final int MAX_NOTE = 500;
    public static final int MAX_LABELED_BY = 100;

    public enum Verdict {
        /** The string carries a first name: {@link #firstName} holds it. */
        NAME,
        /** The string carries no usable first name (role, title + surname, creature...). */
        NOT_A_NAME,
        /** Looked at and deliberately left undecided; only keeps it out of the queue. */
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The raw character string, exactly as stored in characters.full_name. */
    @Column(nullable = false, unique = true, length = MAX_FULL_NAME)
    private String fullName;

    /** The first name; null unless verdict is NAME. */
    @Column(length = MAX_FIRST_NAME)
    private String firstName;

    @Convert(converter = LabelVerdictConverter.class)
    @Column(nullable = false, length = 20)
    private Verdict verdict;

    @Column(length = MAX_NOTE)
    private String note;

    @Column(length = MAX_LABELED_BY)
    private String labeledBy;

    /** When this label was last written into the characters table; null if not since it changed. */
    private LocalDateTime appliedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
