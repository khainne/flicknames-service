package com.flicknames.service.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Nightly: refresh recent films' revenue from TMDB, then rebuild the last twelve weeks of the
 * name index (revenue for those weeks is still moving).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler {

    static final int REFRESH_DAYS = 120;
    static final int REBUILD_WEEKS = 12;

    private final RevenueRefreshService revenueRefreshService;
    private final NameIndexService nameIndexService;

    @Value("${market.schedule.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "${market.schedule.cron:0 30 5 * * *}", zone = "UTC")
    public void nightly() {
        if (!enabled) {
            return;
        }
        LocalDate today = MarketController.today();
        try {
            revenueRefreshService.refresh(REFRESH_DAYS, false, today);
        } catch (Exception e) {
            log.error("Nightly revenue refresh failed; rebuilding the index anyway", e);
        }
        try {
            nameIndexService.rebuild(today.minusWeeks(REBUILD_WEEKS), today, today);
        } catch (Exception e) {
            log.error("Nightly name index rebuild failed", e);
        }
    }
}
