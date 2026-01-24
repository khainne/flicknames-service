package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "characters", indexes = {
    @Index(name = "idx_character_first_name", columnList = "firstName"),
    @Index(name = "idx_character_full_name", columnList = "fullName"),
    @Index(name = "idx_character_name_type", columnList = "nameType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenCharacter {

    /**
     * Classification of character name types for proper first name extraction
     */
    public enum NameType {
        /** Standard name with extractable first name (e.g., "Tony Stark") */
        STANDARD,
        /** Single name that is the first name (e.g., "Napoleon", "Cher") */
        SINGLE_NAME,
        /** Title or honorific followed by surname (e.g., "Officer Daniels", "Dr. Smith") */
        TITLE_SURNAME,
        /** Role or description, not a personal name (e.g., "Security Guard", "Old Woman") */
        ROLE_DESCRIPTION,
        /** Numbered/generic role (e.g., "Bank Teller #2", "Soldier 1") */
        NUMBERED_ROLE,
        /** Unable to classify the name type */
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The extracted first name, or null if no valid first name exists.
     * For STANDARD names, this is the given name (e.g., "Tony" from "Tony Stark").
     * For SINGLE_NAME, this is the full name (e.g., "Napoleon").
     * For TITLE_SURNAME, ROLE_DESCRIPTION, NUMBERED_ROLE, and UNKNOWN, this is null.
     */
    private String firstName;

    private String lastName;

    @Column(nullable = false)
    private String fullName;

    private String gender;

    /** Classification of what type of character name this is */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private NameType nameType = NameType.UNKNOWN;

    @Column(length = 500)
    private String description;

    @OneToMany(mappedBy = "character", cascade = CascadeType.ALL)
    @Builder.Default
    private Set<Credit> credits = new HashSet<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
