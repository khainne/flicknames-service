package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills in missing TMDB image paths: movie posters and backdrops, and people's profile photos
 * (earlier collection runs never stored those). Only empty fields are written; existing paths are
 * never overwritten. Works through TMDB movies in id order, one batch per call, so a caller can
 * resume from {@code nextAfterId} until {@code done}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageBackfillService {

    static final int MAX_BATCH = 1000;
    private static final int MAX_ERRORS_REPORTED = 10;

    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final TMDBClient tmdbClient;

    public Map<String, Object> backfill(boolean dryRun, long afterId, int batchSize) {
        int size = Math.max(1, Math.min(batchSize, MAX_BATCH));
        List<Movie> movies = movieRepository.findByTmdbMovieIdIsNotNullAndIdGreaterThanOrderByIdAsc(
            afterId, PageRequest.of(0, size));

        int postersFilled = 0;
        int backdropsFilled = 0;
        int peopleFilled = 0;
        int peopleStillMissing = 0;
        int tmdbCalls = 0;
        List<String> errors = new ArrayList<>();
        long lastId = afterId;

        for (Movie movie : movies) {
            lastId = movie.getId();
            try {
                if (movie.getPosterPath() == null || movie.getBackdropPath() == null) {
                    TMDBMovieDTO dto = tmdbClient.getMovie(movie.getTmdbMovieId());
                    tmdbCalls++;
                    boolean changed = false;
                    if (dto != null && movie.getPosterPath() == null && dto.getPosterPath() != null) {
                        postersFilled++;
                        movie.setPosterPath(dto.getPosterPath());
                        changed = true;
                    }
                    if (dto != null && movie.getBackdropPath() == null && dto.getBackdropPath() != null) {
                        backdropsFilled++;
                        movie.setBackdropPath(dto.getBackdropPath());
                        changed = true;
                    }
                    if (changed && !dryRun) {
                        movieRepository.save(movie);
                    }
                }

                // Only ask TMDB for credits when someone on this movie still lacks a photo.
                List<Person> missing = personRepository.findMissingPhotosForMovie(movie.getId());
                if (!missing.isEmpty()) {
                    TMDBCreditsDTO credits = tmdbClient.getMovieCredits(movie.getTmdbMovieId());
                    tmdbCalls++;
                    Map<Long, String> photos = photosByTmdbId(credits);
                    List<Person> updated = new ArrayList<>();
                    for (Person person : missing) {
                        String path = photos.get(person.getTmdbPersonId());
                        if (path != null) {
                            person.setProfilePath(path);
                            updated.add(person);
                        } else {
                            peopleStillMissing++;
                        }
                    }
                    peopleFilled += updated.size();
                    if (!dryRun && !updated.isEmpty()) {
                        personRepository.saveAll(updated);
                    }
                }
            } catch (Exception e) {
                if (errors.size() < MAX_ERRORS_REPORTED) {
                    errors.add("movie " + movie.getId() + " (tmdb " + movie.getTmdbMovieId() + "): " + e.getMessage());
                }
                log.warn("Image backfill failed for movie {} (tmdb {}): {}",
                    movie.getId(), movie.getTmdbMovieId(), e.getMessage());
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dryRun", dryRun);
        report.put("moviesScanned", movies.size());
        report.put("nextAfterId", lastId);
        report.put("done", movies.size() < size);
        report.put("postersFilled", postersFilled);
        report.put("backdropsFilled", backdropsFilled);
        report.put("peopleFilled", peopleFilled);
        report.put("peopleWithoutTmdbPhoto", peopleStillMissing);
        report.put("tmdbCalls", tmdbCalls);
        report.put("errors", errors);
        log.info("Image backfill batch: {}", report);
        return report;
    }

    /** TMDB person id to profile path, for everyone in the credits who has a photo. */
    private static Map<Long, String> photosByTmdbId(TMDBCreditsDTO credits) {
        Map<Long, String> photos = new HashMap<>();
        if (credits == null) {
            return photos;
        }
        if (credits.getCast() != null) {
            for (TMDBCreditsDTO.CastMember member : credits.getCast()) {
                addPhoto(photos, member.getId(), member.getProfilePath());
            }
        }
        if (credits.getCrew() != null) {
            for (TMDBCreditsDTO.CrewMember member : credits.getCrew()) {
                addPhoto(photos, member.getId(), member.getProfilePath());
            }
        }
        return photos;
    }

    private static void addPhoto(Map<Long, String> photos, Long tmdbId, String path) {
        if (tmdbId != null && path != null && !path.isBlank()) {
            photos.putIfAbsent(tmdbId, path);
        }
    }
}
