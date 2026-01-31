package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.ComprehensiveCollectionResult;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.collector.sse.CollectionProgressEvent;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.DataSource;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.*;
import com.flicknames.service.util.CharacterNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataCollectorService {

    private final TMDBClient tmdbClient;
    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final ScreenCharacterRepository screenCharacterRepository;
    private final CreditRepository creditRepository;
    private final DataSourceRepository dataSourceRepository;
    private final CharacterNameParser characterNameParser;
    private final ApplicationEventPublisher eventPublisher;

    // Cancellation flag for long-running collections
    private volatile boolean cancelled = false;
    private volatile String currentOperation = null;
    private volatile int totalMoviesCollected = 0;

    /**
     * Collect a single movie and all its credits from TMDB
     */
    @Transactional
    public Movie collectMovie(Long tmdbMovieId) {
        log.info("Collecting movie with TMDB ID: {}", tmdbMovieId);

        // Check if we've already successfully fetched this movie
        Optional<DataSource> existingSource = dataSourceRepository.findBySourceTypeAndExternalIdAndEntityType(
                DataSource.SourceType.TMDB,
                tmdbMovieId.toString(),
                DataSource.EntityType.MOVIE
        );

        if (existingSource.isPresent() && existingSource.get().getStatus() == DataSource.FetchStatus.SUCCESS) {
            log.info("Movie {} already fetched from TMDB, skipping API call", tmdbMovieId);
            return movieRepository.findById(existingSource.get().getInternalId()).orElse(null);
        }

        // Fetch movie details from TMDB API
        TMDBMovieDTO movieDTO = tmdbClient.getMovie(tmdbMovieId);
        if (movieDTO == null) {
            log.warn("Movie not found: {}", tmdbMovieId);
            return null;
        }

        // Check if movie already exists
        Optional<Movie> existingMovie = movieRepository.findByTmdbMovieId(tmdbMovieId);
        Movie movie;

        if (existingMovie.isPresent()) {
            log.debug("Movie already exists, updating: {}", movieDTO.getTitle());
            movie = existingMovie.get();
            updateMovieFromDTO(movie, movieDTO);
        } else {
            log.debug("Creating new movie: {}", movieDTO.getTitle());
            movie = createMovieFromDTO(movieDTO);
        }

        movie = movieRepository.save(movie);

        // Fetch and process credits
        TMDBCreditsDTO creditsDTO = tmdbClient.getMovieCredits(tmdbMovieId);
        if (creditsDTO != null) {
            processCredits(movie, creditsDTO);
        }

        log.info("Successfully collected movie: {} ({}) with {} credits",
                movie.getTitle(), movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : "unknown", movie.getCredits().size());

        // Record successful fetch to avoid redundant API calls
        recordDataSource(DataSource.SourceType.TMDB, tmdbMovieId.toString(),
                DataSource.EntityType.MOVIE, movie.getId(), DataSource.FetchStatus.SUCCESS, null);

        return movie;
    }

    /**
     * Collect popular movies
     * Note: No @Transactional here - each collectMovie() call runs in its own transaction
     */
    public void collectPopularMovies(int pages) {
        log.info("Collecting popular movies, {} pages", pages);

        for (int page = 1; page <= pages; page++) {
            TMDBClient.PopularMoviesResponse response = tmdbClient.getPopularMovies(page);

            if (response != null && response.results != null) {
                log.info("Processing page {}/{}, {} movies", page, response.total_pages, response.results.size());

                for (TMDBMovieDTO movieDTO : response.results) {
                    try {
                        collectMovie(movieDTO.getId());
                    } catch (Exception e) {
                        log.error("Failed to collect movie ID {}: {}", movieDTO.getId(), e.getMessage(), e);
                    }
                }
            }
        }

        log.info("Completed collecting popular movies");
    }

    /**
     * Collect top box office movies for a specific year
     * Note: No @Transactional here - each collectMovie() call runs in its own transaction
     */
    public void collectMoviesByYear(int year, int pages) {
        log.info("Collecting movies for year {}, {} pages", year, pages);

        for (int page = 1; page <= pages; page++) {
            TMDBClient.DiscoverMoviesResponse response = tmdbClient.discoverMoviesByYear(year, page);

            if (response != null && response.results != null) {
                log.info("Processing year {} page {}/{}, {} movies",
                        year, page, response.total_pages, response.results.size());

                for (TMDBMovieDTO movieDTO : response.results) {
                    try {
                        collectMovie(movieDTO.getId());
                    } catch (Exception e) {
                        log.error("Failed to collect movie ID {}: {}", movieDTO.getId(), e.getMessage(), e);
                    }
                }
            }
        }

        log.info("Completed collecting movies for year {}", year);
    }

    /**
     * Comprehensive collection for a single year using multiple sorting strategies
     * to maximize coverage and overcome TMDB's 500-page limit
     */
    public ComprehensiveCollectionResult collectYearComprehensive(
            int year,
            boolean usOnlyFilter,
            int maxPagesPerStrategy) {

        // Reset cancellation flag and set current operation
        cancelled = false;
        totalMoviesCollected = 0;
        currentOperation = String.format("Comprehensive collection for year %d", year);

        log.info("Starting comprehensive collection for year {} (US only: {}, max pages: {})",
                year, usOnlyFilter, maxPagesPerStrategy);

        // Publish collection started event
        publishProgress(CollectionProgressEvent.EventType.COLLECTION_STARTED, year, null, null, null, 0, null,
                String.format("Started comprehensive collection for year %d", year));

        ComprehensiveCollectionResult result = new ComprehensiveCollectionResult();
        result.setYear(year);
        result.setUsOnlyFilter(usOnlyFilter);
        result.setMaxPagesPerStrategy(maxPagesPerStrategy);
        result.setStartTime(LocalDateTime.now());

        try {
            // Try multiple sorting strategies to catch different movies
            String[] sortStrategies = {
                    "popularity.desc",
                    "vote_count.desc",
                    "primary_release_date.desc",
                    "original_title.asc"
            };

            for (String sortBy : sortStrategies) {
                // Check cancellation before starting strategy
                if (cancelled) {
                    break;
                }

                // Publish strategy started event
                publishProgress(CollectionProgressEvent.EventType.STRATEGY_STARTED, year, sortBy, null, null,
                        totalMoviesCollected, null, String.format("Starting strategy: %s", sortBy));

                int moviesInStrategy = collectWithSort(year, sortBy, usOnlyFilter, maxPagesPerStrategy);
                result.addStrategyResult(sortBy, moviesInStrategy);
                log.info("Strategy {} collected {} movies", sortBy, moviesInStrategy);

                // Publish strategy completed event
                publishProgress(CollectionProgressEvent.EventType.STRATEGY_COMPLETED, year, sortBy, null, null,
                        totalMoviesCollected, null, String.format("Completed strategy: %s (%d movies)", sortBy, moviesInStrategy));
            }

            result.setEndTime(LocalDateTime.now());

            if (cancelled) {
                log.warn("Comprehensive collection for year {} was CANCELLED. Partial collection: {} movies, Duration: {} minutes",
                        year, result.getTotalMoviesCollected(), result.getDurationMinutes());
                publishProgress(CollectionProgressEvent.EventType.COLLECTION_CANCELLED, year, null, null, null,
                        totalMoviesCollected, null, String.format("Collection cancelled. Collected %d movies", totalMoviesCollected));
            } else {
                log.info("Comprehensive collection for year {} completed. Total movies: {}, Duration: {} minutes",
                        year, result.getTotalMoviesCollected(), result.getDurationMinutes());
                publishProgress(CollectionProgressEvent.EventType.COLLECTION_COMPLETED, year, null, null, null,
                        totalMoviesCollected, null, String.format("Collection completed. Total: %d movies in %.1f minutes",
                                totalMoviesCollected, result.getDurationSeconds() / 60.0));
            }

            return result;
        } catch (Exception e) {
            log.error("Unexpected error during collection for year {}: {}", year, e.getMessage(), e);
            publishProgress(CollectionProgressEvent.EventType.COLLECTION_ERROR, year, null, null, null,
                    totalMoviesCollected, null, String.format("Collection failed with error: %s", e.getMessage()));
            throw e;
        } finally {
            // Always clear current operation, even if an exception occurs
            currentOperation = null;
        }
    }

    /**
     * Collect movies using a specific sorting strategy
     */
    private int collectWithSort(int year, String sortBy, boolean usOnly, int maxPages) {
        int collected = 0;
        String originCountry = usOnly ? "US" : null;
        Integer minVoteCount = 10; // Filter out very obscure entries

        for (int page = 1; page <= maxPages; page++) {
            // Check cancellation flag
            if (cancelled) {
                log.warn("Collection cancelled by user at page {} for year {} (sort: {})", page, year, sortBy);
                break;
            }

            TMDBClient.DiscoverMoviesResponse response = tmdbClient.discoverMoviesByYearWithFilters(
                    year, page, sortBy, originCountry, minVoteCount, null
            );

            if (response == null || response.results == null || response.results.isEmpty()) {
                break;
            }

            int totalPages = Math.min(response.total_pages, maxPages);

            // Check if we're hitting the 500-page limit
            if (page >= 500 && response.total_pages > 500) {
                log.warn("Hit 500-page limit for year {} with sort {}. Consider segmentation.",
                        year, sortBy);
                break;
            }

            for (TMDBMovieDTO movieDTO : response.results) {
                // Check cancellation flag before each movie
                if (cancelled) {
                    log.warn("Collection cancelled by user during movie collection");
                    return collected;
                }

                try {
                    Movie movie = collectMovie(movieDTO.getId());
                    if (movie != null) {
                        collected++;
                        totalMoviesCollected++;

                        // Publish movie collected event
                        publishProgress(CollectionProgressEvent.EventType.MOVIE_COLLECTED, year, sortBy,
                                page, totalPages, totalMoviesCollected, movie.getTitle(), null);
                    }
                } catch (Exception e) {
                    log.error("Failed to collect movie ID {}: {}", movieDTO.getId(), e.getMessage());
                    publishProgress(CollectionProgressEvent.EventType.COLLECTION_ERROR, year, sortBy,
                            page, totalPages, totalMoviesCollected, null,
                            String.format("Error collecting movie ID %d: %s", movieDTO.getId(), e.getMessage()));
                }
            }

            // Publish page completed event
            publishProgress(CollectionProgressEvent.EventType.PAGE_COMPLETED, year, sortBy,
                    page, totalPages, totalMoviesCollected, null,
                    String.format("Completed page %d/%d", page, totalPages));

            // Stop if we've processed all pages
            if (page >= response.total_pages) {
                break;
            }
        }

        return collected;
    }

    /**
     * Segmented collection for high-volume years (>10k results)
     * Uses vote count ranges to stay under the 500-page limit
     */
    public void collectYearSegmented(int year, boolean usOnly) {
        log.info("Starting segmented collection for year {} (high volume, US only: {})", year, usOnly);

        // Segment by vote count to stay under 10k results per segment
        Integer[][] voteCountSegments = {
                {1000, null},        // High popularity: >=1000 votes
                {100, 999},          // Medium: 100-999 votes
                {10, 99},            // Low: 10-99 votes
                {null, 9}            // Very low: <10 votes
        };

        for (Integer[] segment : voteCountSegments) {
            collectYearVoteSegment(year, segment[0], segment[1], usOnly);
        }

        log.info("Completed segmented collection for year {}", year);
    }

    /**
     * Collect a specific vote count segment for a year
     */
    private void collectYearVoteSegment(int year, Integer voteGte, Integer voteLte, boolean usOnly) {
        log.info("Collecting year {} segment: votes [{}, {}]", year, voteGte, voteLte);

        String originCountry = usOnly ? "US" : null;
        int page = 1;
        int collected = 0;

        while (page <= 500) { // Respect 500-page limit
            TMDBClient.DiscoverMoviesResponse response = tmdbClient.discoverMoviesByYearWithFilters(
                    year, page, "popularity.desc", originCountry, voteGte, voteLte
            );

            if (response == null || response.results == null || response.results.isEmpty()) {
                break;
            }

            for (TMDBMovieDTO movieDTO : response.results) {
                try {
                    Movie movie = collectMovie(movieDTO.getId());
                    if (movie != null) {
                        collected++;
                    }
                } catch (Exception e) {
                    log.error("Failed to collect movie ID {}: {}", movieDTO.getId(), e.getMessage());
                }
            }

            if (page >= response.total_pages) {
                break;
            }
            page++;
        }

        log.info("Segment [{}, {}] collected {} movies", voteGte, voteLte, collected);
    }

    /**
     * Process credits (cast and crew) for a movie
     */
    private void processCredits(Movie movie, TMDBCreditsDTO creditsDTO) {
        Map<Long, Person> personCache = new HashMap<>();
        Map<String, ScreenCharacter> characterCache = new HashMap<>();

        // Process cast
        if (creditsDTO.getCast() != null) {
            for (int i = 0; i < creditsDTO.getCast().size(); i++) {
                TMDBCreditsDTO.CastMember castMember = creditsDTO.getCast().get(i);

                try {
                    Person person = getOrCreatePerson(castMember.getId(), castMember.getName(),
                            castMember.getGender(), personCache);
                    ScreenCharacter character = getOrCreateCharacter(castMember.getCharacter(), characterCache);

                    createCredit(movie, person, character, Credit.RoleType.CAST,
                            "Acting", "Actor", i + 1);
                } catch (Exception e) {
                    log.error("Failed to process cast member {}: {}", castMember.getName(), e.getMessage());
                }
            }
        }

        // Process crew
        if (creditsDTO.getCrew() != null) {
            for (TMDBCreditsDTO.CrewMember crewMember : creditsDTO.getCrew()) {
                try {
                    Person person = getOrCreatePerson(crewMember.getId(), crewMember.getName(),
                            crewMember.getGender(), personCache);

                    createCredit(movie, person, null, Credit.RoleType.CREW,
                            crewMember.getDepartment(), crewMember.getJob(), null);
                } catch (Exception e) {
                    log.error("Failed to process crew member {}: {}", crewMember.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Get existing person or create new one
     */
    private Person getOrCreatePerson(Long tmdbId, String fullName, Integer gender,
                                      Map<Long, Person> cache) {
        if (cache.containsKey(tmdbId)) {
            return cache.get(tmdbId);
        }

        Optional<Person> existing = personRepository.findByTmdbPersonId(tmdbId);
        if (existing.isPresent()) {
            cache.put(tmdbId, existing.get());
            return existing.get();
        }

        Person person = new Person();
        person.setTmdbPersonId(tmdbId);
        person.setFullName(fullName);

        // Parse first and last name
        String[] nameParts = parseFullName(fullName);
        person.setFirstName(nameParts[0]);
        person.setLastName(nameParts[1]);

        person.setGender(mapGender(gender));

        person = personRepository.save(person);
        cache.put(tmdbId, person);

        return person;
    }

    /**
     * Get existing character or create new one
     */
    private ScreenCharacter getOrCreateCharacter(String characterName, Map<String, ScreenCharacter> cache) {
        if (characterName == null || characterName.isBlank()) {
            return null;
        }

        String normalizedName = characterName.trim();
        if (cache.containsKey(normalizedName)) {
            return cache.get(normalizedName);
        }

        Optional<ScreenCharacter> existing = screenCharacterRepository.findByFullName(normalizedName);
        if (existing.isPresent()) {
            cache.put(normalizedName, existing.get());
            return existing.get();
        }

        ScreenCharacter character = new ScreenCharacter();
        character.setFullName(normalizedName);

        // Parse and classify the character name using intelligent parser
        CharacterNameParser.ParseResult parseResult = characterNameParser.parse(normalizedName);
        character.setFirstName(parseResult.getFirstName());
        character.setLastName(parseResult.getLastName());
        character.setNameType(parseResult.getNameType());

        character = screenCharacterRepository.save(character);
        cache.put(normalizedName, character);

        return character;
    }

    /**
     * Create a credit linking person, character, and movie
     */
    private void createCredit(Movie movie, Person person, ScreenCharacter character,
                              Credit.RoleType roleType, String department, String job, Integer order) {
        // Check if credit already exists
        boolean exists = creditRepository.existsByMovieAndPersonAndRoleTypeAndJob(
                movie, person, roleType, job);

        if (exists) {
            log.trace("Credit already exists for {} in {}", person.getFullName(), movie.getTitle());
            return;
        }

        Credit credit = new Credit();
        credit.setMovie(movie);
        credit.setPerson(person);
        credit.setCharacter(character);
        credit.setRoleType(roleType);
        credit.setDepartment(department);
        credit.setJob(job);
        credit.setOrder(order);

        creditRepository.save(credit);
    }

    /**
     * Create Movie entity from TMDB DTO
     */
    private Movie createMovieFromDTO(TMDBMovieDTO dto) {
        Movie movie = new Movie();
        updateMovieFromDTO(movie, dto);
        movie.setTmdbMovieId(dto.getId());
        return movie;
    }

    /**
     * Update existing movie from TMDB DTO
     */
    private void updateMovieFromDTO(Movie movie, TMDBMovieDTO dto) {
        movie.setTitle(dto.getTitle());
        movie.setReleaseDate(dto.getReleaseDate());
        movie.setRevenue(dto.getRevenue() != null ? java.math.BigDecimal.valueOf(dto.getRevenue()) : null);
        movie.setBudget(dto.getBudget() != null ? java.math.BigDecimal.valueOf(dto.getBudget()) : null);
        movie.setRuntime(dto.getRuntime());
        movie.setOverview(dto.getOverview());
        movie.setPosterPath(dto.getPosterPath());
        movie.setBackdropPath(dto.getBackdropPath());
        movie.setOriginalLanguage(dto.getOriginalLanguage());
        movie.setVoteAverage(dto.getVoteAverage());
        movie.setVoteCount(dto.getVoteCount());
        movie.setStatus(dto.getStatus());
    }


    /**
     * Parse full name into first and last name (for Person entities - actors/crew)
     */
    private String[] parseFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return new String[]{"Unknown", ""};
        }

        String trimmed = fullName.trim();
        int lastSpace = trimmed.lastIndexOf(' ');

        if (lastSpace > 0) {
            return new String[]{
                    trimmed.substring(0, lastSpace).trim(),
                    trimmed.substring(lastSpace + 1).trim()
            };
        } else {
            return new String[]{trimmed, ""};
        }
    }

    /**
     * Map TMDB gender code to our gender string
     */
    private String mapGender(Integer genderCode) {
        if (genderCode == null) {
            return "Unknown";
        }

        return switch (genderCode) {
            case 1 -> "Female";
            case 2 -> "Male";
            case 3 -> "Non-binary";
            default -> "Unknown";
        };
    }

    /**
     * Record data source fetch to avoid redundant API calls
     */
    private void recordDataSource(DataSource.SourceType sourceType, String externalId,
                                   DataSource.EntityType entityType, Long internalId,
                                   DataSource.FetchStatus status, String errorMessage) {
        Optional<DataSource> existing = dataSourceRepository.findBySourceTypeAndExternalIdAndEntityType(
                sourceType, externalId, entityType);

        DataSource dataSource;
        if (existing.isPresent()) {
            dataSource = existing.get();
            dataSource.setLastUpdatedAt(java.time.LocalDateTime.now());
        } else {
            dataSource = new DataSource();
            dataSource.setSourceType(sourceType);
            dataSource.setExternalId(externalId);
            dataSource.setEntityType(entityType);
            dataSource.setFetchedAt(java.time.LocalDateTime.now());
        }

        dataSource.setInternalId(internalId);
        dataSource.setStatus(status);
        dataSource.setErrorMessage(errorMessage);

        dataSourceRepository.save(dataSource);
    }

    // ========== Collection Control Methods ==========

    /**
     * Cancel the currently running collection
     */
    public void cancelCollection() {
        log.warn("Collection cancellation requested");
        cancelled = true;
    }

    /**
     * Get the status of the current collection
     */
    public Map<String, Object> getCollectionStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isRunning", currentOperation != null);
        status.put("currentOperation", currentOperation);
        status.put("cancelled", cancelled);
        return status;
    }

    /**
     * Check if a collection is currently running
     */
    public boolean isCollectionRunning() {
        return currentOperation != null;
    }

    /**
     * Publish progress event for SSE broadcasting
     */
    private void publishProgress(CollectionProgressEvent.EventType eventType, Integer year,
                                  String strategy, Integer currentPage, Integer totalPages,
                                  Integer moviesCollected, String movieTitle, String message) {
        try {
            CollectionProgressEvent event = CollectionProgressEvent.builder(this, eventType)
                    .year(year)
                    .strategy(strategy)
                    .currentPage(currentPage)
                    .totalPages(totalPages)
                    .moviesCollected(moviesCollected)
                    .movieTitle(movieTitle)
                    .message(message)
                    .build();
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Failed to publish progress event: {}", e.getMessage());
        }
    }
}
