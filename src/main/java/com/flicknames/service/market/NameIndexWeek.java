package com.flicknames.service.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One name's standing in one market for one week: its share of weighted box office from films
 * released in the six weeks up to the end of that week. Only the top names per week are stored.
 */
@Entity
@Table(name = "name_index_weekly",
    uniqueConstraints = @UniqueConstraint(name = "uk_name_index_week", columnNames = {"market", "nameKey", "weekStart"}),
    indexes = {
        @Index(name = "idx_name_index_market_week_rank", columnList = "market, weekStart, rankPosition"),
        @Index(name = "idx_name_index_market_name_week", columnList = "market, nameKey, weekStart")
    })
@Getter
@Setter
@NoArgsConstructor
public class NameIndexWeek {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Market market;

    /** Display spelling, e.g. "Michael". */
    @Column(nullable = false, length = 100)
    private String name;

    /** Lower-cased name used for lookups. */
    @Column(nullable = false, length = 100)
    private String nameKey;

    /** Monday of the week. */
    @Column(nullable = false)
    private LocalDate weekStart;

    /** Sum over films in the window of revenue x the name's best credit weight on that film. */
    @Column(nullable = false)
    private double weightedRevenue;

    /** Share of the market's weighted revenue that week, in percent. */
    @Column(nullable = false)
    private double indexValue;

    /** 1 = biggest share that week. */
    @Column(nullable = false)
    private int rankPosition;

    @Column(nullable = false)
    private int movieCount;
}
