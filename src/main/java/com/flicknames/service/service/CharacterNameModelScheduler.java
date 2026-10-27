package com.flicknames.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link CharacterNameModelAutoPass#tick} hourly by default, when character-name-model.schedule.enabled. */
@Component
@ConditionalOnProperty(name = "character-name-model.schedule.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CharacterNameModelScheduler {

    private final CharacterNameModelAutoPass autoPass;

    @Scheduled(cron = "${character-name-model.schedule.cron:0 23 * * * *}")
    public void run() {
        try {
            CharacterNameModelAutoPass.Outcome outcome = autoPass.tick();
            if (!"WAITING".equals(outcome.step()) && !"SKIPPED".equals(outcome.step())) {
                log.info("Scheduled model pass: {} - {}", outcome.step(), outcome.detail());
            }
        } catch (Exception e) {
            log.error("Scheduled model pass failed", e);
        }
    }
}
