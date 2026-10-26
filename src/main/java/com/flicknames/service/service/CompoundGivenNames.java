package com.flicknames.service.service;

import com.flicknames.service.entity.CompoundGivenName;
import com.flicknames.service.entity.CompoundGivenName.Source;
import com.flicknames.service.repository.CompoundGivenNameRepository;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The two-word given names in compound_given_names, held in memory for name splitting. Seeds the table
 * at startup from {@code person-names/compound-given-names.txt}, adding only names it has never held,
 * so names an admin disabled stay disabled.
 */
@Service
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class CompoundGivenNames implements ApplicationRunner {

    static final String SEED_FILE = "person-names/compound-given-names.txt";

    private final CompoundGivenNameRepository repository;

    private volatile Set<String> folded;

    @Override
    public void run(ApplicationArguments args) {
        int added = add(seedNames(), Source.SEED).size();
        if (added > 0) {
            log.info("Seeded {} compound given names", added);
        }
    }

    /** Whether "first second" is a two-word given name. */
    public boolean isCompound(String first, String second) {
        Set<String> names = folded;
        if (names == null) {
            names = reload();
        }
        return names.contains(fold(first + " " + second));
    }

    public List<CompoundGivenName> all() {
        return repository.findAll().stream().sorted(Comparator.comparing(CompoundGivenName::getFoldedName)).toList();
    }

    /** Adds the names it does not hold and re-enables disabled ones; returns those that changed. */
    public synchronized List<CompoundGivenName> add(Collection<String> names, Source source) {
        List<CompoundGivenName> changed = new ArrayList<>();
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
            String[] words = name.split(" ");
            if (words.length != 2) {
                continue;
            }
            Optional<CompoundGivenName> existing = repository.findByFoldedName(fold(name));
            if (existing.isEmpty()) {
                changed.add(repository.save(CompoundGivenName.builder().name(name).foldedName(fold(name))
                    .baseName(words[0]).source(source).build()));
            } else if (source == Source.ADMIN && !existing.get().isEnabled()) {
                existing.get().setEnabled(true);
                changed.add(repository.save(existing.get()));
            }
        }
        reload();
        return changed;
    }

    /** Disables a name; false when it is not held. */
    public synchronized boolean disable(String name) {
        Optional<CompoundGivenName> existing = repository.findByFoldedName(fold(name.trim().replaceAll("\\s+", " ")));
        existing.ifPresent(n -> {
            n.setEnabled(false);
            repository.save(n);
        });
        reload();
        return existing.isPresent();
    }

    private Set<String> reload() {
        Set<String> names = repository.findByEnabledTrue().stream().map(CompoundGivenName::getFoldedName)
            .collect(Collectors.toUnmodifiableSet());
        folded = names;
        return names;
    }

    private static String fold(String s) {
        return CharacterNameLexicon.fold(s);
    }

    static List<String> seedNames() {
        try (InputStream in = CompoundGivenNames.class.getClassLoader().getResourceAsStream(SEED_FILE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + SEED_FILE);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
