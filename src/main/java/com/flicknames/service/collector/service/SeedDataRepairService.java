package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBPersonDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * One-off repair for DataInitializer's demo rows that were seeded into production.
 *
 * - Seed people (biography "Sample biography for ...") carry real TMDB ids 1-11, so the collector
 *   attached those real people's credits to them. They are renamed to the real TMDB person.
 * - Seed movies (TMDB ids 1001-1007 with the seed titles) duplicate real movies and double-count
 *   revenue. They are deleted together with their seed credits.
 * - Seed characters left without any credit afterwards are deleted; ones reused by real credits
 *   (characters are deduplicated by full name) are kept.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeedDataRepairService {

    static final String SEED_BIOGRAPHY_PREFIX = "Sample biography for ";

    /** Matches Person.biography's column length; TMDB biographies are often longer. */
    static final int BIOGRAPHY_MAX_LENGTH = 1000;

    static final Map<Long, String> SEED_MOVIES = Map.of(
        1001L, "La La Land",
        1002L, "Oppenheimer",
        1003L, "Don't Worry Darling",
        1004L, "Poor Things",
        1005L, "Harry Potter and the Deathly Hallows: Part 2",
        1006L, "Deadpool",
        1007L, "The Favourite"
    );

    static final List<String> SEED_CHARACTERS = List.of(
        "Mia Dolan", "Sebastian Wilder", "Jean Tatlock", "Alice Chambers", "Bella Baxter",
        "Felicity Baxter", "Hermione Granger", "Wade Wilson", "Abigail", "Anne"
    );

    private final PersonRepository personRepository;
    private final MovieRepository movieRepository;
    private final ScreenCharacterRepository characterRepository;
    private final CreditRepository creditRepository;
    private final DataCollectorService dataCollectorService;
    private final TMDBClient tmdbClient;

    @Transactional
    public Map<String, Object> repair(boolean dryRun) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dryRun", dryRun);
        report.put("people", repairPeople(dryRun));

        List<Map<String, Object>> movies = new ArrayList<>();
        Set<Long> seedCreditIds = new HashSet<>();
        for (Map.Entry<Long, String> seed : SEED_MOVIES.entrySet()) {
            movieRepository.findByTmdbMovieId(seed.getKey())
                .filter(movie -> seed.getValue().equals(movie.getTitle()))
                .ifPresent(movie -> {
                    List<Credit> credits = creditRepository.findByMovieId(movie.getId());
                    credits.forEach(c -> seedCreditIds.add(c.getId()));
                    movies.add(Map.of("id", movie.getId(), "title", movie.getTitle(), "credits", credits.size()));
                    if (!dryRun) {
                        creditRepository.deleteAll(credits);
                        movie.getCredits().clear();
                        movieRepository.delete(movie);
                    }
                });
        }
        report.put("moviesDeleted", movies);
        if (!dryRun) {
            creditRepository.flush();
        }

        List<Map<String, Object>> characters = new ArrayList<>();
        for (String fullName : SEED_CHARACTERS) {
            characterRepository.findByFullName(fullName).ifPresent(character -> {
                long remaining = creditRepository.findByCharacterId(character.getId()).stream()
                    .filter(c -> !seedCreditIds.contains(c.getId()))
                    .count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", character.getId());
                row.put("fullName", fullName);
                row.put("otherCredits", remaining);
                row.put("action", remaining == 0 ? "delete" : "keep");
                characters.add(row);
                if (!dryRun && remaining == 0) {
                    character.getCredits().clear();
                    characterRepository.delete(character);
                }
            });
        }
        report.put("characters", characters);

        log.info("Seed data repair (dryRun={}): {}", dryRun, report);
        return report;
    }

    private List<Map<String, Object>> repairPeople(boolean dryRun) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Person person : personRepository.findByBiographyStartingWith(SEED_BIOGRAPHY_PREFIX)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", person.getId());
            row.put("tmdbPersonId", person.getTmdbPersonId());
            row.put("seedName", person.getFullName());
            try {
                TMDBPersonDTO tmdb = tmdbClient.getPerson(person.getTmdbPersonId());
                if (tmdb == null || tmdb.getName() == null || tmdb.getName().isBlank()) {
                    row.put("error", "TMDB returned no name");
                } else {
                    row.put("tmdbName", tmdb.getName());
                    if (!dryRun) {
                        String[] parts = dataCollectorService.parseFullName(tmdb.getName());
                        person.setFullName(tmdb.getName());
                        person.setFirstName(parts[0]);
                        person.setLastName(parts[1]);
                        person.setGender(dataCollectorService.mapGender(tmdb.getGender()));
                        person.setBiography(truncate(tmdb.getBiography(), BIOGRAPHY_MAX_LENGTH));
                        person.setProfilePath(tmdb.getProfilePath());
                        // imdb_id is unique: another row (e.g. from an IMDb import) may already have it
                        if (person.getImdbId() == null && tmdb.getImdbId() != null
                                && !personRepository.existsByImdbId(tmdb.getImdbId())) {
                            person.setImdbId(tmdb.getImdbId());
                        }
                    }
                }
            } catch (Exception e) {
                row.put("error", e.getMessage());
            }
            rows.add(row);
        }
        return rows;
    }

    /** Cuts text to at most maxLength characters, at a word boundary where possible, ending in "…". */
    static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String cut = text.substring(0, maxLength - 1);
        int space = cut.lastIndexOf(' ');
        if (space > maxLength / 2) {
            cut = cut.substring(0, space);
        }
        return cut.stripTrailing() + "…";
    }
}
