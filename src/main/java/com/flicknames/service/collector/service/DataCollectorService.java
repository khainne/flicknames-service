package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.ComprehensiveCollectionResult;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.DataSource;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        log.info("Starting comprehensive collection for year {} (US only: {}, max pages: {})",
                year, usOnlyFilter, maxPagesPerStrategy);

        ComprehensiveCollectionResult result = new ComprehensiveCollectionResult();
        result.setYear(year);
        result.setUsOnlyFilter(usOnlyFilter);
        result.setMaxPagesPerStrategy(maxPagesPerStrategy);
        result.setStartTime(LocalDateTime.now());

        // Try multiple sorting strategies to catch different movies
        String[] sortStrategies = {
                "popularity.desc",
                "vote_count.desc",
                "primary_release_date.desc",
                "original_title.asc"
        };

        for (String sortBy : sortStrategies) {
            int moviesInStrategy = collectWithSort(year, sortBy, usOnlyFilter, maxPagesPerStrategy);
            result.addStrategyResult(sortBy, moviesInStrategy);
            log.info("Strategy {} collected {} movies", sortBy, moviesInStrategy);
        }

        result.setEndTime(LocalDateTime.now());
        log.info("Comprehensive collection for year {} completed. Total movies: {}, Duration: {} minutes",
                year, result.getTotalMoviesCollected(), result.getDurationMinutes());

        return result;
    }

    /**
     * Collect movies using a specific sorting strategy
     */
    private int collectWithSort(int year, String sortBy, boolean usOnly, int maxPages) {
        int collected = 0;
        String originCountry = usOnly ? "US" : null;
        Integer minVoteCount = 10; // Filter out very obscure entries

        for (int page = 1; page <= maxPages; page++) {
            TMDBClient.DiscoverMoviesResponse response = tmdbClient.discoverMoviesByYearWithFilters(
                    year, page, sortBy, originCountry, minVoteCount, null
            );

            if (response == null || response.results == null || response.results.isEmpty()) {
                break;
            }

            // Check if we're hitting the 500-page limit
            if (page >= 500 && response.total_pages > 500) {
                log.warn("Hit 500-page limit for year {} with sort {}. Consider segmentation.",
                        year, sortBy);
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

        // Parse first and last name
        String[] nameParts = parseFullName(normalizedName);
        character.setFirstName(nameParts[0]);
        character.setLastName(nameParts[1]);

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
        movie.setRuntime(dto.getRuntime());
    }

    /**
     * Parse full name into first and last name
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
}
