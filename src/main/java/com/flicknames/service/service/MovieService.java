package com.flicknames.service.service;

import com.flicknames.service.dto.CreditDTO;
import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.MovieWithCreditsDTO;
import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieService {

    private final MovieRepository movieRepository;
    private final CreditRepository creditRepository;

    public MovieDTO getMovieById(Long id) {
        Movie movie = movieRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Movie not found with id: " + id));
        return mapToDTO(movie);
    }

    public MovieWithCreditsDTO getMovieWithCredits(Long id) {
        Movie movie = movieRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Movie not found with id: " + id));

        List<Credit> cast = creditRepository.findCastByMovieId(id);
        List<Credit> crew = creditRepository.findCrewByMovieId(id);

        return MovieWithCreditsDTO.builder()
            .movie(mapToDTO(movie))
            .cast(cast.stream().map(this::mapCreditToDTO).collect(Collectors.toList()))
            .crew(crew.stream().map(this::mapCreditToDTO).collect(Collectors.toList()))
            .build();
    }

    public Page<MovieDTO> searchMovies(String title, Pageable pageable) {
        return movieRepository.findByTitleContainingIgnoreCase(title, pageable)
            .map(this::mapToDTO);
    }

    public List<MovieDTO> getCurrentBoxOffice(Pageable pageable) {
        LocalDate now = LocalDate.now();
        LocalDate threeWeeksAgo = now.minusWeeks(3);

        return movieRepository.findMoviesInTheaters(threeWeeksAgo, now, pageable)
            .stream()
            .map(this::mapToDTO)
            .collect(Collectors.toList());
    }

    public List<MovieDTO> getTopMoviesByYear(int year, Pageable pageable) {
        return movieRepository.findTopMoviesByYear(year, pageable)
            .stream()
            .map(this::mapToDTO)
            .collect(Collectors.toList());
    }

    public List<MovieDTO> getRecentReleases(Pageable pageable) {
        LocalDate now = LocalDate.now();
        LocalDate threeMonthsAgo = now.minusMonths(3);

        return movieRepository.findRecentReleases(now, threeMonthsAgo, pageable)
            .stream()
            .map(this::mapToDTO)
            .collect(Collectors.toList());
    }

    private MovieDTO mapToDTO(Movie movie) {
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

    private CreditDTO mapCreditToDTO(Credit credit) {
        return CreditDTO.builder()
            .id(credit.getId())
            .roleType(credit.getRoleType().name())
            .department(credit.getDepartment())
            .job(credit.getJob())
            .character(credit.getCharacter() != null ? credit.getCharacter().getFullName() : null)
            .order(credit.getOrder())
            .person(PersonDTO.builder()
                .id(credit.getPerson().getId())
                .firstName(credit.getPerson().getFirstName())
                .lastName(credit.getPerson().getLastName())
                .fullName(credit.getPerson().getFullName())
                .profilePath(credit.getPerson().getProfilePath())
                .build())
            .build();
    }
}
