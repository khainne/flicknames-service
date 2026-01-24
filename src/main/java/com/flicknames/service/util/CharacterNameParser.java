package com.flicknames.service.util;

import com.flicknames.service.entity.ScreenCharacter.NameType;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility for parsing and classifying character names to extract valid first names.
 * Handles various name patterns including standard names, titles, role descriptions, etc.
 */
@Component
public class CharacterNameParser {

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

    // Titles and honorifics that precede surnames (case-insensitive matching)
    private static final Set<String> TITLES = Set.of(
        // Military ranks
        "private", "corporal", "sergeant", "sgt", "staff sergeant", "master sergeant",
        "lieutenant", "lt", "captain", "cpt", "capt", "major", "maj", "colonel", "col",
        "general", "gen", "admiral", "commander", "cmd", "commodore", "ensign",
        // Law enforcement
        "officer", "detective", "det", "inspector", "sheriff", "deputy", "constable",
        "agent", "special agent", "chief", "commissioner", "marshal",
        // Medical
        "doctor", "dr", "nurse", "surgeon", "physician",
        // Academic
        "professor", "prof", "dean",
        // Religious
        "father", "mother", "sister", "brother", "reverend", "rev", "pastor", "bishop",
        "cardinal", "pope", "rabbi", "imam",
        // Nobility/Royal
        "king", "queen", "prince", "princess", "duke", "duchess", "earl", "count",
        "countess", "baron", "baroness", "lord", "lady", "sir", "dame", "knight",
        // Professional/Formal
        "mr", "mrs", "ms", "miss", "mister", "madam", "madame", "mme",
        "judge", "justice", "senator", "congressman", "congresswoman", "mayor",
        "governor", "president", "ambassador", "consul",
        // Other common titles
        "coach", "master", "captain"
    );

    // Role descriptors that indicate non-personal names
    private static final Set<String> ROLE_DESCRIPTORS = Set.of(
        // Age/appearance descriptors
        "old", "young", "elderly", "little", "big", "tall", "short", "fat", "thin",
        "pretty", "ugly", "beautiful", "handsome", "blind", "deaf",
        // Gender descriptors
        "man", "woman", "boy", "girl", "guy", "lady", "gentleman", "kid", "child",
        "teen", "teenager", "baby", "infant", "toddler",
        // Job/role words (when appearing as full descriptor)
        "guard", "soldier", "worker", "driver", "waiter", "waitress", "bartender",
        "clerk", "teller", "customer", "patient", "victim", "suspect", "witness",
        "prisoner", "inmate", "hostage", "thug", "henchman", "goon", "minion",
        "servant", "maid", "butler", "cook", "chef", "baker",
        "reporter", "journalist", "anchor", "host", "announcer",
        "teacher", "student", "pupil", "principal",
        "cop", "policeman", "policewoman", "fireman", "firefighter", "paramedic",
        "pilot", "stewardess", "flight attendant",
        "secretary", "receptionist", "assistant", "executive", "boss", "manager",
        "lawyer", "attorney", "prosecutor", "defendant", "juror",
        "scientist", "researcher", "technician", "engineer",
        "actor", "actress", "singer", "dancer", "musician", "artist",
        "priest", "nun", "monk",
        "zombie", "vampire", "ghost", "monster", "alien", "creature", "robot",
        // Location-based
        "neighbor", "bystander", "passerby", "stranger", "visitor", "guest",
        "tourist", "traveler", "hitchhiker",
        // Relationship-based
        "mother", "father", "parent", "son", "daughter", "husband", "wife",
        "brother", "sister", "uncle", "aunt", "cousin", "grandmother", "grandfather",
        "friend", "boyfriend", "girlfriend", "lover", "ex",
        // Action-based
        "killer", "murderer", "thief", "robber", "burglar", "kidnapper",
        "dealer", "buyer", "seller", "vendor"
    );

    // Common adjective prefixes that typically precede role descriptors
    private static final Set<String> ADJECTIVE_PREFIXES = Set.of(
        "angry", "happy", "sad", "scared", "nervous", "drunk", "crazy", "mad",
        "suspicious", "mysterious", "shady", "friendly", "mean", "nice", "rude",
        "first", "second", "third", "lead", "main", "head", "chief",
        "street", "bar", "club", "store", "bank", "hospital", "hotel", "office",
        "security", "delivery", "pizza", "taxi", "uber", "bus", "train",
        "rich", "poor", "homeless", "wealthy"
    );

    // Pattern for numbered roles like "#1", "#2", "(1)", "(2)", "1", "2" at end
    private static final Pattern NUMBERED_PATTERN = Pattern.compile(
        ".*[#(]?\\d+[)]?$|.*\\s\\d+$"
    );

    // Pattern for "Voice" suffix (animated characters)
    private static final Pattern VOICE_PATTERN = Pattern.compile(
        ".*\\s*\\(voice\\)$|.*\\s*-\\s*voice$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern for parenthetical notes like "(uncredited)", "(archive footage)"
    private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile(
        "\\s*\\([^)]+\\)\\s*$"
    );

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
            return ROLE_DESCRIPTORS.contains(words[0]);
        }

        // Two words: check if it's "Adjective + Role" pattern (e.g., "Old Woman", "Angry Customer")
        if (words.length == 2) {
            String first = words[0];
            String second = words[1];

            // "Old Woman", "Young Man", etc.
            if ((ADJECTIVE_PREFIXES.contains(first) || ROLE_DESCRIPTORS.contains(first))
                && ROLE_DESCRIPTORS.contains(second)) {
                return true;
            }

            // "Security Guard", "Bank Teller", etc.
            if (ADJECTIVE_PREFIXES.contains(first) && ROLE_DESCRIPTORS.contains(second)) {
                return true;
            }
        }

        // Three or more words: check common patterns
        if (words.length >= 2) {
            String lastWord = words[words.length - 1];
            String secondLastWord = words[words.length - 2];

            // Ends with role descriptor (e.g., "Coffee Shop Customer", "Night Club Bouncer")
            if (ROLE_DESCRIPTORS.contains(lastWord)) {
                // Check if preceding words are all modifiers
                boolean allModifiers = true;
                for (int i = 0; i < words.length - 1; i++) {
                    if (!ADJECTIVE_PREFIXES.contains(words[i]) && !ROLE_DESCRIPTORS.contains(words[i])) {
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

        for (String title : TITLES) {
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

        for (String title : TITLES) {
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
