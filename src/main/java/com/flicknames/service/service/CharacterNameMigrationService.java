package com.flicknames.service.service;

import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.ScreenCharacterRepository;
import com.flicknames.service.util.CharacterNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for migrating existing character data to use the new name parsing logic.
 * This re-processes all characters to properly classify their name types and extract
 * valid first names where possible.
 *
 * Uses pagination to avoid loading all characters into memory at once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterNameMigrationService {

    private final ScreenCharacterRepository characterRepository;
    private final CharacterNameParser characterNameParser;

    private static final int BATCH_SIZE = 1000; // Process 1000 characters at a time

    /**
     * Migrate all existing characters to use the new name parsing logic.
     * Skips characters that have been manually verified.
     * Processes in batches to avoid memory issues.
     * Each batch is saved in its own transaction to avoid timeout.
     *
     * @return Statistics about the migration
     */
    public Map<String, Object> migrateAllCharacters() {
        log.info("Starting character name migration with batch size {}...", BATCH_SIZE);

        long totalCount = characterRepository.count();
        int updated = 0;
        int skippedManuallyVerified = 0;
        int skippedUnchanged = 0;
        Map<ScreenCharacter.NameType, Integer> typeCounts = new HashMap<>();

        for (ScreenCharacter.NameType type : ScreenCharacter.NameType.values()) {
            typeCounts.put(type, 0);
        }

        int pageNumber = 0;
        Page<ScreenCharacter> page;

        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
            page = characterRepository.findAll(pageable);

            log.info("Processing batch {}/{} ({} characters)...",
                pageNumber + 1, page.getTotalPages(), page.getNumberOfElements());

            // Process this batch in a separate transaction
            BatchResult batchResult = processBatch(page.getContent());
            updated += batchResult.updated;
            skippedManuallyVerified += batchResult.skippedManuallyVerified;
            skippedUnchanged += batchResult.skippedUnchanged;

            // Merge type counts
            for (Map.Entry<ScreenCharacter.NameType, Integer> entry : batchResult.typeCounts.entrySet()) {
                typeCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }

            pageNumber++;
        } while (page.hasNext());

        log.info("Character name migration completed. Total: {}, Updated: {}, Skipped (verified): {}, Skipped (unchanged): {}",
            totalCount, updated, skippedManuallyVerified, skippedUnchanged);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCharacters", totalCount);
        stats.put("updatedCharacters", updated);
        stats.put("skippedManuallyVerified", skippedManuallyVerified);
        stats.put("skippedUnchanged", skippedUnchanged);
        stats.put("nameTypeCounts", typeCounts);

        return stats;
    }

    /**
     * Process a single batch of characters in a transaction.
     */
    @Transactional
    protected BatchResult processBatch(List<ScreenCharacter> characters) {
        int updated = 0;
        int skippedManuallyVerified = 0;
        int skippedUnchanged = 0;
        Map<ScreenCharacter.NameType, Integer> typeCounts = new HashMap<>();
        List<ScreenCharacter> charactersToSave = new ArrayList<>();

        for (ScreenCharacter character : characters) {
            // Skip manually verified characters
            if (character.isManuallyVerified()) {
                skippedManuallyVerified++;
                typeCounts.merge(character.getNameType(), 1, Integer::sum);
                continue;
            }

            CharacterNameParser.ParseResult result = characterNameParser.parse(character.getFullName());

            // Check if anything changed
            boolean changed = !equalsSafe(character.getFirstName(), result.getFirstName())
                || !equalsSafe(character.getLastName(), result.getLastName())
                || character.getNameType() != result.getNameType();

            if (changed) {
                character.setFirstName(result.getFirstName());
                character.setLastName(result.getLastName());
                character.setNameType(result.getNameType());
                charactersToSave.add(character);
                updated++;
            } else {
                skippedUnchanged++;
            }

            typeCounts.merge(result.getNameType(), 1, Integer::sum);
        }

        // Save this batch
        if (!charactersToSave.isEmpty()) {
            characterRepository.saveAll(charactersToSave);
            log.info("Saved {} updated characters in batch", charactersToSave.size());
        }

        return new BatchResult(updated, skippedManuallyVerified, skippedUnchanged, typeCounts);
    }

    /**
     * Result of processing a single batch.
     */
    private static class BatchResult {
        final int updated;
        final int skippedManuallyVerified;
        final int skippedUnchanged;
        final Map<ScreenCharacter.NameType, Integer> typeCounts;

        BatchResult(int updated, int skippedManuallyVerified, int skippedUnchanged,
                   Map<ScreenCharacter.NameType, Integer> typeCounts) {
            this.updated = updated;
            this.skippedManuallyVerified = skippedManuallyVerified;
            this.skippedUnchanged = skippedUnchanged;
            this.typeCounts = typeCounts;
        }
    }

    /**
     * Preview the migration without making changes.
     * Shows what would happen if migration is run.
     * Uses pagination to avoid loading all characters at once.
     *
     * @param limit Maximum number of examples to show per category
     * @return Preview of changes that would be made
     */
    public Map<String, Object> previewMigration(int limit) {
        log.info("Previewing character name migration...");

        long totalCount = characterRepository.count();
        int wouldChange = 0;
        int manuallyVerified = 0;
        Map<ScreenCharacter.NameType, Integer> currentTypeCounts = new HashMap<>();
        Map<ScreenCharacter.NameType, Integer> newTypeCounts = new HashMap<>();

        for (ScreenCharacter.NameType type : ScreenCharacter.NameType.values()) {
            currentTypeCounts.put(type, 0);
            newTypeCounts.put(type, 0);
        }

        // Collect examples of changes
        Map<String, Object> examples = new HashMap<>();
        examples.put("titleSurnameExamples", new ArrayList<Map<String, String>>());
        examples.put("roleDescriptionExamples", new ArrayList<Map<String, String>>());
        examples.put("numberedRoleExamples", new ArrayList<Map<String, String>>());
        examples.put("correctedFirstNameExamples", new ArrayList<Map<String, String>>());

        int pageNumber = 0;
        Page<ScreenCharacter> page;
        boolean collectingExamples = true;

        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
            page = characterRepository.findAll(pageable);

            log.info("Previewing batch {}/{} ({} characters)...",
                pageNumber + 1, page.getTotalPages(), page.getNumberOfElements());

            for (ScreenCharacter character : page.getContent()) {
                ScreenCharacter.NameType currentType = character.getNameType();
                if (currentType == null) {
                    currentType = ScreenCharacter.NameType.UNKNOWN;
                }
                currentTypeCounts.merge(currentType, 1, Integer::sum);

                // Count manually verified (will be skipped)
                if (character.isManuallyVerified()) {
                    manuallyVerified++;
                    newTypeCounts.merge(currentType, 1, Integer::sum);
                    continue;
                }

                CharacterNameParser.ParseResult result = characterNameParser.parse(character.getFullName());
                newTypeCounts.merge(result.getNameType(), 1, Integer::sum);

                boolean changed = !equalsSafe(character.getFirstName(), result.getFirstName())
                    || !equalsSafe(character.getLastName(), result.getLastName())
                    || character.getNameType() != result.getNameType();

                if (changed) {
                    wouldChange++;

                    // Add example if still collecting and under limit
                    if (collectingExamples) {
                        addExample(examples, character, result, limit);

                        // Stop collecting examples once all categories are full
                        collectingExamples = !allExamplesFull(examples, limit);
                    }
                }
            }

            pageNumber++;

            // Can stop early if we have all examples and counts (but continue for accurate stats)
        } while (page.hasNext());

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCharacters", totalCount);
        stats.put("wouldChange", wouldChange);
        stats.put("manuallyVerifiedWillSkip", manuallyVerified);
        stats.put("currentNameTypeCounts", currentTypeCounts);
        stats.put("newNameTypeCounts", newTypeCounts);
        stats.put("examples", examples);

        log.info("Preview complete: {} characters would change out of {}", wouldChange, totalCount);

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

        List<Map<String, String>> list =
            (List<Map<String, String>>) examples.get(listKey);

        if (list.size() < limit) {
            list.add(example);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean allExamplesFull(Map<String, Object> examples, int limit) {
        for (Object value : examples.values()) {
            List<Map<String, String>> list = (List<Map<String, String>>) value;
            if (list.size() < limit) {
                return false;
            }
        }
        return true;
    }

    private boolean equalsSafe(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
