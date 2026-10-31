package com.flicknames.service.service;

import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.NameStatsDTO;
import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NameService {

    private final PersonRepository personRepository;
    private final MovieRepository movieRepository;
    private final CreditRepository creditRepository;
    private final FirstNameResolver firstNameResolver;

    /**
     * TMDB reports revenue only a couple of weeks after release, so a one-week window was all
     * $0 films and an arbitrary order. Use the films still in theaters that have revenue.
     */
    static final int TRENDING_WINDOW_WEEKS = 6;

    public List<TrendingNameDTO> getTrendingNamesWeekly(int limit) {
        LocalDate now = LocalDate.now();
        LocalDate windowStart = now.minusWeeks(TRENDING_WINDOW_WEEKS);

        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = personRepository.findTrendingNamesByDateRange(windowStart, now, pageable);

        return results.stream()
            .map(this::mapToTrendingNameDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingNameDTO> getTrendingNamesYearly(int year, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = personRepository.findTopNamesByYear(year, pageable);

        return results.stream()
            .map(this::mapToTrendingNameDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingNameDTO> getTrendingNamesCurrentYear(int limit) {
        int currentYear = LocalDate.now().getYear();
        return getTrendingNamesYearly(currentYear, limit);
    }

    public NameStatsDTO getNameStats(String requestedName) {
        List<String> spellings = firstNameResolver.spellingsForPeople(requestedName);
        String firstName = spellings.get(0);
        List<Person> people = personRepository.findByFirstNameIn(spellings);

        if (people.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No people found with first name: " + requestedName);
        }

        // Get all credits for all people with this name
        List<Credit> allCredits = creditRepository.findByPersonFirstNameInWithMovie(spellings);

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
        Map<String, Integer> genderDistribution = people.stream()
            .filter(p -> p.getGender() != null)
            .collect(Collectors.groupingBy(
                Person::getGender,
                Collectors.reducing(0, e -> 1, Integer::sum)
            ));

        // Role distribution
        Map<String, Integer> roleDistribution = allCredits.stream()
            .collect(Collectors.groupingBy(
                Credit::getJob,
                Collectors.reducing(0, e -> 1, Integer::sum)
            ));

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

        // Map people to DTOs
        List<PersonDTO> peopleList = people.stream()
            .map(this::mapPersonToDTO)
            .collect(Collectors.toList());

        return NameStatsDTO.builder()
            .name(firstName)
            .totalBoxOffice(totalBoxOffice)
            .totalMovies(totalMovies)
            .peopleCount(people.size())
            .genderDistribution(genderDistribution)
            .roleDistribution(roleDistribution)
            .people(peopleList)
            .topMovies(topMovies)
            .build();
    }

    public List<PersonDTO> getPeopleByFirstName(String firstName) {
        return personRepository.findByFirstNameIn(firstNameResolver.spellingsForPeople(firstName)).stream()
            .map(this::mapPersonToDTO)
            .collect(Collectors.toList());
    }

    private TrendingNameDTO mapToTrendingNameDTO(Object[] result) {
        String firstName = (String) result[0];
        BigDecimal totalRevenue = (BigDecimal) result[1];
        Long movieCount = (Long) result[2];
        Long peopleCount = (Long) result[3];

        // Determine primary gender (most common)
        String primaryGender = mostCommonGender(personRepository.countGendersByFirstName(firstName));

        // Get top 5 people by revenue
        List<PersonDTO> topPeople = personRepository.findTopPeopleByFirstName(firstName, PageRequest.of(0, 5)).stream()
            .map(this::mapPersonToDTO)
            .collect(Collectors.toList());

        // Get recent movies featuring this name
        List<MovieDTO> recentMovies = creditRepository.findRecentMoviesByPersonFirstName(firstName, PageRequest.of(0, 5)).stream()
            .map(this::mapMovieToDTO)
            .collect(Collectors.toList());

        return TrendingNameDTO.builder()
            .name(firstName)
            .totalRevenue(totalRevenue)
            .movieCount(movieCount.intValue())
            .peopleCount(peopleCount.intValue())
            .primaryGender(primaryGender)
            .topPeople(topPeople)
            .recentMovies(recentMovies)
            .build();
    }

    /** Picks the gender with the highest count from [gender, count] rows. */
    static String mostCommonGender(List<Object[]> genderCounts) {
        return genderCounts.stream()
            .max(Comparator.comparingLong(row -> (Long) row[1]))
            .map(row -> (String) row[0])
            .orElse(null);
    }

    private PersonDTO mapPersonToDTO(Person person) {
        return PersonDTO.builder()
            .id(person.getId())
            .firstName(person.getFirstName())
            .lastName(person.getLastName())
            .fullName(person.getFullName())
            .gender(person.getGender())
            .profilePath(person.getProfilePath())
            .biography(person.getBiography())
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
