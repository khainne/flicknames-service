package com.flicknames.service.service;

import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.repository.NamePatternRepository;
import com.flicknames.service.util.CharacterNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing name patterns used by the character name parser.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NamePatternService {

    private final NamePatternRepository patternRepository;
    private final CharacterNameParser characterNameParser;

    /**
     * Add a new pattern to the database.
     *
     * @param type The pattern type
     * @param value The pattern value (will be lowercased)
     * @param note Optional note about why this was added
     * @param exampleName Optional example character name that triggered this
     * @return The created pattern, or empty if it already exists
     */
    @Transactional
    public Optional<NamePattern> addPattern(PatternType type, String value, String note, String exampleName) {
        String normalizedValue = value.toLowerCase().trim();

        if (patternRepository.existsByPatternTypeAndPatternValue(type, normalizedValue)) {
            log.info("Pattern already exists: {} = {}", type, normalizedValue);
            return Optional.empty();
        }

        NamePattern pattern = NamePattern.builder()
            .patternType(type)
            .patternValue(normalizedValue)
            .note(note)
            .exampleName(exampleName)
            .build();

        pattern = patternRepository.save(pattern);
        log.info("Added new pattern: {} = {} (example: {})", type, normalizedValue, exampleName);

        // Refresh the parser cache
        characterNameParser.refreshPatterns();

        return Optional.of(pattern);
    }

    /**
     * Add a title pattern.
     */
    @Transactional
    public Optional<NamePattern> addTitle(String value, String exampleName) {
        return addPattern(PatternType.TITLE, value,
            "Title/honorific that precedes surnames", exampleName);
    }

    /**
     * Add a role descriptor pattern.
     */
    @Transactional
    public Optional<NamePattern> addRoleDescriptor(String value, String exampleName) {
        return addPattern(PatternType.ROLE_DESCRIPTOR, value,
            "Role descriptor indicating non-personal name", exampleName);
    }

    /**
     * Add an adjective prefix pattern.
     */
    @Transactional
    public Optional<NamePattern> addAdjectivePrefix(String value, String exampleName) {
        return addPattern(PatternType.ADJECTIVE_PREFIX, value,
            "Adjective prefix used with role descriptors", exampleName);
    }

    /**
     * Remove a pattern.
     */
    @Transactional
    public boolean removePattern(PatternType type, String value) {
        String normalizedValue = value.toLowerCase().trim();

        Optional<NamePattern> existing = patternRepository.findByPatternTypeAndPatternValue(type, normalizedValue);
        if (existing.isPresent()) {
            patternRepository.delete(existing.get());
            log.info("Removed pattern: {} = {}", type, normalizedValue);
            characterNameParser.refreshPatterns();
            return true;
        }
        return false;
    }

    /**
     * Get all patterns of a specific type.
     */
    public List<NamePattern> getPatternsByType(PatternType type) {
        return patternRepository.findByPatternType(type);
    }

    /**
     * Get all patterns grouped by type.
     */
    public Map<PatternType, List<NamePattern>> getAllPatternsGrouped() {
        Map<PatternType, List<NamePattern>> result = new HashMap<>();
        for (PatternType type : PatternType.values()) {
            result.put(type, patternRepository.findByPatternType(type));
        }
        return result;
    }

    /**
     * Get pattern statistics.
     */
    public Map<String, Object> getPatternStats() {
        Map<String, Object> stats = new HashMap<>();

        CharacterNameParser.PatternStats parserStats = characterNameParser.getPatternStats();
        stats.put("cachedTitles", parserStats.getTitleCount());
        stats.put("cachedRoleDescriptors", parserStats.getRoleDescriptorCount());
        stats.put("cachedAdjectivePrefixes", parserStats.getAdjectivePrefixCount());

        stats.put("dbTitles", patternRepository.findByPatternType(PatternType.TITLE).size());
        stats.put("dbRoleDescriptors", patternRepository.findByPatternType(PatternType.ROLE_DESCRIPTOR).size());
        stats.put("dbAdjectivePrefixes", patternRepository.findByPatternType(PatternType.ADJECTIVE_PREFIX).size());

        return stats;
    }

    /**
     * Refresh the parser's cached patterns from the database.
     */
    public void refreshCache() {
        characterNameParser.refreshPatterns();
    }

    /**
     * Test how a character name would be parsed with current patterns.
     */
    public Map<String, Object> testParse(String characterName) {
        CharacterNameParser.ParseResult result = characterNameParser.parse(characterName);

        Map<String, Object> response = new HashMap<>();
        response.put("input", characterName);
        response.put("firstName", result.getFirstName());
        response.put("lastName", result.getLastName());
        response.put("nameType", result.getNameType().toString());
        response.put("hasValidFirstName", result.hasValidFirstName());

        return response;
    }
}
