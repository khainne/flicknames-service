package com.flicknames.service.collector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "collector.schedule")
@Getter
@Setter
public class CollectorScheduleConfig {

    /**
     * Enable automatic scheduled data collection
     */
    private boolean enabled = false;

    /**
     * Collect popular movies - runs at specified cron schedule
     * Default: Every day at 3 AM
     */
    private Popular popular = new Popular();

    /**
     * Collect movies for current year - runs at specified cron schedule
     * Default: Every Sunday at 4 AM
     */
    private CurrentYear currentYear = new CurrentYear();

    @Getter
    @Setter
    public static class Popular {
        private boolean enabled = true;
        private String cron = "0 0 3 * * *"; // 3 AM daily
        private int pages = 3; // 60 movies per run
    }

    @Getter
    @Setter
    public static class CurrentYear {
        private boolean enabled = true;
        private String cron = "0 0 4 * * SUN"; // 4 AM every Sunday
        private int pages = 5; // 100 movies per run
    }
}
