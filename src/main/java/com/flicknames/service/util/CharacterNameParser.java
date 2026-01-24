package com.flicknames.service.util;

import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.repository.NamePatternRepository;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility for parsing and classifying character names to extract valid first names.
 * Patterns are loaded from the database for dynamic updates without code changes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CharacterNameParser {

    private final NamePatternRepository patternRepository;

    // Cached pattern sets - refreshed on demand
    private volatile Set<String> titles = new HashSet<>();
    private volatile Set<String> roleDescriptors = new HashSet<>();
    private volatile Set<String> adjectivePrefixes = new HashSet<>();

    // Pattern for numbered roles like "#1", "#2", "(1)", "(2)", "1", "2" at end
    private static final Pattern NUMBERED_PATTERN = Pattern.compile(
        ".*[#(]?\\d+[)]?$|.*\\s\\d+$"
    );

    // Pattern for parenthetical notes like "(uncredited)", "(archive footage)", "(voice)"
    private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile(
        "\\s*\\([^)]+\\)\\s*$"
    );

    /**
     * Result of parsing a character name
     */
    @Data
    @Builder
    public static class ParseResult {
        private final String firstName;
        private final String lastName;
        private final NameType nameType;

        public boolean hasValidFirstName() {
            return firstName != null && !firstName.isBlank();
        }
    }

    /**
     * Load patterns from database on startup.
     */
    @PostConstruct
    public void init() {
        refreshPatterns();
    }

    /**
     * Reload all patterns from the database.
     * Call this after adding new patterns to pick them up immediately.
     */
    public void refreshPatterns() {
        log.info("Refreshing character name patterns from database...");

        this.titles = new HashSet<>(patternRepository.findPatternValuesByType(PatternType.TITLE));
        this.roleDescriptors = new HashSet<>(patternRepository.findPatternValuesByType(PatternType.ROLE_DESCRIPTOR));
        this.adjectivePrefixes = new HashSet<>(patternRepository.findPatternValuesByType(PatternType.ADJECTIVE_PREFIX));

        log.info("Loaded {} titles, {} role descriptors, {} adjective prefixes",
            titles.size(), roleDescriptors.size(), adjectivePrefixes.size());
    }

    /**
     * Get current pattern counts for diagnostics.
     */
    public PatternStats getPatternStats() {
        return PatternStats.builder()
            .titleCount(titles.size())
            .roleDescriptorCount(roleDescriptors.size())
            .adjectivePrefixCount(adjectivePrefixes.size())
            .build();
    }

    @Data
    @Builder
    public static class PatternStats {
        private final int titleCount;
        private final int roleDescriptorCount;
        private final int adjectivePrefixCount;
    }

    /**
     * Parse a character name and classify it, extracting a valid first name if possible.
     *
     * @param fullName The full character name from TMDB/IMDb
     * @return ParseResult containing firstName, lastName, and nameType
     */
    public ParseResult parse(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return ParseResult.builder()
                .firstName(null)
                .lastName(null)
                .nameType(NameType.UNKNOWN)
                .build();
        }

        // Clean the name - remove parenthetical notes
        String cleanedName = cleanName(fullName.trim());

        if (cleanedName.isBlank()) {
            return ParseResult.builder()
                .firstName(null)
                .lastName(null)
                .nameType(NameType.UNKNOWN)
                .build();
        }

        // Check for numbered roles first
        if (isNumberedRole(cleanedName)) {
            return ParseResult.builder()
                .firstName(null)
                .lastName(null)
                .nameType(NameType.NUMBERED_ROLE)
                .build();
        }

        // Check if it's a role description (e.g., "Security Guard", "Old Woman")
        if (isRoleDescription(cleanedName)) {
            return ParseResult.builder()
                .firstName(null)
                .lastName(null)
                .nameType(NameType.ROLE_DESCRIPTION)
                .build();
        }

        // Check if it starts with a title (e.g., "Officer Daniels", "Dr. Smith")
        if (startsWithTitle(cleanedName)) {
            return ParseResult.builder()
                .firstName(null)
                .lastName(extractSurnameAfterTitle(cleanedName))
                .nameType(NameType.TITLE_SURNAME)
                .build();
        }

        // Handle single-word names
        if (!cleanedName.contains(" ")) {
            return ParseResult.builder()
                .firstName(cleanedName)
                .lastName(null)
                .nameType(NameType.SINGLE_NAME)
                .build();
        }

        // Standard name parsing - first word is first name, rest could be last name
        return parseStandardName(cleanedName);
    }

    /**
     * Clean a name by removing parenthetical notes and normalizing whitespace.
     */
    private String cleanName(String name) {
        // Remove (voice), (uncredited), etc.
        String cleaned = PARENTHETICAL_PATTERN.matcher(name).replaceAll("");
        // Normalize whitespace
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    /**
     * Check if the name represents a numbered/generic role.
     */
    private boolean isNumberedRole(String name) {
        return NUMBERED_PATTERN.matcher(name).matches();
    }

    /**
     * Check if the name is a role description rather than a personal name.
     */
    private boolean isRoleDescription(String name) {
        String lower = name.toLowerCase();
        String[] words = lower.split("\\s+");

        if (words.length == 0) {
            return false;
        }

        // Single word that's a role descriptor
        if (words.length == 1) {
            return roleDescriptors.contains(words[0]);
        }

        // Two words: check if it's "Adjective + Role" pattern (e.g., "Old Woman", "Angry Customer")
        if (words.length == 2) {
            String first = words[0];
            String second = words[1];

            // "Old Woman", "Young Man", etc.
            if ((adjectivePrefixes.contains(first) || roleDescriptors.contains(first))
                && roleDescriptors.contains(second)) {
                return true;
            }

            // "Security Guard", "Bank Teller", etc.
            if (adjectivePrefixes.contains(first) && roleDescriptors.contains(second)) {
                return true;
            }
        }

        // Three or more words: check common patterns
        if (words.length >= 2) {
            String lastWord = words[words.length - 1];

            // Ends with role descriptor (e.g., "Coffee Shop Customer", "Night Club Bouncer")
            if (roleDescriptors.contains(lastWord)) {
                // Check if preceding words are all modifiers
                boolean allModifiers = true;
                for (int i = 0; i < words.length - 1; i++) {
                    if (!adjectivePrefixes.contains(words[i]) && !roleDescriptors.contains(words[i])) {
                        allModifiers = false;
                        break;
                    }
                }
                if (allModifiers) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if the name starts with a title or honorific.
     */
    private boolean startsWithTitle(String name) {
        String lower = name.toLowerCase();

        for (String title : titles) {
            // Match "Title" or "Title." followed by space
            if (lower.startsWith(title + " ") || lower.startsWith(title + ". ")) {
                return true;
            }
            // Match exactly (e.g., just "Captain" with nothing after)
            if (lower.equals(title) || lower.equals(title + ".")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract the surname portion after a title.
     */
    private String extractSurnameAfterTitle(String name) {
        String lower = name.toLowerCase();

        for (String title : titles) {
            if (lower.startsWith(title + " ")) {
                return name.substring(title.length() + 1).trim();
            }
            if (lower.startsWith(title + ". ")) {
                return name.substring(title.length() + 2).trim();
            }
        }

        return null;
    }

    /**
     * Parse a standard name (first name + optional last name).
     */
    private ParseResult parseStandardName(String name) {
        String[] parts = name.split("\\s+");

        if (parts.length == 1) {
            return ParseResult.builder()
                .firstName(parts[0])
                .lastName(null)
                .nameType(NameType.SINGLE_NAME)
                .build();
        }

        // First word is the first name
        String firstName = parts[0];

        // Everything after the first word is the last name
        String lastName = name.substring(firstName.length()).trim();

        return ParseResult.builder()
            .firstName(firstName)
            .lastName(lastName)
            .nameType(NameType.STANDARD)
            .build();
    }
}
