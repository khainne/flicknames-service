package com.flicknames.service.collector.sse;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Event published during movie collection to broadcast progress via SSE.
 */
@Getter
public class CollectionProgressEvent extends ApplicationEvent {

    public enum EventType {
        COLLECTION_STARTED,
        STRATEGY_STARTED,
        PAGE_COMPLETED,
        MOVIE_COLLECTED,
        STRATEGY_COMPLETED,
        COLLECTION_COMPLETED,
        COLLECTION_ERROR,
        COLLECTION_CANCELLED
    }

    private final EventType eventType;
    private final Integer year;
    private final String strategy;
    private final Integer currentPage;
    private final Integer totalPages;
    private final Integer moviesCollected;
    private final String movieTitle;
    private final String message;

    private CollectionProgressEvent(Object source, EventType eventType, Integer year,
                                    String strategy, Integer currentPage, Integer totalPages,
                                    Integer moviesCollected, String movieTitle, String message) {
        super(source);
        this.eventType = eventType;
        this.year = year;
        this.strategy = strategy;
        this.currentPage = currentPage;
        this.totalPages = totalPages;
        this.moviesCollected = moviesCollected;
        this.movieTitle = movieTitle;
        this.message = message;
    }

    public static Builder builder(Object source, EventType eventType) {
        return new Builder(source, eventType);
    }

    public static class Builder {
        private final Object source;
        private final EventType eventType;
        private Integer year;
        private String strategy;
        private Integer currentPage;
        private Integer totalPages;
        private Integer moviesCollected;
        private String movieTitle;
        private String message;

        private Builder(Object source, EventType eventType) {
            this.source = source;
            this.eventType = eventType;
        }

        public Builder year(Integer year) {
            this.year = year;
            return this;
        }

        public Builder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder currentPage(Integer currentPage) {
            this.currentPage = currentPage;
            return this;
        }

        public Builder totalPages(Integer totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        public Builder moviesCollected(Integer moviesCollected) {
            this.moviesCollected = moviesCollected;
            return this;
        }

        public Builder movieTitle(String movieTitle) {
            this.movieTitle = movieTitle;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public CollectionProgressEvent build() {
            return new CollectionProgressEvent(source, eventType, year, strategy,
                currentPage, totalPages, moviesCollected, movieTitle, message);
        }
    }
}
