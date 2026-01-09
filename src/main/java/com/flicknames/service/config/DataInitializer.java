package com.flicknames.service.config;

import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final PersonRepository personRepository;
    private final MovieRepository movieRepository;
    private final CreditRepository creditRepository;

    @Override
    public void run(String... args) {
        if (personRepository.count() > 0) {
            log.info("Database already initialized, skipping seed data");
            return;
        }

        log.info("Initializing database with seed data...");

        // Create sample people
        Person emma = createPerson("Emma", "Stone", 1L, "Female");
        Person ryan = createPerson("Ryan", "Gosling", 2L, "Male");
        Person olivia = createPerson("Olivia", "Wilde", 3L, "Female");
        Person damien = createPerson("Damien", "Chazelle", 4L, "Male");
        Person christopher = createPerson("Christopher", "Nolan", 5L, "Male");
        Person florence = createPerson("Florence", "Pugh", 6L, "Female");

        personRepository.save(emma);
        personRepository.save(ryan);
        personRepository.save(olivia);
        personRepository.save(damien);
        personRepository.save(christopher);
        personRepository.save(florence);

        // Create sample movies
        Movie lalaland = createMovie(
            "La La Land",
            LocalDate.of(2016, 12, 9),
            1001L,
            new BigDecimal("30000000"),
            new BigDecimal("472000000"),
            128,
            "A jazz pianist falls for an aspiring actress in Los Angeles."
        );

        Movie oppenheimer = createMovie(
            "Oppenheimer",
            LocalDate.of(2023, 7, 21),
            1002L,
            new BigDecimal("100000000"),
            new BigDecimal("952000000"),
            180,
            "The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb."
        );

        Movie dontworrydarling = createMovie(
            "Don't Worry Darling",
            LocalDate.of(2022, 9, 23),
            1003L,
            new BigDecimal("35000000"),
            new BigDecimal("87000000"),
            123,
            "A 1950s housewife living in an experimental company town begins to worry that his company may be hiding disturbing secrets."
        );

        Movie poorThings = createMovie(
            "Poor Things",
            LocalDate.of(2023, 12, 8),
            1004L,
            new BigDecimal("35000000"),
            new BigDecimal("117000000"),
            141,
            "The incredible tale of Bella Baxter, a young woman brought back to life by a brilliant scientist."
        );

        movieRepository.save(lalaland);
        movieRepository.save(oppenheimer);
        movieRepository.save(dontworrydarling);
        movieRepository.save(poorThings);

        // Create credits for La La Land
        createCredit(emma, lalaland, Credit.RoleType.CAST, "Acting", "Actor", "Mia", 1);
        createCredit(ryan, lalaland, Credit.RoleType.CAST, "Acting", "Actor", "Sebastian", 2);
        createCredit(damien, lalaland, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(damien, lalaland, Credit.RoleType.CREW, "Writing", "Writer", null, null);

        // Create credits for Oppenheimer
        createCredit(florence, oppenheimer, Credit.RoleType.CAST, "Acting", "Actor", "Jean Tatlock", 3);
        createCredit(christopher, oppenheimer, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(christopher, oppenheimer, Credit.RoleType.CREW, "Writing", "Writer", null, null);
        createCredit(christopher, oppenheimer, Credit.RoleType.CREW, "Production", "Producer", null, null);

        // Create credits for Don't Worry Darling
        createCredit(florence, dontworrydarling, Credit.RoleType.CAST, "Acting", "Actor", "Alice Chambers", 1);
        createCredit(olivia, dontworrydarling, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(olivia, dontworrydarling, Credit.RoleType.CREW, "Production", "Producer", null, null);

        // Create credits for Poor Things
        createCredit(emma, poorThings, Credit.RoleType.CAST, "Acting", "Actor", "Bella Baxter", 1);

        log.info("Database initialization completed successfully!");
    }

    private Person createPerson(String firstName, String lastName, Long tmdbId, String gender) {
        return Person.builder()
            .firstName(firstName)
            .lastName(lastName)
            .fullName(firstName + " " + lastName)
            .tmdbPersonId(tmdbId)
            .gender(gender)
            .biography("Sample biography for " + firstName + " " + lastName)
            .build();
    }

    private Movie createMovie(String title, LocalDate releaseDate, Long tmdbId,
                             BigDecimal budget, BigDecimal revenue, Integer runtime, String overview) {
        return Movie.builder()
            .title(title)
            .releaseDate(releaseDate)
            .tmdbMovieId(tmdbId)
            .budget(budget)
            .revenue(revenue)
            .runtime(runtime)
            .overview(overview)
            .originalLanguage("en")
            .status("Released")
            .voteAverage(8.0)
            .voteCount(5000)
            .build();
    }

    private void createCredit(Person person, Movie movie, Credit.RoleType roleType,
                            String department, String job, String character, Integer order) {
        Credit credit = Credit.builder()
            .person(person)
            .movie(movie)
            .roleType(roleType)
            .department(department)
            .job(job)
            .character(character)
            .order(order)
            .build();
        creditRepository.save(credit);
    }
}
