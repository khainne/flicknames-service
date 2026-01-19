package com.flicknames.service.collector.imdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.DataSource;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class IMDbImportService {

    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final CharacterRepository characterRepository;
    private final CreditRepository creditRepository;
    private final DataSourceRepository dataSourceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // In-memory caches for batch processing
    private final Map<String, Long> imdbMovieIdCache = new HashMap<>();
    private final Map<String, Long> imdbPersonIdCache = new HashMap<>();
    private final Map<String, Long> imdbCharacterCache = new HashMap<>();

    /**
     * Import movies from title.basics.tsv.gz file
     * Filters for movies only (excludes TV shows, etc.)
     */
    @Transactional
    public void importMovies(Path tsvFilePath, int minYear, int maxYear) throws IOException {
        log.info("Importing movies from {} (years {}-{})", tsvFilePath, minYear, maxYear);

        int totalLines = 0;
        int imported = 0;
        int skipped = 0;

        try (BufferedReader reader = createReader(tsvFilePath)) {
            // Skip header line
            String headerLine = reader.readLine();
            log.debug("Header: {}", headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;

                try {
                    String[] fields = line.split("\t");

                    // Filter: only movies, within year range
                    String titleType = fields[IMDbDataset.TitleBasics.TITLE_TYPE];
                    String startYearStr = fields[IMDbDataset.TitleBasics.START_YEAR];

                    if (!"movie".equals(titleType)) {
                        continue;
                    }

                    if (IMDbDataset.isNull(startYearStr)) {
                        continue;
                    }

                    int year = Integer.parseInt(startYearStr);
                    if (year < minYear || year > maxYear) {
                        continue;
                    }

                    // Check if already imported
                    String tconst = fields[IMDbDataset.TitleBasics.TCONST];
                    if (dataSourceRepository.existsBySourceTypeAndExternalIdAndEntityTypeAndStatus(
                            DataSource.SourceType.IMDB, tconst, DataSource.EntityType.MOVIE,
                            DataSource.FetchStatus.SUCCESS)) {
                        skipped++;
                        continue;
                    }

                    // Import movie
                    Movie movie = importMovie(fields);
                    if (movie != null) {
                        imported++;
                        imdbMovieIdCache.put(tconst, movie.getId());

                        if (imported % 1000 == 0) {
                            log.info("Imported {} movies ({} total lines processed)", imported, totalLines);
                        }
                    }

                } catch (Exception e) {
                    log.warn("Failed to import movie at line {}: {}", totalLines, e.getMessage());
                }
            }
        }

        log.info("Movie import complete: {} imported, {} skipped, {} total lines",
                imported, skipped, totalLines);
    }

    /**
     * Import people from name.basics.tsv.gz file
     */
    @Transactional
    public void importPeople(Path tsvFilePath, Set<String> filterNconsts) throws IOException {
        log.info("Importing {} people from {}", filterNconsts.size(), tsvFilePath);

        int totalLines = 0;
        int imported = 0;

        try (BufferedReader reader = createReader(tsvFilePath)) {
            // Skip header
            reader.readLine();

            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;

                try {
                    String[] fields = line.split("\t");
                    String nconst = fields[IMDbDataset.NameBasics.NCONST];

                    // Filter: only import people referenced in our movies
                    if (!filterNconsts.contains(nconst)) {
                        continue;
                    }

                    // Check if already imported
                    if (dataSourceRepository.existsBySourceTypeAndExternalIdAndEntityTypeAndStatus(
                            DataSource.SourceType.IMDB, nconst, DataSource.EntityType.PERSON,
                            DataSource.FetchStatus.SUCCESS)) {
                        continue;
                    }

                    Person person = importPerson(fields);
                    if (person != null) {
                        imported++;
                        imdbPersonIdCache.put(nconst, person.getId());

                        if (imported % 1000 == 0) {
                            log.info("Imported {} people ({} total lines processed)", imported, totalLines);
                        }
                    }

                } catch (Exception e) {
                    log.warn("Failed to import person at line {}: {}", totalLines, e.getMessage());
                }
            }
        }

        log.info("People import complete: {} imported, {} total lines", imported, totalLines);
    }

    /**
     * Import credits (cast and crew) from title.principals.tsv.gz file
     */
    @Transactional
    public void importCredits(Path tsvFilePath) throws IOException {
        log.info("Importing credits from {}", tsvFilePath);

        int totalLines = 0;
        int imported = 0;

        try (BufferedReader reader = createReader(tsvFilePath)) {
            // Skip header
            reader.readLine();

            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;

                try {
                    String[] fields = line.split("\t", -1); // -1 to keep trailing empty strings

                    String tconst = fields[IMDbDataset.TitlePrincipals.TCONST];
                    String nconst = fields[IMDbDataset.TitlePrincipals.NCONST];

                    // Only import credits for movies/people we have
                    Long movieId = imdbMovieIdCache.get(tconst);
                    Long personId = imdbPersonIdCache.get(nconst);

                    if (movieId == null || personId == null) {
                        continue;
                    }

                    importCredit(fields, movieId, personId);
                    imported++;

                    if (imported % 5000 == 0) {
                        log.info("Imported {} credits ({} total lines processed)", imported, totalLines);
                    }

                } catch (Exception e) {
                    log.warn("Failed to import credit at line {}: {}", totalLines, e.getMessage());
                }
            }
        }

        log.info("Credits import complete: {} imported, {} total lines", imported, totalLines);
    }

    /**
     * Import a single movie from TSV fields
     */
    private Movie importMovie(String[] fields) {
        String tconst = fields[IMDbDataset.TitleBasics.TCONST];
        String title = fields[IMDbDataset.TitleBasics.PRIMARY_TITLE];
        String startYearStr = fields[IMDbDataset.TitleBasics.START_YEAR];
        String runtimeStr = fields[IMDbDataset.TitleBasics.RUNTIME_MINUTES];

        Movie movie = new Movie();
        movie.setImdbId(tconst);
        movie.setTitle(title);

        if (!IMDbDataset.isNull(startYearStr)) {
            int year = Integer.parseInt(startYearStr);
            movie.setReleaseYear(year);
            movie.setReleaseDate(LocalDate.of(year, 1, 1));
        }

        if (!IMDbDataset.isNull(runtimeStr)) {
            movie.setRuntime(Integer.parseInt(runtimeStr));
        }

        movie = movieRepository.save(movie);

        // Record successful import
        recordDataSource(DataSource.SourceType.IMDB, tconst, DataSource.EntityType.MOVIE,
                movie.getId(), DataSource.FetchStatus.SUCCESS, null);

        return movie;
    }

    /**
     * Import a single person from TSV fields
     */
    private Person importPerson(String[] fields) {
        String nconst = fields[IMDbDataset.NameBasics.NCONST];
        String fullName = fields[IMDbDataset.NameBasics.PRIMARY_NAME];

        Person person = new Person();
        person.setImdbId(nconst);
        person.setFullName(fullName);

        // Parse first and last name
        String[] nameParts = parseFullName(fullName);
        person.setFirstName(nameParts[0]);
        person.setLastName(nameParts[1]);

        person = personRepository.save(person);

        // Record successful import
        recordDataSource(DataSource.SourceType.IMDB, nconst, DataSource.EntityType.PERSON,
                person.getId(), DataSource.FetchStatus.SUCCESS, null);

        return person;
    }

    /**
     * Import a single credit from TSV fields
     */
    private void importCredit(String[] fields, Long movieId, Long personId) {
        Movie movie = movieRepository.findById(movieId).orElse(null);
        Person person = personRepository.findById(personId).orElse(null);

        if (movie == null || person == null) {
            return;
        }

        String category = fields[IMDbDataset.TitlePrincipals.CATEGORY];
        String job = fields[IMDbDataset.TitlePrincipals.JOB];
        String charactersJson = fields[IMDbDataset.TitlePrincipals.CHARACTERS];
        String orderingStr = fields[IMDbDataset.TitlePrincipals.ORDERING];

        Credit.RoleType roleType = mapCategoryToRoleType(category);
        String department = mapCategoryToDepartment(category);
        String jobTitle = IMDbDataset.isNull(job) ? category : job;
        Integer order = IMDbDataset.isNull(orderingStr) ? null : Integer.parseInt(orderingStr);

        // Parse character names from JSON array
        com.flicknames.service.entity.Character character = null;
        if (!IMDbDataset.isNull(charactersJson) && roleType == Credit.RoleType.CAST) {
            character = parseAndCreateCharacter(charactersJson);
        }

        // Check if credit already exists
        boolean exists = creditRepository.existsByMovieAndPersonAndRoleTypeAndJob(
                movie, person, roleType, jobTitle);
        if (exists) {
            return;
        }

        Credit credit = new Credit();
        credit.setMovie(movie);
        credit.setPerson(person);
        credit.setCharacter(character);
        credit.setRoleType(roleType);
        credit.setDepartment(department);
        credit.setJob(jobTitle);
        credit.setCastOrder(order);

        creditRepository.save(credit);
    }

    /**
     * Parse character name from IMDb JSON array format: ["Character Name"]
     */
    private com.flicknames.service.entity.Character parseAndCreateCharacter(String charactersJson) {
        try {
            // IMDb uses JSON array format like: ["Tony Stark","Iron Man"]
            JsonNode jsonNode = objectMapper.readTree(charactersJson);
            if (jsonNode.isArray() && jsonNode.size() > 0) {
                String characterName = jsonNode.get(0).asText();

                // Check cache first
                if (imdbCharacterCache.containsKey(characterName)) {
                    return characterRepository.findById(imdbCharacterCache.get(characterName)).orElse(null);
                }

                Optional<com.flicknames.service.entity.Character> existing = characterRepository.findByFullName(characterName);
                if (existing.isPresent()) {
                    imdbCharacterCache.put(characterName, existing.get().getId());
                    return existing.get();
                }

                com.flicknames.service.entity.Character character = new com.flicknames.service.entity.Character();
                character.setFullName(characterName);

                String[] nameParts = parseFullName(characterName);
                character.setFirstName(nameParts[0]);
                character.setLastName(nameParts[1]);

                character = characterRepository.save(character);
                imdbCharacterCache.put(characterName, character.getId());

                return character;
            }
        } catch (Exception e) {
            log.warn("Failed to parse character JSON: {}", charactersJson, e);
        }
        return null;
    }

    private Credit.RoleType mapCategoryToRoleType(String category) {
        return switch (category.toLowerCase()) {
            case "actor", "actress", "self" -> Credit.RoleType.CAST;
            default -> Credit.RoleType.CREW;
        };
    }

    private String mapCategoryToDepartment(String category) {
        return switch (category.toLowerCase()) {
            case "actor", "actress", "self" -> "Acting";
            case "director" -> "Directing";
            case "writer" -> "Writing";
            case "producer" -> "Production";
            case "cinematographer" -> "Camera";
            case "composer" -> "Sound";
            default -> "Crew";
        };
    }

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

    private BufferedReader createReader(Path filePath) throws IOException {
        InputStream inputStream = Files.newInputStream(filePath);

        // Auto-detect gzip compression
        if (filePath.toString().endsWith(".gz")) {
            inputStream = new GZIPInputStream(inputStream);
        }

        return new BufferedReader(new InputStreamReader(inputStream));
    }

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

    /**
     * Build person filter set by loading referenced nconsts from principals file
     */
    public Set<String> extractReferencedPeople(Path principalsFilePath) throws IOException {
        log.info("Extracting referenced people from {}", principalsFilePath);
        Set<String> nconsts = new HashSet<>();

        try (BufferedReader reader = createReader(principalsFilePath)) {
            reader.readLine(); // Skip header

            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t");
                String nconst = fields[IMDbDataset.TitlePrincipals.NCONST];
                nconsts.add(nconst);
            }
        }

        log.info("Extracted {} unique people references", nconsts.size());
        return nconsts;
    }
}
