package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One Message Batch sent for character first names, with what it cost. Written with plain JDBC. */
@Entity
@Table(name = "character_model_batches")
@Getter
@Setter
@NoArgsConstructor
public class CharacterModelBatch {

    @Id
    @Column(length = 64)
    private String batchId;

    @Column(length = 32)
    private String model;

    private int requests;
    private int items;

    /** The batch's processing status as last seen: in_progress, canceling or ended. */
    @Column(length = 16)
    private String status;

    private LocalDateTime submittedAt;
    private LocalDateTime collectedAt;
    private int failedRequests;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
}
