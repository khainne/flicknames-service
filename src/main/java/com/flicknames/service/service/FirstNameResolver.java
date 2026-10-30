package com.flicknames.service.service;

import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Maps a first name from a URL ("emma", "EMMA") to the spelling stored in the database ("Emma"),
 * so name lookups are case-insensitive. An exact match is used as-is, keeping the common path on
 * the first_name index; only a miss falls back to a case-insensitive search.
 *
 * <p>A name page covers every spelling that differs only by accents or case (José, Jose, Josè): the
 * {@code spellingsFor*} methods return them from the given-name catalog, most common first.
 */
@Component
@RequiredArgsConstructor
public class FirstNameResolver {

    private static final PageRequest MOST_COMMON = PageRequest.of(0, 1);

    private final PersonRepository personRepository;
    private final ScreenCharacterRepository characterRepository;
    private final JdbcTemplate jdbc;

    public String forPeople(String name) {
        return resolve(name, personRepository::existsByFirstName, personRepository::findFirstNameSpellings);
    }

    public String forCharacters(String name) {
        return resolve(name, characterRepository::existsByFirstName, characterRepository::findFirstNameSpellings);
    }

    /** The spellings people bear that fold like this name, most common first; the first is the page's name. */
    public List<String> spellingsForPeople(String name) {
        return spellings(forPeople(name), "people_count");
    }

    /** The spellings characters bear that fold like this name, most common first. */
    public List<String> spellingsForCharacters(String name) {
        return spellings(forCharacters(name), "character_count");
    }

    private List<String> spellings(String resolved, String countColumn) {
        List<String> found = new ArrayList<>();
        if (!resolved.isEmpty()) {
            try {
                found.addAll(jdbc.queryForList("SELECT spelling FROM given_names WHERE folded = ? AND " + countColumn
                    + " > 0 ORDER BY " + countColumn + " DESC, spelling", String.class, CharacterNameLexicon.fold(resolved)));
            } catch (DataAccessException e) {
                // the catalog is optional: without it a page covers the stored spelling only
            }
        }
        if (!found.contains(resolved)) {
            found.add(resolved);   // a spelling newer than the nightly catalog refresh
        }
        return found;
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
