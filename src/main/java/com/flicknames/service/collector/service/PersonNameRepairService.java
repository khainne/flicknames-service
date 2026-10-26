package com.flicknames.service.collector.service;

import com.flicknames.service.util.PersonNameSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Re-splits the stored first and last names of people with {@link PersonNameSplitter}. Earlier imports
 * took everything before the last space as the first name ("Samuel L." Jackson, "Abbas Ali" Khan), so
 * those people were missing from their first name's statistics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonNameRepairService {

    private static final int PAGE = 5_000;
    private static final int SAMPLES = 50;

    private final JdbcTemplate jdbc;

    public Map<String, Object> repair(boolean dryRun) {
        long scanned = 0;
        long changed = 0;
        Map<String, Long> oldFirstNames = new HashMap<>();
        List<Map<String, String>> samples = new ArrayList<>();
        long after = 0;
        while (true) {
            List<Object[]> page = jdbc.query(
                "SELECT id, full_name, first_name, last_name FROM people WHERE id > ? ORDER BY id LIMIT " + PAGE,
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)}, after);
            if (page.isEmpty()) {
                break;
            }
            List<Object[]> updates = new ArrayList<>();
            for (Object[] row : page) {
                String fullName = (String) row[1];
                String[] split = PersonNameSplitter.split(fullName);
                if (!split[0].equals(row[2]) || !split[1].equals(row[3])) {
                    updates.add(new Object[] {split[0], split[1], row[0]});
                    oldFirstNames.merge(String.valueOf(row[2]), 1L, Long::sum);
                    if (samples.size() < SAMPLES) {
                        samples.add(Map.of("fullName", String.valueOf(fullName),
                            "old", row[2] + " | " + row[3], "new", split[0] + " | " + split[1]));
                    }
                }
            }
            if (!dryRun && !updates.isEmpty()) {
                jdbc.batchUpdate("UPDATE people SET first_name = ?, last_name = ? WHERE id = ?", updates);
            }
            scanned += page.size();
            changed += updates.size();
            after = (Long) page.get(page.size() - 1)[0];
        }
        List<Map<String, Object>> topOld = oldFirstNames.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(30)
            .map(e -> Map.<String, Object>of("firstName", e.getKey(), "people", e.getValue())).toList();
        log.info("Person name repair (dryRun={}): scanned {}, changed {}", dryRun, scanned, changed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("scanned", scanned);
        result.put("changed", changed);
        result.put("mostChangedOldFirstNames", topOld);
        result.put("samples", samples);
        return result;
    }
}
