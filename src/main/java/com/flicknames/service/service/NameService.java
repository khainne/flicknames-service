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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<TrendingNameDTO> getTrendingNamesWeekly(int limit) {
        LocalDate now = LocalDate.now();
        LocalDate oneWeekAgo = now.minusWeeks(1);

        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = personRepository.findTrendingNamesByDateRange(oneWeekAgo, now, pageable);

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

    public NameStatsDTO getNameStats(String firstName) {
        List<Person> people = personRepository.findByFirstName(firstName);

        if (people.isEmpty()) {
            throw new RuntimeException("No people found with first name: " + firstName);
        }

        // Get all credits for all people with this name
        List<Credit> allCredits = people.stream()
            .flatMap(p -> creditRepository.findByPersonId(p.getId()).stream())
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
        return personRepository.findByFirstName(firstName).stream()
            .map(this::mapPersonToDTO)
            .collect(Collectors.toList());
    }

    private TrendingNameDTO mapToTrendingNameDTO(Object[] result) {
        String firstName = (String) result[0];
        BigDecimal totalRevenue = (BigDecimal) result[1];
        Long movieCount = (Long) result[2];
        Long peopleCount = (Long) result[3];

        // Get all people with this first name
        List<Person> people = personRepository.findByFirstName(firstName);

        // Determine primary gender (most common)
        String primaryGender = people.stream()
            .filter(p -> p.getGender() != null)
            .collect(Collectors.groupingBy(Person::getGender, Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        // Get top 5 people by revenue
        List<PersonDTO> topPeople = people.stream()
            .limit(5)
            .map(this::mapPersonToDTO)
            .collect(Collectors.toList());

        // Get recent movies featuring this name
        List<MovieDTO> recentMovies = people.stream()
            .flatMap(p -> creditRepository.findByPersonIdOrderByMovieReleaseDate(p.getId()).stream())
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
            .peopleCount(peopleCount.intValue())
            .primaryGender(primaryGender)
            .topPeople(topPeople)
            .recentMovies(recentMovies)
            .build();
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
