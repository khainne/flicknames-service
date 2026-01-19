package com.flicknames.service.collector.imdb;

/**
 * IMDb dataset file names and URLs
 * https://datasets.imdbws.com/
 */
public class IMDbDataset {

    public static final String BASE_URL = "https://datasets.imdbws.com/";

    public enum Dataset {
        TITLE_BASICS("title.basics.tsv.gz"),
        TITLE_PRINCIPALS("title.principals.tsv.gz"),
        NAME_BASICS("name.basics.tsv.gz"),
        TITLE_RATINGS("title.ratings.tsv.gz");

        private final String filename;

        Dataset(String filename) {
            this.filename = filename;
        }

        public String getFilename() {
            return filename;
        }

        public String getUrl() {
            return BASE_URL + filename;
        }
    }

    // Column indices for title.basics.tsv
    public static class TitleBasics {
        public static final int TCONST = 0;          // tt1234567
        public static final int TITLE_TYPE = 1;      // movie, tvSeries, etc.
        public static final int PRIMARY_TITLE = 2;   // Title
        public static final int ORIGINAL_TITLE = 3;  // Original title
        public static final int IS_ADULT = 4;        // 0 or 1
        public static final int START_YEAR = 5;      // YYYY or \N
        public static final int END_YEAR = 6;        // YYYY or \N (for TV series)
        public static final int RUNTIME_MINUTES = 7; // Integer or \N
        public static final int GENRES = 8;          // Comma-separated
    }

    // Column indices for title.principals.tsv
    public static class TitlePrincipals {
        public static final int TCONST = 0;          // tt1234567
        public static final int ORDERING = 1;        // Integer (cast order)
        public static final int NCONST = 2;          // nm1234567
        public static final int CATEGORY = 3;        // actor, director, writer, etc.
        public static final int JOB = 4;             // Job title (or \N)
        public static final int CHARACTERS = 5;      // JSON array of character names (or \N)
    }

    // Column indices for name.basics.tsv
    public static class NameBasics {
        public static final int NCONST = 0;             // nm1234567
        public static final int PRIMARY_NAME = 1;       // Full name
        public static final int BIRTH_YEAR = 2;         // YYYY or \N
        public static final int DEATH_YEAR = 3;         // YYYY or \N
        public static final int PRIMARY_PROFESSION = 4; // Comma-separated
        public static final int KNOWN_FOR_TITLES = 5;   // Comma-separated tconst
    }

    // Column indices for title.ratings.tsv
    public static class TitleRatings {
        public static final int TCONST = 0;          // tt1234567
        public static final int AVERAGE_RATING = 1;  // Float
        public static final int NUM_VOTES = 2;       // Integer
    }

    public static final String NULL_VALUE = "\\N";

    public static boolean isNull(String value) {
        return value == null || value.equals(NULL_VALUE) || value.isBlank();
    }
}
