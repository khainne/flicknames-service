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
@Table(name = "people", indexes = {
    @Index(name = "idx_person_full_name", columnList = "fullName"),
    @Index(name = "idx_person_tmdb_id", columnList = "tmdbPersonId"),
    @Index(name = "idx_person_imdb_id", columnList = "imdbId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String fullName;

    @Column(unique = true)
    private Long tmdbPersonId;

    @Column(unique = true)
    private String imdbId; // nm1234567 format

    private String gender;

    @Column(length = 1000)
    private String biography;

    private String profilePath;

    @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<Credit> credits = new HashSet<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Helper method to add credit
    public void addCredit(Credit credit) {
        credits.add(credit);
        credit.setPerson(this);
    }

    // Helper method to remove credit
    public void removeCredit(Credit credit) {
        credits.remove(credit);
        credit.setPerson(null);
    }
}
