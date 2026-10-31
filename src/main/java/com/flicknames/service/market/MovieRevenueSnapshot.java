package com.flicknames.service.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A film's TMDB revenue as seen on a given day, so box-office growth can be tracked over time. */
@Entity
@Table(name = "movie_revenue_snapshots",
    indexes = @Index(name = "idx_revenue_snapshot_movie_date", columnList = "movieId, snapshotDate"))
@Getter
@Setter
@NoArgsConstructor
public class MovieRevenueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long movieId;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(precision = 15, scale = 2)
    private BigDecimal revenue;
}
