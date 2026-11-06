package com.flicknames.service.service;

import com.flicknames.service.util.charactername.CharacterNameLexicon;
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
    private final FirstNameResolver firstNameResolver;

    /**
     * Get trending names from BOTH people and characters combined
     * Aggregates by first name across all sources
     */
    public List<TrendingNameDTO> getAllTrendingNamesWeekly(int limit) {
        List<TrendingNameDTO> personNames = nameService.getTrendingNamesWeekly(limit * 2);
        List<TrendingNameDTO> characterNames = characterNameService.getTrendingNamesWeekly(limit * 2);

        return mergeAndAggregate(personNames, characterNames, limit);
    }

    /**
     * Same result as merging fully looked-up person and character lists, but the gender, people and movie
     * lookups (several queries per name) run only for the names that make the final list, not all 4 × limit.
     */
    public List<TrendingNameDTO> getAllTrendingNamesYearly(int year, int limit) {
        List<Object[]> personRows = nameService.topNameRowsByYear(year, limit * 2);
        List<Object[]> characterRows = characterNameService.topNameRowsByYear(year, limit * 2);

        // Rank on the totals alone
        List<TrendingNameDTO> totals = new ArrayList<>();
        for (Object[] row : personRows) totals.add(totalsOnly(row));
        for (Object[] row : characterRows) totals.add(totalsOnly(row));
        List<String> keep = mergeAndAggregate(totals, List.of(), limit).stream()
            .map(n -> CharacterNameLexicon.fold(n.getName()))
            .toList();
        Set<String> wanted = new HashSet<>(keep);

        // Look up details only for the kept names, people before characters as mergeAndAggregate does
        Map<String, List<TrendingNameDTO>> sources = new HashMap<>();
        for (Object[] row : personRows) {
            String key = CharacterNameLexicon.fold((String) row[0]);
            if (wanted.contains(key)) sources.computeIfAbsent(key, k -> new ArrayList<>()).add(nameService.mapToTrendingNameDTO(row));
        }
        for (Object[] row : characterRows) {
            String key = CharacterNameLexicon.fold((String) row[0]);
            if (wanted.contains(key)) sources.computeIfAbsent(key, k -> new ArrayList<>()).add(characterNameService.mapToTrendingNameDTO(row));
        }
        return keep.stream()
            .map(key -> sources.get(key).stream().reduce(this::combine).orElseThrow())
            .toList();
    }

    private static TrendingNameDTO totalsOnly(Object[] row) {
        return TrendingNameDTO.builder()
            .name((String) row[0])
            .totalRevenue((BigDecimal) row[1])
            .movieCount(((Long) row[2]).intValue())
            .peopleCount(((Long) row[3]).intValue())
            .build();
    }

    /** This year's list changes only when new films or revenue are imported, so it's kept for a few minutes. */
    private static final long CURRENT_YEAR_TTL_MS = 10 * 60 * 1000;
    private final Map<String, CachedNames> currentYearCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedNames(List<TrendingNameDTO> names, long at) {}

    public List<TrendingNameDTO> getAllTrendingNamesCurrentYear(int limit) {
        int currentYear = java.time.LocalDate.now().getYear();
        String key = currentYear + ":" + limit;
        long now = System.currentTimeMillis();
        CachedNames hit = currentYearCache.get(key);
        if (hit != null && now - hit.at() < CURRENT_YEAR_TTL_MS) {
            return hit.names();
        }
        List<TrendingNameDTO> names = getAllTrendingNamesYearly(currentYear, limit);
        currentYearCache.put(key, new CachedNames(names, now));
        return names;
    }

    /**
     * Merge person and character names, aggregating by firstName. Spellings that differ only by accents or
     * case (José, Jose) are one name, shown under the spelling with the larger revenue.
     */
    List<TrendingNameDTO> mergeAndAggregate(
            List<TrendingNameDTO> personNames,
            List<TrendingNameDTO> characterNames,
            int limit) {

        Map<String, TrendingNameDTO> merged = new HashMap<>();
        List<TrendingNameDTO> all = new ArrayList<>(personNames);
        all.addAll(characterNames);
        for (TrendingNameDTO name : all) {
            merged.merge(CharacterNameLexicon.fold(name.getName()), name, this::combine);
        }

        // Sort by total revenue and return top N
        return merged.values().stream()
            .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /** Combines two entries for one name: revenue, movie and people/character counts add up. */
    private TrendingNameDTO combine(TrendingNameDTO a, TrendingNameDTO b) {
        TrendingNameDTO larger = a.getTotalRevenue().compareTo(b.getTotalRevenue()) >= 0 ? a : b;
        return TrendingNameDTO.builder()
            .name(larger.getName())
            .totalRevenue(a.getTotalRevenue().add(b.getTotalRevenue()))
            .movieCount(a.getMovieCount() + b.getMovieCount())
            .peopleCount(a.getPeopleCount() + b.getPeopleCount())  // people + characters
            .primaryGender(a.getPrimaryGender() != null ? a.getPrimaryGender() : b.getPrimaryGender())
            .topPeople(a.getTopPeople() != null ? a.getTopPeople() : b.getTopPeople())
            .recentMovies(mergeRecentMovies(a.getRecentMovies(), b.getRecentMovies()))
            .build();
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
        List<Person> people = personRepository.findTopPeopleByFirstName(firstNameResolver.forPeople(firstName), pageable);

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
