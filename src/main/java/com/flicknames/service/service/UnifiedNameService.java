package com.flicknames.service.service;

import com.flicknames.service.dto.PersonCardDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.SsaName;
import com.flicknames.service.entity.SsaNameYearlyStat;
import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.SsaNameRepository;
import com.flicknames.service.research.dto.FullNameDetailsDTO;
import com.flicknames.service.research.dto.NameResearchDTO;
import com.flicknames.service.research.service.NameResearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private final NameResearchService nameResearchService;
    private final SsaNameRepository ssaNameRepository;
    private final PersonRepository personRepository;

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

    /**
     * Get full name details including research, SSA stats, and namesakes
     */
    public Optional<FullNameDetailsDTO> getFullNameDetails(String name) {
        // Get research data (only if approved)
        Optional<NameResearchDTO> research = nameResearchService.getApprovedResearch(name);

        // Get SSA statistics
        List<SsaName> ssaNames = ssaNameRepository.findByNameIgnoreCase(name);
        FullNameDetailsDTO.SsaStatsDTO ssaStats = null;

        if (!ssaNames.isEmpty()) {
            // Get the most common gender version
            SsaName primarySsaName = ssaNames.stream()
                .max(Comparator.comparingLong(ssaName ->
                    ssaName.getYearlyStats().stream().mapToLong(stat -> stat.getCount()).sum()))
                .orElse(ssaNames.get(0));

            ssaStats = buildSsaStats(primarySsaName);
        }

        // Get famous people with this name (namesakes)
        List<PersonCardDTO> namesakes = getTopNamesakes(name, 10);

        // Only return data if we have at least one of: research, SSA stats, or namesakes
        if (research.isEmpty() && ssaStats == null && namesakes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FullNameDetailsDTO.builder()
            .name(name)
            .research(research.orElse(null))
            .ssaStats(ssaStats)
            .namesakes(namesakes)
            .build());
    }

    /**
     * Build SSA statistics from SsaName entity
     */
    private FullNameDetailsDTO.SsaStatsDTO buildSsaStats(SsaName ssaName) {
        List<SsaNameYearlyStat> stats = ssaName.getYearlyStats();
        if (stats.isEmpty()) {
            return null;
        }

        // Calculate totals and find peak
        long totalCount = 0;
        SsaNameYearlyStat peakStat = stats.get(0);
        Integer firstYear = null;
        Integer lastYear = null;

        for (SsaNameYearlyStat stat : stats) {
            totalCount += stat.getCount();

            if (peakStat == null || stat.getCount() > peakStat.getCount()) {
                peakStat = stat;
            }

            if (firstYear == null || stat.getYear() < firstYear) {
                firstYear = stat.getYear();
            }
            if (lastYear == null || stat.getYear() > lastYear) {
                lastYear = stat.getYear();
            }
        }

        // Get recent years (last 10 years)
        List<FullNameDetailsDTO.YearlyStatDTO> recentYears = stats.stream()
            .sorted(Comparator.comparingInt(SsaNameYearlyStat::getYear).reversed())
            .limit(10)
            .map(stat -> FullNameDetailsDTO.YearlyStatDTO.builder()
                .year(stat.getYear())
                .count(stat.getCount().longValue())
                .rank(stat.getRank())
                .build())
            .collect(Collectors.toList());

        return FullNameDetailsDTO.SsaStatsDTO.builder()
            .sex(ssaName.getSex())
            .totalCount(totalCount)
            .peakYear(peakStat.getYear())
            .peakCount(peakStat.getCount().longValue())
            .firstYear(firstYear)
            .lastYear(lastYear)
            .recentYears(recentYears)
            .build();
    }

    /**
     * Get top famous people with this first name
     */
    private List<PersonCardDTO> getTopNamesakes(String firstName, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Person> people = personRepository.findTopPeopleByFirstName(firstName, pageable);

        return people.stream()
            .map(person -> PersonCardDTO.builder()
                .id(person.getId())
                .firstName(person.getFirstName())
                .lastName(person.getLastName())
                .fullName(person.getFullName())
                .profilePath(person.getProfilePath())
                .gender(person.getGender())
                .build())
            .collect(Collectors.toList());
    }
}
