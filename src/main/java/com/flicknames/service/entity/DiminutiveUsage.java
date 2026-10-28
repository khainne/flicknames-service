package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Whether a short form is still used as a nickname for its full form (Nick for Nicholas) or is an
 * independent given name today (Liam from William, Colin from Nicholas). Name families leave independent
 * names out. Keyed by the folded spellings, so it survives link rebuilds; an ADMIN decision wins over
 * the MODEL's.
 */
@Entity
@Table(name = "diminutive_usages",
    uniqueConstraints = @UniqueConstraint(name = "uk_diminutive_usage", columnNames = {"short_folded", "full_folded"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiminutiveUsage {

    public enum Usage { NICKNAME, INDEPENDENT }

    public enum Source { MODEL, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_folded", nullable = false, length = 100)
    private String shortFolded;

    @Column(name = "full_folded", nullable = false, length = 100)
    private String fullFolded;

    @Enumerated(EnumType.STRING)
    @Column(name = "name_usage", nullable = false, length = 12)
    private Usage usage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Source source;

    private LocalDateTime decidedAt;
}
