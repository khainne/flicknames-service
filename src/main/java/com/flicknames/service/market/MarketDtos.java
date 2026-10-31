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

    /** One name like a stock quote: index (share %), rank, changes, a sparkline and its drivers. */
    public record Quote(String name, double index, Integer rank, double change1w, Double pctChange1w,
                        double change6w, int movieCount, List<Double> sparkline, List<Driver> drivers) {
    }

    public record Movers(Market market, LocalDate weekStart, LocalDate asOf, int windowDays, List<Quote> top,
                         List<Quote> gainers, List<Quote> losers) {
    }

    /** Index 0 with a null rank means the name wasn't in that week's top list. */
    public record Point(LocalDate week, double index, Integer rank) {
    }

    public record History(Market market, String name, List<Point> points) {
    }
}
