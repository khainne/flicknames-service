package com.flicknames.service.collector.scheduler;

import com.flicknames.service.collector.config.CollectorScheduleConfig;
import com.flicknames.service.collector.service.DataCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Year;

@Component
@ConditionalOnProperty(name = "collector.schedule.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataCollectionScheduler {

    private final DataCollectorService collectorService;
    private final CollectorScheduleConfig scheduleConfig;

    /**
     * Automatically collect popular movies
     * Default: Runs daily at 3 AM
     */
    @Scheduled(cron = "${collector.schedule.popular.cron:0 0 3 * * *}")
    public void collectPopularMovies() {
        if (!scheduleConfig.getPopular().isEnabled()) {
            log.debug("Popular movies collection is disabled");
            return;
        }

        int pages = scheduleConfig.getPopular().getPages();
        log.info("Starting scheduled collection of popular movies ({} pages) at {}",
                pages, LocalDateTime.now());

        try {
            collectorService.collectPopularMovies(pages);
            log.info("Successfully completed scheduled popular movies collection");
        } catch (Exception e) {
            log.error("Failed to collect popular movies during scheduled run", e);
        }
    }

    /**
     * Automatically collect movies for the current year
     * Default: Runs weekly on Sunday at 4 AM
     */
    @Scheduled(cron = "${collector.schedule.current-year.cron:0 0 4 * * SUN}")
    public void collectCurrentYearMovies() {
        if (!scheduleConfig.getCurrentYear().isEnabled()) {
            log.debug("Current year movies collection is disabled");
            return;
        }

        int currentYear = Year.now().getValue();
        int pages = scheduleConfig.getCurrentYear().getPages();

        log.info("Starting scheduled collection of {} movies ({} pages) at {}",
                currentYear, pages, LocalDateTime.now());

        try {
            collectorService.collectMoviesByYear(currentYear, pages);
            log.info("Successfully completed scheduled {} movies collection", currentYear);
        } catch (Exception e) {
            log.error("Failed to collect {} movies during scheduled run", currentYear, e);
        }
    }
}
