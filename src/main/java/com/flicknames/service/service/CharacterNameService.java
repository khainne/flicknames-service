package com.flicknames.service.service;

import com.flicknames.service.dto.CharacterDTO;
import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.NameStatsDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.entity.Character;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.repository.CharacterRepository;
import com.flicknames.service.repository.CreditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterNameService {

    private final CharacterRepository characterRepository;
    private final CreditRepository creditRepository;

    public List<TrendingNameDTO> getTrendingNamesWeekly(int limit) {
        LocalDate now = LocalDate.now();
        LocalDate oneWeekAgo = now.minusWeeks(1);

        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = characterRepository.findTrendingNamesByDateRange(oneWeekAgo, now, pageable);

        return results.stream()
            .map(this::mapToTrendingNameDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingNameDTO> getTrendingNamesYearly(int year, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = characterRepository.findTopNamesByYear(year, pageable);

        return results.stream()
            .map(this::mapToTrendingNameDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingNameDTO> getTrendingNamesCurrentYear(int limit) {
        int currentYear = LocalDate.now().getYear();
        return getTrendingNamesYearly(currentYear, limit);
    }

    public NameStatsDTO getNameStats(String firstName) {
        List<Character> characters = characterRepository.findByFirstName(firstName);

        if (characters.isEmpty()) {
            throw new RuntimeException("No characters found with first name: " + firstName);
        }

        // Get all credits for all characters with this name
        List<Credit> allCredits = characters.stream()
            .flatMap(ch -> creditRepository.findByPersonIdOrderByMovieReleaseDate(ch.getId()).stream())
            .collect(Collectors.toList());

        // Calculate total box office
        BigDecimal totalBoxOffice = allCredits.stream()
            .map(c -> c.getMovie().getRevenue())
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Count distinct movies
        int totalMovies = (int) allCredits.stream()
            .map(c -> c.getMovie().getId())
            .distinct()
            .count();

        // Gender distribution
        Map<String, Integer> genderDistribution = characters.stream()
            .filter(ch -> ch.getGender() != null)
            .collect(Collectors.groupingBy(
                Character::getGender,
                Collectors.reducing(0, e -> 1, Integer::sum)
            ));

        // Role distribution (characters are always CAST, but show movies they're in)
        Map<String, Integer> roleDistribution = new HashMap<>();
        roleDistribution.put("Character Appearances", allCredits.size());

        // Top movies by revenue
        List<MovieDTO> topMovies = allCredits.stream()
            .map(Credit::getMovie)
            .distinct()
            .sorted((m1, m2) -> {
                BigDecimal r1 = m1.getRevenue() != null ? m1.getRevenue() : BigDecimal.ZERO;
                BigDecimal r2 = m2.getRevenue() != null ? m2.getRevenue() : BigDecimal.ZERO;
                return r2.compareTo(r1);
            })
            .limit(10)
            .map(this::mapMovieToDTO)
            .collect(Collectors.toList());

        return NameStatsDTO.builder()
            .name(firstName)
            .totalBoxOffice(totalBoxOffice)
            .totalMovies(totalMovies)
            .peopleCount(characters.size())  // Actually character count
            .genderDistribution(genderDistribution)
            .roleDistribution(roleDistribution)
            .people(null)  // Characters don't map to PersonDTO
            .topMovies(topMovies)
            .build();
    }

    public List<CharacterDTO> getCharactersByFirstName(String firstName) {
        return characterRepository.findByFirstName(firstName).stream()
            .map(this::mapCharacterToDTO)
            .collect(Collectors.toList());
    }

    private TrendingNameDTO mapToTrendingNameDTO(Object[] result) {
        String firstName = (String) result[0];
        BigDecimal totalRevenue = (BigDecimal) result[1];
        Long movieCount = (Long) result[2];
        Long characterCount = (Long) result[3];

        // Get all characters with this first name
        List<Character> characters = characterRepository.findByFirstName(firstName);

        // Determine primary gender (most common)
        String primaryGender = characters.stream()
            .filter(ch -> ch.getGender() != null)
            .collect(Collectors.groupingBy(Character::getGender, Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        // Get recent movies featuring this character name
        List<MovieDTO> recentMovies = characters.stream()
            .flatMap(ch -> creditRepository.findByPersonIdOrderByMovieReleaseDate(ch.getId()).stream())
            .map(Credit::getMovie)
            .distinct()
            .sorted((m1, m2) -> m2.getReleaseDate().compareTo(m1.getReleaseDate()))
            .limit(5)
            .map(this::mapMovieToDTO)
            .collect(Collectors.toList());

        return TrendingNameDTO.builder()
            .name(firstName)
            .totalRevenue(totalRevenue)
            .movieCount(movieCount.intValue())
            .peopleCount(characterCount.intValue())  // Actually character count
            .primaryGender(primaryGender)
            .topPeople(null)  // Characters, not people
            .recentMovies(recentMovies)
            .build();
    }

    private CharacterDTO mapCharacterToDTO(Character character) {
        return CharacterDTO.builder()
            .id(character.getId())
            .firstName(character.getFirstName())
            .lastName(character.getLastName())
            .fullName(character.getFullName())
            .gender(character.getGender())
            .description(character.getDescription())
            .build();
    }

    private MovieDTO mapMovieToDTO(Movie movie) {
        return MovieDTO.builder()
            .id(movie.getId())
            .title(movie.getTitle())
            .releaseDate(movie.getReleaseDate())
            .budget(movie.getBudget())
            .revenue(movie.getRevenue())
            .runtime(movie.getRuntime())
            .overview(movie.getOverview())
            .posterPath(movie.getPosterPath())
            .backdropPath(movie.getBackdropPath())
            .originalLanguage(movie.getOriginalLanguage())
            .voteAverage(movie.getVoteAverage())
            .voteCount(movie.getVoteCount())
            .status(movie.getStatus())
            .build();
    }
}
