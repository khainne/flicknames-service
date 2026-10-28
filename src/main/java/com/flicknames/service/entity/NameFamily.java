package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A name family: a full form (the head, which labels it), its spellings and its short forms in use
 * ("Nicholas": Nicolas, Nick, Nicky, Nico). Rebuilt from given_name_links by NameFamilyBuilder. The
 * counts are precomputed over the members that are not shared with another family, distinct people and
 * movies, so a person or film is counted once.
 */
@Entity
@Table(name = "name_families", indexes = @Index(name = "idx_name_family_head", columnList = "head_id", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NameFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "head_id", nullable = false)
    private Long headId;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false)
    private int members;

    @Column(nullable = false)
    private long people;

    @Column(nullable = false)
    private long peopleMovies;

    @Column(nullable = false)
    private long characters;

    @Column(nullable = false)
    private long characterMovies;

    private LocalDateTime rebuiltAt;
}
