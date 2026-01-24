package com.flicknames.service.config;

import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.ScreenCharacterRepository;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.util.CharacterNameParser;
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
    private final ScreenCharacterRepository characterRepository;
    private final CharacterNameParser characterNameParser;

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

        // Create character entities
        ScreenCharacter mia = createCharacter("Mia", "Dolan", "Female");
        ScreenCharacter sebastian = createCharacter("Sebastian", "Wilder", "Male");
        ScreenCharacter jeanTatlock = createCharacter("Jean", "Tatlock", "Female");
        ScreenCharacter aliceChambers = createCharacter("Alice", "Chambers", "Female");
        ScreenCharacter bellaBaxter = createCharacter("Bella", "Baxter", "Female");
        ScreenCharacter felicityBaxter = createCharacter("Felicity", "Baxter", "Female");
        ScreenCharacter hermioneGranger = createCharacter("Hermione", "Granger", "Female");
        ScreenCharacter wadeWilson = createCharacter("Wade", "Wilson", "Male");
        ScreenCharacter abigail = createCharacter("Abigail", null, "Female");
        ScreenCharacter queenAnne = createCharacter("Anne", null, "Female");

        characterRepository.save(mia);
        characterRepository.save(sebastian);
        characterRepository.save(jeanTatlock);
        characterRepository.save(aliceChambers);
        characterRepository.save(bellaBaxter);
        characterRepository.save(felicityBaxter);
        characterRepository.save(hermioneGranger);
        characterRepository.save(wadeWilson);
        characterRepository.save(abigail);
        characterRepository.save(queenAnne);

        // Create credits for La La Land
        createCredit(emmaStone, lalaland, mia, Credit.RoleType.CAST, "Acting", "Actor", 1);
        createCredit(ryanGosling, lalaland, sebastian, Credit.RoleType.CAST, "Acting", "Actor", 2);
        createCredit(damien, lalaland, null, Credit.RoleType.CREW, "Directing", "Director", null);
        createCredit(damien, lalaland, null, Credit.RoleType.CREW, "Writing", "Writer", null);

        // Create credits for Oppenheimer
        createCredit(florence, oppenheimer, jeanTatlock, Credit.RoleType.CAST, "Acting", "Actor", 3);
        createCredit(christopherNolan, oppenheimer, null, Credit.RoleType.CREW, "Directing", "Director", null);
        createCredit(christopherNolan, oppenheimer, null, Credit.RoleType.CREW, "Writing", "Writer", null);
        createCredit(christopherNolan, oppenheimer, null, Credit.RoleType.CREW, "Production", "Producer", null);

        // Create credits for Don't Worry Darling
        createCredit(florence, dontworrydarling, aliceChambers, Credit.RoleType.CAST, "Acting", "Actor", 1);
        createCredit(oliviaWilde, dontworrydarling, null, Credit.RoleType.CREW, "Directing", "Director", null);
        createCredit(oliviaWilde, dontworrydarling, null, Credit.RoleType.CREW, "Production", "Producer", null);

        // Create credits for Poor Things
        createCredit(emmaStone, poorThings, bellaBaxter, Credit.RoleType.CAST, "Acting", "Actor", 1);
        createCredit(emmaThompson, poorThings, felicityBaxter, Credit.RoleType.CAST, "Acting", "Actor", 2);

        // Create credits for Harry Potter
        createCredit(emmaWatson, harryPotter, hermioneGranger, Credit.RoleType.CAST, "Acting", "Actor", 2);

        // Create credits for Deadpool
        createCredit(ryanReynolds, deadpool, wadeWilson, Credit.RoleType.CAST, "Acting", "Actor", 1);
        createCredit(ryanReynolds, deadpool, null, Credit.RoleType.CREW, "Production", "Producer", null);

        // Create credits for The Favourite
        createCredit(emmaStone, favourites, abigail, Credit.RoleType.CAST, "Acting", "Actor", 1);
        createCredit(emmaThompson, favourites, null, Credit.RoleType.CREW, "Production", "Executive Producer", null);
        createCredit(oliviaColman, favourites, queenAnne, Credit.RoleType.CAST, "Acting", "Actor", 2);

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

    private ScreenCharacter createCharacter(String firstName, String lastName, String gender) {
        String fullName = lastName != null ? firstName + " " + lastName : firstName;
        CharacterNameParser.ParseResult parseResult = characterNameParser.parse(fullName);
        return ScreenCharacter.builder()
            .firstName(parseResult.getFirstName())
            .lastName(parseResult.getLastName())
            .fullName(fullName)
            .gender(gender)
            .nameType(parseResult.getNameType())
            .description("ScreenCharacter from the film")
            .build();
    }

    private void createCredit(Person person, Movie movie, ScreenCharacter character,
                            Credit.RoleType roleType, String department, String job, Integer order) {
        Credit credit = Credit.builder()
            .person(person)
            .movie(movie)
            .character(character)
            .roleType(roleType)
            .department(department)
            .job(job)
            .order(order)
            .build();
        creditRepository.save(credit);
    }
}
