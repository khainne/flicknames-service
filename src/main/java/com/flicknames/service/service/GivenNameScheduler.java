package com.flicknames.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the given-name catalog, classifies new short forms and rebuilds the name families nightly by
 * default, when given-names.refresh.enabled, so families and counts follow new research and credits.
 */
@Component
@ConditionalOnProperty(name = "given-names.refresh.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class GivenNameScheduler {

    private final GivenNameCatalog catalog;
    private final NameFamilyService families;

    @Scheduled(cron = "${given-names.refresh.cron:0 41 4 * * *}")
    public void run() {
        try {
            catalog.rebuild();
            try {
                families.classifyShortForms(500, false);   // short forms added since; a few pairs a night at most
            } catch (IllegalStateException e) {
                log.debug("Short forms not classified: {}", e.getMessage());
            }
            families.rebuild();
        } catch (Exception e) {
            log.error("Scheduled given-name catalog rebuild failed", e);
        }
    }
}
