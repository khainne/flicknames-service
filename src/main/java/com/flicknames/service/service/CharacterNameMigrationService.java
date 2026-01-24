package com.flicknames.service.service;

import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.ScreenCharacterRepository;
import com.flicknames.service.util.CharacterNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for migrating existing character data to use the new name parsing logic.
 * This re-processes all characters to properly classify their name types and extract
 * valid first names where possible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterNameMigrationService {

    private final ScreenCharacterRepository characterRepository;
    private final CharacterNameParser characterNameParser;

    /**
     * Migrate all existing characters to use the new name parsing logic.
     *
     * @return Statistics about the migration
     */
    @Transactional
    public Map<String, Object> migrateAllCharacters() {
        log.info("Starting character name migration...");

        List<ScreenCharacter> allCharacters = characterRepository.findAll();

        int total = allCharacters.size();
        int updated = 0;
        Map<ScreenCharacter.NameType, Integer> typeCounts = new HashMap<>();

        for (ScreenCharacter.NameType type : ScreenCharacter.NameType.values()) {
            typeCounts.put(type, 0);
        }

        for (ScreenCharacter character : allCharacters) {
            CharacterNameParser.ParseResult result = characterNameParser.parse(character.getFullName());

            // Check if anything changed
            boolean changed = !equalsSafe(character.getFirstName(), result.getFirstName())
                || !equalsSafe(character.getLastName(), result.getLastName())
                || character.getNameType() != result.getNameType();

            if (changed) {
                character.setFirstName(result.getFirstName());
                character.setLastName(result.getLastName());
                character.setNameType(result.getNameType());
                updated++;
            }

            typeCounts.merge(result.getNameType(), 1, Integer::sum);
        }

        // Save all changes
        characterRepository.saveAll(allCharacters);

        log.info("Character name migration completed. Total: {}, Updated: {}", total, updated);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCharacters", total);
        stats.put("updatedCharacters", updated);
        stats.put("nameTypeCounts", typeCounts);

        return stats;
    }

    /**
     * Preview the migration without making changes.
     * Shows what would happen if migration is run.
     *
     * @param limit Maximum number of examples to show
     * @return Preview of changes that would be made
     */
    public Map<String, Object> previewMigration(int limit) {
        log.info("Previewing character name migration...");

        List<ScreenCharacter> allCharacters = characterRepository.findAll();

        int total = allCharacters.size();
        int wouldChange = 0;
        Map<ScreenCharacter.NameType, Integer> currentTypeCounts = new HashMap<>();
        Map<ScreenCharacter.NameType, Integer> newTypeCounts = new HashMap<>();

        for (ScreenCharacter.NameType type : ScreenCharacter.NameType.values()) {
            currentTypeCounts.put(type, 0);
            newTypeCounts.put(type, 0);
        }

        // Collect examples of changes
        Map<String, Object> examples = new HashMap<>();
        examples.put("titleSurnameExamples", new java.util.ArrayList<Map<String, String>>());
        examples.put("roleDescriptionExamples", new java.util.ArrayList<Map<String, String>>());
        examples.put("numberedRoleExamples", new java.util.ArrayList<Map<String, String>>());
        examples.put("correctedFirstNameExamples", new java.util.ArrayList<Map<String, String>>());

        for (ScreenCharacter character : allCharacters) {
            ScreenCharacter.NameType currentType = character.getNameType();
            if (currentType == null) {
                currentType = ScreenCharacter.NameType.UNKNOWN;
            }
            currentTypeCounts.merge(currentType, 1, Integer::sum);

            CharacterNameParser.ParseResult result = characterNameParser.parse(character.getFullName());
            newTypeCounts.merge(result.getNameType(), 1, Integer::sum);

            boolean changed = !equalsSafe(character.getFirstName(), result.getFirstName())
                || !equalsSafe(character.getLastName(), result.getLastName())
                || character.getNameType() != result.getNameType();

            if (changed) {
                wouldChange++;

                // Add example if under limit
                addExample(examples, character, result, limit);
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCharacters", total);
        stats.put("wouldChange", wouldChange);
        stats.put("currentNameTypeCounts", currentTypeCounts);
        stats.put("newNameTypeCounts", newTypeCounts);
        stats.put("examples", examples);

        return stats;
    }

    @SuppressWarnings("unchecked")
    private void addExample(Map<String, Object> examples, ScreenCharacter character,
                            CharacterNameParser.ParseResult result, int limit) {
        Map<String, String> example = new HashMap<>();
        example.put("fullName", character.getFullName());
        example.put("oldFirstName", character.getFirstName());
        example.put("newFirstName", result.getFirstName());
        example.put("oldLastName", character.getLastName());
        example.put("newLastName", result.getLastName());
        example.put("newNameType", result.getNameType().toString());

        String listKey = switch (result.getNameType()) {
            case TITLE_SURNAME -> "titleSurnameExamples";
            case ROLE_DESCRIPTION -> "roleDescriptionExamples";
            case NUMBERED_ROLE -> "numberedRoleExamples";
            default -> "correctedFirstNameExamples";
        };

        java.util.List<Map<String, String>> list =
            (java.util.List<Map<String, String>>) examples.get(listKey);

        if (list.size() < limit) {
            list.add(example);
        }
    }

    private boolean equalsSafe(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
