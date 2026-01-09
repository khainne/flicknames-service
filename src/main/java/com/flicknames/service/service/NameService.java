package com.flicknames.service.service;

import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.TrendingPersonDTO;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NameService {

    private final PersonRepository personRepository;
    private final MovieRepository movieRepository;
    private final CreditRepository creditRepository;

    public List<TrendingPersonDTO> getTrendingNamesWeekly(int limit) {
        LocalDate now = LocalDate.now();
        LocalDate oneWeekAgo = now.minusWeeks(1);

        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = personRepository.findTrendingPeopleByDateRange(oneWeekAgo, now, pageable);

        return results.stream()
            .map(this::mapToTrendingPersonDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingPersonDTO> getTrendingNamesYearly(int year, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = personRepository.findTopPeopleByYear(year, pageable);

        return results.stream()
            .map(this::mapToTrendingPersonDTO)
            .collect(Collectors.toList());
    }

    public List<TrendingPersonDTO> getTrendingNamesCurrentYear(int limit) {
        int currentYear = LocalDate.now().getYear();
        return getTrendingNamesYearly(currentYear, limit);
    }

    public List<MovieDTO> getMoviesForPerson(Long personId) {
        List<Credit> credits = creditRepository.findByPersonIdOrderByMovieReleaseDate(personId);

        return credits.stream()
            .map(Credit::getMovie)
            .distinct()
            .map(this::mapMovieToDTO)
            .collect(Collectors.toList());
    }

    private TrendingPersonDTO mapToTrendingPersonDTO(Object[] result) {
        Person person = (Person) result[0];
        BigDecimal totalRevenue = (BigDecimal) result[1];

        // Get recent movies for this person
        List<Credit> recentCredits = creditRepository.findByPersonIdOrderByMovieReleaseDate(person.getId());
        int movieCount = (int) recentCredits.stream()
            .map(c -> c.getMovie().getId())
            .distinct()
            .count();

        List<MovieDTO> recentMovies = recentCredits.stream()
            .map(Credit::getMovie)
            .distinct()
            .limit(5)
            .map(this::mapMovieToDTO)
            .collect(Collectors.toList());

        return TrendingPersonDTO.builder()
            .id(person.getId())
            .firstName(person.getFirstName())
            .lastName(person.getLastName())
            .fullName(person.getFullName())
            .gender(person.getGender())
            .profilePath(person.getProfilePath())
            .totalRevenue(totalRevenue)
            .movieCount(movieCount)
            .recentMovies(recentMovies)
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
