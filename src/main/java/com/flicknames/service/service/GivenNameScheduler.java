package com.flicknames.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rebuilds the given-name catalog nightly by default, when given-names.refresh.enabled, so counts follow new credits. */
@Component
@ConditionalOnProperty(name = "given-names.refresh.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class GivenNameScheduler {

    private final GivenNameCatalog catalog;

    @Scheduled(cron = "${given-names.refresh.cron:0 41 4 * * *}")
    public void run() {
        try {
            catalog.rebuild();
        } catch (Exception e) {
            log.error("Scheduled given-name catalog rebuild failed", e);
        }
    }
}
