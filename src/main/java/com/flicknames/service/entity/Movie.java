package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "movies", indexes = {
    @Index(name = "idx_movie_title", columnList = "title"),
    @Index(name = "idx_movie_release_date", columnList = "releaseDate"),
    @Index(name = "idx_movie_revenue", columnList = "revenue"),
    @Index(name = "idx_movie_tmdb_id", columnList = "tmdbMovieId"),
    @Index(name = "idx_movie_imdb_id", columnList = "imdbId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate releaseDate;

    @Column(unique = true)
    private Long tmdbMovieId;

    @Column(unique = true)
    private String imdbId; // tt1234567 format

    @Column(precision = 15, scale = 2)
    private BigDecimal budget;

    @Column(precision = 15, scale = 2)
    private BigDecimal revenue;

    private Integer runtime; // in minutes

    @Column(length = 2000)
    private String overview;

    private String posterPath;

    private String backdropPath;

    @Column(length = 10)
    private String originalLanguage;

    private Double voteAverage;

    private Integer voteCount;

    @Column(length = 50)
    private String status; // Released, Post Production, etc.

    @OneToMany(mappedBy = "movie", cascade = CascadeType.ALL, orphanRemoval = true)
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
        credit.setMovie(this);
    }

    // Helper method to remove credit
    public void removeCredit(Credit credit) {
        credits.remove(credit);
        credit.setMovie(null);
    }
}
