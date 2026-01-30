package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "credits",
    indexes = {
        @Index(name = "idx_credit_person", columnList = "person_id"),
        @Index(name = "idx_credit_movie", columnList = "movie_id"),
        @Index(name = "idx_credit_character", columnList = "character_id"),
        @Index(name = "idx_credit_role_type", columnList = "roleType")
    },
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"person_id", "movie_id", "roleType", "job", "character_id"})
    }
)
@Data
@EqualsAndHashCode(exclude = {"person", "movie", "character"})
@ToString(exclude = {"person", "movie", "character"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Credit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private ScreenCharacter character; // Character portrayed (nullable for crew credits)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RoleType roleType;

    @Column(length = 100)
    private String department; // Acting, Directing, Production, Camera, etc.

    @Column(nullable = false, length = 100)
    private String job; // Actor, Director, Producer, Gaffer, etc.

    @Column(name = "cast_order")
    private Integer order; // For cast ordering (lower number = more prominent)

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum RoleType {
        CAST,
        CREW
    }
}
