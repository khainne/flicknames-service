package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * National-level yearly statistics for an SSA name.
 * Contains aggregate counts across all states for a given year.
 */
@Entity
@Table(name = "ssa_name_yearly_stats",
    uniqueConstraints = @UniqueConstraint(name = "uk_ssa_yearly_name_year", columnNames = {"ssa_name_id", "year"}),
    indexes = {
        @Index(name = "idx_ssa_yearly_year", columnList = "year"),
        @Index(name = "idx_ssa_yearly_rank", columnList = "year, rank"),
        @Index(name = "idx_ssa_yearly_count", columnList = "year, count DESC")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsaNameYearlyStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ssa_name_id", nullable = false)
    private SsaName ssaName;

    /**
     * Year of birth (1880+)
     */
    @Column(nullable = false)
    private Integer year;

    /**
     * Number of births with this name nationally.
     * Note: Names with fewer than 5 occurrences are excluded by SSA.
     */
    @Column(nullable = false)
    private Integer count;

    /**
     * National rank within year and sex (1 = most popular).
     * Calculated after import.
     */
    private Integer rank;

    /**
     * Proportion of total births for this year+sex.
     * Calculated as: count / total_births_for_year_sex
     */
    @Column(precision = 10, scale = 8)
    private BigDecimal proportion;

    /**
     * Year-over-year rank change (positive = improved, negative = declined).
     * Calculated after import.
     */
    private Integer rankChange;

    @OneToMany(mappedBy = "yearlyStat", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SsaNameStateBreakdown> stateBreakdowns = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Helper to add a state breakdown
     */
    public void addStateBreakdown(SsaNameStateBreakdown breakdown) {
        stateBreakdowns.add(breakdown);
        breakdown.setYearlyStat(this);
    }
}
