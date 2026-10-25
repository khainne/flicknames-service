package com.flicknames.service.util.charactername;

import com.flicknames.service.config.NamePatternSeeder;
import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.repository.NamePatternRepository;
import com.flicknames.service.util.CharacterNameParser;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds the current (v1) parser with the default patterns that NamePatternSeeder would seed, without a database. */
final class V1Baseline {

    private V1Baseline() {
    }

    static CharacterNameParser withDefaultPatterns() {
        Map<PatternType, List<String>> patterns = new EnumMap<>(PatternType.class);
        for (PatternType t : PatternType.values()) {
            patterns.put(t, new ArrayList<>());
        }
        NamePatternRepository seederRepo = mock(NamePatternRepository.class);
        when(seederRepo.count()).thenReturn(0L);
        when(seederRepo.save(any(NamePattern.class))).thenAnswer(inv -> {
            NamePattern p = inv.getArgument(0);
            List<String> values = patterns.get(p.getPatternType());
            if (!values.contains(p.getPatternValue())) {
                values.add(p.getPatternValue());
            }
            return p;
        });
        new NamePatternSeeder(seederRepo).run();
        return withPatterns(patterns);
    }

    static CharacterNameParser withPatterns(Map<PatternType, List<String>> patterns) {
        NamePatternRepository repo = mock(NamePatternRepository.class);
        for (PatternType t : PatternType.values()) {
            when(repo.findPatternValuesByType(t)).thenReturn(patterns.getOrDefault(t, List.of()));
        }
        CharacterNameParser parser = new CharacterNameParser(repo);
        parser.refreshPatterns();
        return parser;
    }
}
