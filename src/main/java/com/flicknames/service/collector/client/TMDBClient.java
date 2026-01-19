package com.flicknames.service.collector.client;

import com.flicknames.service.collector.config.TMDBConfig;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class TMDBClient {

    private final TMDBConfig config;
    private final RestTemplate restTemplate = new RestTemplate();

    private long lastRequestTime = 0;

    /**
     * Fetch movie details by TMDB ID
     */
    public TMDBMovieDTO getMovie(Long movieId) {
        String url = buildUrl("/movie/" + movieId);
        rateLimit();

        log.debug("Fetching movie details for ID: {}", movieId);
        return restTemplate.getForObject(url, TMDBMovieDTO.class);
    }

    /**
     * Fetch movie credits (cast and crew) by TMDB ID
     */
    public TMDBCreditsDTO getMovieCredits(Long movieId) {
        String url = buildUrl("/movie/" + movieId + "/credits");
        rateLimit();

        log.debug("Fetching credits for movie ID: {}", movieId);
        return restTemplate.getForObject(url, TMDBCreditsDTO.class);
    }

    /**
     * Fetch popular movies (useful for discovering movies to collect)
     */
    public PopularMoviesResponse getPopularMovies(int page) {
        String url = buildUrl("/movie/popular", "page", String.valueOf(page));
        rateLimit();

        log.debug("Fetching popular movies, page: {}", page);
        return restTemplate.getForObject(url, PopularMoviesResponse.class);
    }

    /**
     * Fetch top box office movies by year
     */
    public DiscoverMoviesResponse discoverMoviesByYear(int year, int page) {
        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl() + "/discover/movie")
                .queryParam("api_key", config.getApiKey())
                .queryParam("sort_by", "revenue.desc")
                .queryParam("primary_release_year", year)
                .queryParam("page", page)
                .build()
                .toUriString();

        rateLimit();

        log.debug("Discovering movies for year: {}, page: {}", year, page);
        return restTemplate.getForObject(url, DiscoverMoviesResponse.class);
    }

    /**
     * Build URL with API key
     */
    private String buildUrl(String path, String... extraParams) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + path)
                .queryParam("api_key", config.getApiKey());

        for (int i = 0; i < extraParams.length; i += 2) {
            if (i + 1 < extraParams.length) {
                builder.queryParam(extraParams[i], extraParams[i + 1]);
            }
        }

        return builder.build().toUriString();
    }

    /**
     * Rate limiting to respect TMDB API limits
     */
    private void rateLimit() {
        if (!config.getRateLimit().isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long minInterval = 1000 / config.getRateLimit().getRequestsPerSecond();
        long timeSinceLastRequest = now - lastRequestTime;

        if (timeSinceLastRequest < minInterval) {
            try {
                long sleepTime = minInterval - timeSinceLastRequest;
                log.trace("Rate limiting: sleeping {}ms", sleepTime);
                TimeUnit.MILLISECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limit sleep interrupted", e);
            }
        }

        lastRequestTime = System.currentTimeMillis();
    }

    // Response wrapper classes
    public static class PopularMoviesResponse {
        public int page;
        public java.util.List<TMDBMovieDTO> results;
        public int total_pages;
        public int total_results;
    }

    public static class DiscoverMoviesResponse {
        public int page;
        public java.util.List<TMDBMovieDTO> results;
        public int total_pages;
        public int total_results;
    }
}
