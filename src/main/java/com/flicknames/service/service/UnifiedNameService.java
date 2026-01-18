package com.flicknames.service.service;

import com.flicknames.service.dto.TrendingNameDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnifiedNameService {

    private final NameService nameService;
    private final CharacterNameService characterNameService;

    /**
     * Get trending names from BOTH people and characters combined
     * Aggregates by first name across all sources
     */
    public List<TrendingNameDTO> getAllTrendingNamesWeekly(int limit) {
        List<TrendingNameDTO> personNames = nameService.getTrendingNamesWeekly(limit * 2);
        List<TrendingNameDTO> characterNames = characterNameService.getTrendingNamesWeekly(limit * 2);

        return mergeAndAggregate(personNames, characterNames, limit);
    }

    public List<TrendingNameDTO> getAllTrendingNamesYearly(int year, int limit) {
        List<TrendingNameDTO> personNames = nameService.getTrendingNamesYearly(year, limit * 2);
        List<TrendingNameDTO> characterNames = characterNameService.getTrendingNamesYearly(year, limit * 2);

        return mergeAndAggregate(personNames, characterNames, limit);
    }

    public List<TrendingNameDTO> getAllTrendingNamesCurrentYear(int limit) {
        int currentYear = java.time.LocalDate.now().getYear();
        return getAllTrendingNamesYearly(currentYear, limit);
    }

    /**
     * Merge person and character names, aggregating by firstName
     */
    private List<TrendingNameDTO> mergeAndAggregate(
            List<TrendingNameDTO> personNames,
            List<TrendingNameDTO> characterNames,
            int limit) {

        Map<String, TrendingNameDTO> merged = new HashMap<>();

        // Process person names
        for (TrendingNameDTO personName : personNames) {
            merged.put(personName.getName(), personName);
        }

        // Process character names - merge or add
        for (TrendingNameDTO characterName : characterNames) {
            String firstName = characterName.getName();

            if (merged.containsKey(firstName)) {
                // Merge: combine revenue and movie counts
                TrendingNameDTO existing = merged.get(firstName);

                BigDecimal combinedRevenue = existing.getTotalRevenue().add(characterName.getTotalRevenue());
                int combinedMovieCount = existing.getMovieCount() + characterName.getMovieCount();
                int combinedCount = existing.getPeopleCount() + characterName.getPeopleCount();

                // Create new merged DTO
                TrendingNameDTO mergedDTO = TrendingNameDTO.builder()
                    .name(firstName)
                    .totalRevenue(combinedRevenue)
                    .movieCount(combinedMovieCount)
                    .peopleCount(combinedCount)  // people + characters
                    .primaryGender(existing.getPrimaryGender())  // Use person's gender as primary
                    .topPeople(existing.getTopPeople())
                    .recentMovies(mergeRecentMovies(existing.getRecentMovies(), characterName.getRecentMovies()))
                    .build();

                merged.put(firstName, mergedDTO);
            } else {
                // Add new character name
                merged.put(firstName, characterName);
            }
        }

        // Sort by total revenue and return top N
        return merged.values().stream()
            .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Merge two lists of movies, remove duplicates, and return most recent
     */
    private List<com.flicknames.service.dto.MovieDTO> mergeRecentMovies(
            List<com.flicknames.service.dto.MovieDTO> list1,
            List<com.flicknames.service.dto.MovieDTO> list2) {

        if (list1 == null && list2 == null) return Collections.emptyList();
        if (list1 == null) return list2;
        if (list2 == null) return list1;

        Set<Long> seen = new HashSet<>();
        List<com.flicknames.service.dto.MovieDTO> merged = new ArrayList<>();

        for (com.flicknames.service.dto.MovieDTO movie : list1) {
            if (seen.add(movie.getId())) {
                merged.add(movie);
            }
        }

        for (com.flicknames.service.dto.MovieDTO movie : list2) {
            if (seen.add(movie.getId())) {
                merged.add(movie);
            }
        }

        // Sort by release date, most recent first
        return merged.stream()
            .sorted((a, b) -> b.getReleaseDate().compareTo(a.getReleaseDate()))
            .limit(5)
            .collect(Collectors.toList());
    }
}
