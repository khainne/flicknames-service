package com.flicknames.service.collector.dto;

import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * DTO for tracking comprehensive collection results across multiple strategies
 */
@Data
public class ComprehensiveCollectionResult {
    private int year;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, Integer> strategyResults = new HashMap<>();
    private int totalMoviesCollected;
    private int totalApiCalls;
    private boolean usOnlyFilter;
    private int maxPagesPerStrategy;

    public void addStrategyResult(String strategy, int count) {
        strategyResults.put(strategy, count);
        totalMoviesCollected += count;
    }

    public long getDurationMinutes() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return Duration.between(startTime, endTime).toMinutes();
    }

    public long getDurationSeconds() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return Duration.between(startTime, endTime).getSeconds();
    }
}
