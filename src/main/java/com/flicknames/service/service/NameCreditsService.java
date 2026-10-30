package com.flicknames.service.service;

import com.flicknames.service.dto.CharacterWithMovieDTO;
import com.flicknames.service.dto.MovieCardDTO;
import com.flicknames.service.dto.PersonCardDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.CreditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The roles behind a name page's closing credits: who with the name played what, and which characters carry it. */
@Service
@RequiredArgsConstructor
public class NameCreditsService {

    /** Rows read per result wanted, so duplicate roles can be dropped and still fill the list */
    private static final int OVERSCAN = 4;

    private final CreditRepository credits;
    private final FirstNameResolver firstNames;

    /** One credited role, the person who played it and the film. */
    public record CastCredit(PersonCardDTO actor, String character, Integer castOrder, MovieCardDTO movie) {}

    /** People with the name in a film's cast, one role per person per film, biggest films first. */
    @Transactional(readOnly = true)
    public List<CastCredit> cast(String name, int limit) {
        List<Credit> rows = credits.findCastByPersonFirstNames(firstNames.spellingsForPeople(name),
            Credit.RoleType.CAST, PageRequest.of(0, limit * OVERSCAN));
        Set<String> seen = new HashSet<>();
        List<CastCredit> out = new ArrayList<>();
        for (Credit c : rows) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(c.getPerson().getId() + ":" + c.getMovie().getId())) {
                out.add(new CastCredit(person(c.getPerson()),
                    c.getCharacter() == null ? null : c.getCharacter().getFullName(),
                    c.getOrder(), movie(c.getMovie())));
            }
        }
        return out;
    }

    /** Characters with the name, each in the biggest film it appears in, biggest films first. */
    @Transactional(readOnly = true)
    public List<CharacterWithMovieDTO> appearances(String name, int limit) {
        List<Credit> rows = credits.findByCharacterFirstNames(firstNames.spellingsForCharacters(name),
            PageRequest.of(0, limit * OVERSCAN));
        Set<Long> seen = new HashSet<>();
        List<CharacterWithMovieDTO> out = new ArrayList<>();
        for (Credit c : rows) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(c.getCharacter().getId())) {
                out.add(CharacterWithMovieDTO.builder()
                    .characterId(c.getCharacter().getId())
                    .firstName(c.getCharacter().getFirstName())
                    .fullName(c.getCharacter().getFullName())
                    .gender(c.getCharacter().getGender())
                    .movie(movie(c.getMovie()))
                    .actor(person(c.getPerson()))
                    .castOrder(c.getOrder())
                    .build());
            }
        }
        return out;
    }

    private static PersonCardDTO person(Person p) {
        return PersonCardDTO.builder()
            .id(p.getId())
            .firstName(p.getFirstName())
            .lastName(p.getLastName())
            .fullName(p.getFullName())
            .profilePath(p.getProfilePath())
            .gender(p.getGender())
            .build();
    }

    private static MovieCardDTO movie(Movie m) {
        return MovieCardDTO.builder()
            .id(m.getId())
            .title(m.getTitle())
            .releaseDate(m.getReleaseDate())
            .releaseYear(m.getReleaseDate() == null ? null : m.getReleaseDate().getYear())
            .posterPath(m.getPosterPath())
            .backdropPath(m.getBackdropPath())
            .voteAverage(m.getVoteAverage())
            .voteCount(m.getVoteCount())
            .revenue(m.getRevenue())
            .runtime(m.getRuntime())
            .status(m.getStatus())
            .build();
    }
}
