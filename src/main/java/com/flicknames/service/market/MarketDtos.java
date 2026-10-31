package com.flicknames.service.market;

import java.time.LocalDate;
import java.util.List;

/** Response shapes for the market endpoints. */
public final class MarketDtos {

    private MarketDtos() {
    }

    /**
     * Why a name is where it is: one film and the credit that counted most on it. For people,
     * fullName is the person and role is "as Character" or their job; for characters, fullName is
     * the character and playedBy the actor.
     */
    public record Driver(long movieId, String movieTitle, double movieRevenue, String fullName, String role,
                         String playedBy) {
    }

    /**
     * One name like a stock quote: index (share %), rank, changes, a sparkline and its drivers.
     * usual is the name's average index over the 52 weeks before the current six-week window, and
     * vsUsual the current index as a multiple of it (with a small floor, so rare names don't explode).
     */
    public record Quote(String name, double index, Integer rank, double change1w, Double pctChange1w,
                        double change6w, int movieCount, List<Double> sparkline, List<Driver> drivers,
                        double usual, double vsUsual) {
    }

    /** unusual: names well above their usual share, biggest multiple first. */
    public record Movers(Market market, LocalDate weekStart, LocalDate asOf, int windowDays, List<Quote> top,
                         List<Quote> gainers, List<Quote> losers, List<Quote> unusual) {
    }

    /** Index 0 with a null rank means the name wasn't in that week's top list. */
    public record Point(LocalDate week, double index, Integer rank) {
    }

    public record History(Market market, String name, List<Point> points) {
    }

    /** Points are weekly, or averaged per calendar month or year (rank = best rank in the period). */
    public enum Interval {
        WEEK,
        MONTH,
        YEAR
    }

    /** Names, most prominent first, each with its average index per period. */
    public record Heatmap(Market market, Interval interval, List<LocalDate> periods, List<HeatmapRow> rows) {
    }

    /** average is the name's mean weekly index over the whole range. */
    public record HeatmapRow(String name, double average, List<Double> values) {
    }
}
