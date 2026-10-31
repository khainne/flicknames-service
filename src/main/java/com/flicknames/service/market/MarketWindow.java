package com.flicknames.service.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Week and window arithmetic shared by the index builder and the read side. */
public final class MarketWindow {

    /** The index for a week covers films released in the six weeks up to the end of that week. */
    public static final int WINDOW_DAYS = 42;

    private MarketWindow() {
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Last day counted for a week: its Sunday, or today for the week in progress. */
    public static LocalDate asOf(LocalDate weekStart, LocalDate today) {
        LocalDate sunday = weekStart.plusDays(6);
        return sunday.isAfter(today) ? today : sunday;
    }

    public static LocalDate windowStart(LocalDate asOf) {
        return asOf.minusDays(WINDOW_DAYS - 1L);
    }
}
