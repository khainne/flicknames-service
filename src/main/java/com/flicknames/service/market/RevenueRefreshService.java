package com.flicknames.service.market;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TMDB fills in a film's revenue weeks after release and keeps raising it while it plays, but the
 * collector only fetched each film once. This re-fetches recent films, updates their revenue (and
 * any missing poster or backdrop), and records a dated snapshot whenever the revenue changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueRefreshService {

    static final int MAX_DAYS = 365;
    private static final int MAX_ERRORS_REPORTED = 10;

    private final MovieRepository movieRepository;
    private final MovieRevenueSnapshotRepository snapshotRepository;
    private final TMDBClient tmdbClient;

    public Map<String, Object> refresh(int days, boolean dryRun, LocalDate today) {
        int span = Math.max(1, Math.min(days, MAX_DAYS));
        List<Movie> movies = movieRepository.findByTmdbMovieIdIsNotNullAndReleaseDateBetween(today.minusDays(span), today);
        int changed = 0;
        int snapshots = 0;
        List<String> errors = new ArrayList<>();
        for (Movie movie : movies) {
            try {
                TMDBMovieDTO dto = tmdbClient.getMovie(movie.getTmdbMovieId());
                if (dto == null) {
                    continue;
                }
                BigDecimal revenue = dto.getRevenue() != null && dto.getRevenue() > 0
                    ? BigDecimal.valueOf(dto.getRevenue()) : null;
                boolean revenueChanged = revenue != null
                    && (movie.getRevenue() == null || movie.getRevenue().compareTo(revenue) != 0);
                boolean imagesChanged = (movie.getPosterPath() == null && dto.getPosterPath() != null)
                    || (movie.getBackdropPath() == null && dto.getBackdropPath() != null);
                boolean needsSnapshot = revenue != null && snapshotRepository
                    .findFirstByMovieIdOrderBySnapshotDateDesc(movie.getId())
                    .map(s -> s.getRevenue() == null || s.getRevenue().compareTo(revenue) != 0)
                    .orElse(true);
                if (revenueChanged) {
                    changed++;
                }
                if (needsSnapshot) {
                    snapshots++;
                }
                if (dryRun) {
                    continue;
                }
                if (revenueChanged || imagesChanged) {
                    if (revenueChanged) {
                        movie.setRevenue(revenue);
                    }
                    if (movie.getPosterPath() == null) {
                        movie.setPosterPath(dto.getPosterPath());
                    }
                    if (movie.getBackdropPath() == null) {
                        movie.setBackdropPath(dto.getBackdropPath());
                    }
                    movieRepository.save(movie);
                }
                if (needsSnapshot) {
                    MovieRevenueSnapshot snapshot = new MovieRevenueSnapshot();
                    snapshot.setMovieId(movie.getId());
                    snapshot.setSnapshotDate(today);
                    snapshot.setRevenue(Objects.requireNonNull(revenue));
                    snapshotRepository.save(snapshot);
                }
            } catch (Exception e) {
                if (errors.size() < MAX_ERRORS_REPORTED) {
                    errors.add("movie " + movie.getId() + " (tmdb " + movie.getTmdbMovieId() + "): " + e.getMessage());
                }
                log.warn("Revenue refresh failed for movie {}: {}", movie.getId(), e.getMessage());
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dryRun", dryRun);
        report.put("days", span);
        report.put("moviesChecked", movies.size());
        report.put("revenueChanged", changed);
        report.put("snapshots", snapshots);
        report.put("errors", errors);
        log.info("Revenue refresh: {}", report);
        return report;
    }
}
