package com.flicknames.service.service;

import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Maps a first name from a URL ("emma", "EMMA") to the spelling stored in the database ("Emma"),
 * so name lookups are case-insensitive. An exact match is used as-is, keeping the common path on
 * the first_name index; only a miss falls back to a case-insensitive search.
 */
@Component
@RequiredArgsConstructor
public class FirstNameResolver {

    private static final PageRequest MOST_COMMON = PageRequest.of(0, 1);

    private final PersonRepository personRepository;
    private final ScreenCharacterRepository characterRepository;

    public String forPeople(String name) {
        return resolve(name, personRepository::existsByFirstName, personRepository::findFirstNameSpellings);
    }

    public String forCharacters(String name) {
        return resolve(name, characterRepository::existsByFirstName, characterRepository::findFirstNameSpellings);
    }

    private String resolve(String name, Predicate<String> exists,
                           BiFunction<String, PageRequest, List<String>> spellings) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || exists.test(trimmed)) {
            return trimmed;
        }
        List<String> matches = spellings.apply(trimmed, MOST_COMMON);
        return matches.isEmpty() ? trimmed : matches.get(0);
    }
}
