package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * State-level breakdown of SSA name statistics.
 * Links to the national yearly stat for the same name+year.
 */
@Entity
@Table(name = "ssa_name_state_breakdowns",
    uniqueConstraints = @UniqueConstraint(name = "uk_ssa_state_yearly_state", columnNames = {"yearly_stat_id", "state_code"}),
    indexes = {
        @Index(name = "idx_ssa_state_code", columnList = "state_code"),
        @Index(name = "idx_ssa_state_rank", columnList = "state_code, rank")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsaNameStateBreakdown {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "yearly_stat_id", nullable = false)
    private SsaNameYearlyStat yearlyStat;

    /**
     * Two-letter state code (e.g., "CA", "NY", "TX")
     */
    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    /**
     * Number of births with this name in this state.
     * Note: Names with fewer than 5 occurrences in a state are excluded.
     */
    @Column(nullable = false)
    private Integer count;

    /**
     * Rank within state for this year+sex (1 = most popular in state).
     * Calculated after import.
     */
    private Integer rank;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
