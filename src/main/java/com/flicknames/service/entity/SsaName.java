package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalized SSA name entity - stores unique name+sex combinations once.
 * Each name links to yearly statistics and state breakdowns.
 */
@Entity
@Table(name = "ssa_names",
    uniqueConstraints = @UniqueConstraint(name = "uk_ssa_name_sex", columnNames = {"name", "sex"}),
    indexes = {
        @Index(name = "idx_ssa_name", columnList = "name"),
        @Index(name = "idx_ssa_sex", columnList = "sex")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsaName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The name as recorded by SSA (2-15 characters).
     * Hyphens and spaces are removed by SSA, so "Julie-Anne" becomes "Julieanne".
     */
    @Column(nullable = false, length = 15)
    private String name;

    /**
     * Sex: M (male) or F (female)
     */
    @Column(nullable = false, length = 1)
    private String sex;

    @OneToMany(mappedBy = "ssaName", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SsaNameYearlyStat> yearlyStats = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Helper to add a yearly stat
     */
    public void addYearlyStat(SsaNameYearlyStat stat) {
        yearlyStats.add(stat);
        stat.setSsaName(this);
    }
}
