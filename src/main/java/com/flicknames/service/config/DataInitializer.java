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

        // Create sample people - Multiple people with same first names to demonstrate aggregation
        Person emmaStone = createPerson("Emma", "Stone", 1L, "Female");
        Person emmaWatson = createPerson("Emma", "Watson", 7L, "Female");
        Person emmaThompson = createPerson("Emma", "Thompson", 8L, "Female");

        Person ryanGosling = createPerson("Ryan", "Gosling", 2L, "Male");
        Person ryanReynolds = createPerson("Ryan", "Reynolds", 9L, "Male");

        Person oliviaWilde = createPerson("Olivia", "Wilde", 3L, "Female");
        Person oliviaColman = createPerson("Olivia", "Colman", 10L, "Female");

        Person damien = createPerson("Damien", "Chazelle", 4L, "Male");

        Person christopherNolan = createPerson("Christopher", "Nolan", 5L, "Male");
        Person christopherReeve = createPerson("Christopher", "Reeve", 11L, "Male");

        Person florence = createPerson("Florence", "Pugh", 6L, "Female");

        personRepository.save(emmaStone);
        personRepository.save(emmaWatson);
        personRepository.save(emmaThompson);
        personRepository.save(ryanGosling);
        personRepository.save(ryanReynolds);
        personRepository.save(oliviaWilde);
        personRepository.save(oliviaColman);
        personRepository.save(damien);
        personRepository.save(christopherNolan);
        personRepository.save(christopherReeve);
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

        // Additional movies
        Movie harryPotter = createMovie(
            "Harry Potter and the Deathly Hallows: Part 2",
            LocalDate.of(2011, 7, 15),
            1005L,
            new BigDecimal("125000000"),
            new BigDecimal("1342000000"),
            130,
            "The final battle between Harry Potter and Lord Voldemort."
        );

        Movie deadpool = createMovie(
            "Deadpool",
            LocalDate.of(2016, 2, 12),
            1006L,
            new BigDecimal("58000000"),
            new BigDecimal("782000000"),
            108,
            "A wisecracking mercenary gets experimented on and becomes immortal but ugly."
        );

        Movie favourites = createMovie(
            "The Favourite",
            LocalDate.of(2018, 11, 23),
            1007L,
            new BigDecimal("15000000"),
            new BigDecimal("95000000"),
            119,
            "In early 18th century England, a frail Queen Anne occupies the throne."
        );

        movieRepository.save(lalaland);
        movieRepository.save(oppenheimer);
        movieRepository.save(dontworrydarling);
        movieRepository.save(poorThings);
        movieRepository.save(harryPotter);
        movieRepository.save(deadpool);
        movieRepository.save(favourites);

        // Create credits for La La Land
        createCredit(emmaStone, lalaland, Credit.RoleType.CAST, "Acting", "Actor", "Mia", 1);
        createCredit(ryanGosling, lalaland, Credit.RoleType.CAST, "Acting", "Actor", "Sebastian", 2);
        createCredit(damien, lalaland, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(damien, lalaland, Credit.RoleType.CREW, "Writing", "Writer", null, null);

        // Create credits for Oppenheimer
        createCredit(florence, oppenheimer, Credit.RoleType.CAST, "Acting", "Actor", "Jean Tatlock", 3);
        createCredit(christopherNolan, oppenheimer, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(christopherNolan, oppenheimer, Credit.RoleType.CREW, "Writing", "Writer", null, null);
        createCredit(christopherNolan, oppenheimer, Credit.RoleType.CREW, "Production", "Producer", null, null);

        // Create credits for Don't Worry Darling
        createCredit(florence, dontworrydarling, Credit.RoleType.CAST, "Acting", "Actor", "Alice Chambers", 1);
        createCredit(oliviaWilde, dontworrydarling, Credit.RoleType.CREW, "Directing", "Director", null, null);
        createCredit(oliviaWilde, dontworrydarling, Credit.RoleType.CREW, "Production", "Producer", null, null);

        // Create credits for Poor Things
        createCredit(emmaStone, poorThings, Credit.RoleType.CAST, "Acting", "Actor", "Bella Baxter", 1);
        createCredit(emmaThompson, poorThings, Credit.RoleType.CAST, "Acting", "Actor", "Felicity Baxter", 2);

        // Create credits for Harry Potter
        createCredit(emmaWatson, harryPotter, Credit.RoleType.CAST, "Acting", "Actor", "Hermione Granger", 2);

        // Create credits for Deadpool
        createCredit(ryanReynolds, deadpool, Credit.RoleType.CAST, "Acting", "Actor", "Wade Wilson", 1);
        createCredit(ryanReynolds, deadpool, Credit.RoleType.CREW, "Production", "Producer", null, null);

        // Create credits for The Favourite
        createCredit(emmaStone, favourites, Credit.RoleType.CAST, "Acting", "Actor", "Abigail", 1);
        createCredit(emmaThompson, favourites, Credit.RoleType.CREW, "Production", "Executive Producer", null, null);
        createCredit(oliviaColman, favourites, Credit.RoleType.CAST, "Acting", "Actor", "Queen Anne", 2);

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
