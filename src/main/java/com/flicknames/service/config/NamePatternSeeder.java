package com.flicknames.service.config;

import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.repository.NamePatternRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the database with default name patterns on startup.
 * Only adds patterns that don't already exist.
 */
@Component
@Order(1) // Run before DataInitializer
@RequiredArgsConstructor
@Slf4j
public class NamePatternSeeder implements CommandLineRunner {

    private final NamePatternRepository patternRepository;

    // Default title patterns
    private static final List<String> DEFAULT_TITLES = List.of(
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
        "coach", "master"
    );

    // Default role descriptor patterns
    private static final List<String> DEFAULT_ROLE_DESCRIPTORS = List.of(
        // Age/appearance descriptors
        "old", "young", "elderly", "little", "big", "tall", "short", "fat", "thin",
        "pretty", "ugly", "beautiful", "handsome", "blind", "deaf",
        // Gender descriptors
        "man", "woman", "boy", "girl", "guy", "lady", "gentleman", "kid", "child",
        "teen", "teenager", "baby", "infant", "toddler",
        // Job/role words
        "guard", "soldier", "worker", "driver", "waiter", "waitress", "bartender",
        "clerk", "teller", "customer", "patient", "victim", "suspect", "witness",
        "prisoner", "inmate", "hostage", "thug", "henchman", "goon", "minion",
        "servant", "maid", "butler", "cook", "chef", "baker",
        "reporter", "journalist", "anchor", "host", "announcer",
        "teacher", "student", "pupil", "principal",
        "cop", "policeman", "policewoman", "fireman", "firefighter", "paramedic",
        "pilot", "stewardess", "attendant",
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
        "parent", "son", "daughter", "husband", "wife",
        "uncle", "aunt", "cousin", "grandmother", "grandfather",
        "friend", "boyfriend", "girlfriend", "lover", "ex",
        // Action-based
        "killer", "murderer", "thief", "robber", "burglar", "kidnapper",
        "dealer", "buyer", "seller", "vendor"
    );

    // Default adjective prefix patterns
    private static final List<String> DEFAULT_ADJECTIVE_PREFIXES = List.of(
        "angry", "happy", "sad", "scared", "nervous", "drunk", "crazy", "mad",
        "suspicious", "mysterious", "shady", "friendly", "mean", "nice", "rude",
        "first", "second", "third", "lead", "main", "head", "chief",
        "street", "bar", "club", "store", "bank", "hospital", "hotel", "office",
        "security", "delivery", "pizza", "taxi", "uber", "bus", "train",
        "rich", "poor", "homeless", "wealthy",
        "night", "day", "coffee", "shop"
    );

    @Override
    public void run(String... args) {
        long existingCount = patternRepository.count();

        if (existingCount > 0) {
            log.info("Name patterns already exist ({} patterns), skipping seed", existingCount);
            return;
        }

        log.info("Seeding default name patterns...");

        int titleCount = seedPatterns(PatternType.TITLE, DEFAULT_TITLES);
        int roleCount = seedPatterns(PatternType.ROLE_DESCRIPTOR, DEFAULT_ROLE_DESCRIPTORS);
        int adjectiveCount = seedPatterns(PatternType.ADJECTIVE_PREFIX, DEFAULT_ADJECTIVE_PREFIXES);

        log.info("Seeded {} titles, {} role descriptors, {} adjective prefixes",
            titleCount, roleCount, adjectiveCount);
    }

    private int seedPatterns(PatternType type, List<String> values) {
        int count = 0;
        for (String value : values) {
            String normalized = value.toLowerCase().trim();
            if (!patternRepository.existsByPatternTypeAndPatternValue(type, normalized)) {
                NamePattern pattern = NamePattern.builder()
                    .patternType(type)
                    .patternValue(normalized)
                    .note("Default pattern from initial seed")
                    .build();
                patternRepository.save(pattern);
                count++;
            }
        }
        return count;
    }
}
