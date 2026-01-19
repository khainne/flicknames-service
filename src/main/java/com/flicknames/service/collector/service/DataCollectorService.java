package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.entity.*;
import com.flicknames.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CharacterRepository characterRepository;
    private final CreditRepository creditRepository;

    /**
     * Collect a single movie and all its credits from TMDB
     */
    @Transactional
    public Movie collectMovie(Long tmdbMovieId) {
        log.info("Collecting movie with TMDB ID: {}", tmdbMovieId);

        // Fetch movie details
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
                movie.getTitle(), movie.getReleaseYear(), movie.getCredits().size());

        return movie;
    }

    /**
     * Collect popular movies
     */
    @Transactional
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
     */
    @Transactional
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
     * Process credits (cast and crew) for a movie
     */
    private void processCredits(Movie movie, TMDBCreditsDTO creditsDTO) {
        Map<Long, Person> personCache = new HashMap<>();
        Map<String, Character> characterCache = new HashMap<>();

        // Process cast
        if (creditsDTO.getCast() != null) {
            for (int i = 0; i < creditsDTO.getCast().size(); i++) {
                TMDBCreditsDTO.CastMember castMember = creditsDTO.getCast().get(i);

                try {
                    Person person = getOrCreatePerson(castMember.getId(), castMember.getName(),
                            castMember.getGender(), personCache);
                    Character character = getOrCreateCharacter(castMember.getCharacter(), characterCache);

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
    private Character getOrCreateCharacter(String characterName, Map<String, Character> cache) {
        if (characterName == null || characterName.isBlank()) {
            return null;
        }

        String normalizedName = characterName.trim();
        if (cache.containsKey(normalizedName)) {
            return cache.get(normalizedName);
        }

        Optional<Character> existing = characterRepository.findByFullName(normalizedName);
        if (existing.isPresent()) {
            cache.put(normalizedName, existing.get());
            return existing.get();
        }

        Character character = new Character();
        character.setFullName(normalizedName);

        // Parse first and last name
        String[] nameParts = parseFullName(normalizedName);
        character.setFirstName(nameParts[0]);
        character.setLastName(nameParts[1]);

        character = characterRepository.save(character);
        cache.put(normalizedName, character);

        return character;
    }

    /**
     * Create a credit linking person, character, and movie
     */
    private void createCredit(Movie movie, Person person, Character character,
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
        credit.setCastOrder(order);

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

        if (dto.getReleaseDate() != null) {
            movie.setReleaseYear(dto.getReleaseDate().getYear());
        }

        movie.setBoxOffice(dto.getRevenue());
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
}
