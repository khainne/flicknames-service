# Flicknames Service

Backend REST API service for the Flicknames application - discover baby name inspiration from movie credits based on box office performance.

## Overview

Flicknames aggregates names from movie credits (cast and crew) and ranks them by box office performance. Users can discover trending names from current box office hits, explore historically successful names, and get detailed statistics about any name's movie career.

## Technology Stack

- Java 17
- Spring Boot 3.2.1
- Spring Data JPA
- H2 Database (in-memory, PostgreSQL for production)
- Maven
- Lombok
- SpringDoc OpenAPI (Swagger)

## Architecture

This is a **read-only API service** that serves data to frontend clients. A separate **flicknames-collector** service handles data ingestion from TMDB (The Movie Database).

### Database Schema

- **Person**: Individual names with TMDB sync support
- **Movie**: Complete movie metadata including revenue for rankings
- **Credit**: Junction table linking people to movies with role details (cast/crew)

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+

### Running the Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

### Building the Application

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

## API Documentation

Once running, access the interactive API documentation at:
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI Spec**: `http://localhost:8080/api-docs`

## API Endpoints

### Names API (`/api/v1/names`)
Discover and explore names based on box office performance

- `GET /trending/weekly?limit=20` - Names from this week's box office
- `GET /trending/yearly?limit=20` - Highest grossing names this year
- `GET /trending/yearly/{year}?limit=20` - Top names for a specific year

### People API (`/api/v1/people`)
Detailed information about individuals

- `GET /{id}` - Get person details by ID
- `GET /{id}/stats` - Statistics (total box office, movie count, job breakdown)
- `GET /{id}/movies` - All movies for a person
- `GET /search?q={name}&page=0&size=20` - Search for people by name

### Movies API (`/api/v1/movies`)
Movie information and credits

- `GET /{id}` - Get movie details by ID
- `GET /{id}/credits` - Movie with full cast and crew
- `GET /search?q={title}&page=0&size=20` - Search movies by title
- `GET /box-office/current?limit=10` - Current box office movies
- `GET /box-office/year/{year}?limit=20` - Top movies by year
- `GET /recent?limit=20` - Recent releases

### Health Check
- `GET /api/health` - Service health status

## Sample Data

The application includes seed data with sample movies and people for testing:
- Emma Stone (La La Land, Poor Things)
- Ryan Gosling (La La Land)
- Christopher Nolan (Oppenheimer)
- Florence Pugh (Oppenheimer, Don't Worry Darling)
- And more...

## Database Access

### H2 Console (Development)
Access the H2 console at: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:flicknamesdb`
- Username: `sa`
- Password: (leave empty)

## Project Structure

```
src/main/java/com/flicknames/service/
├── FlicknamesServiceApplication.java
├── config/
│   └── DataInitializer.java          # Seed data
├── controller/
│   ├── HealthController.java
│   ├── NameController.java           # Trending names endpoints
│   ├── PersonController.java         # Person details endpoints
│   └── MovieController.java          # Movie endpoints
├── dto/
│   ├── PersonDTO.java
│   ├── MovieDTO.java
│   ├── CreditDTO.java
│   ├── TrendingPersonDTO.java
│   ├── PersonStatsDTO.java
│   └── MovieWithCreditsDTO.java
├── entity/
│   ├── Person.java
│   ├── Movie.java
│   └── Credit.java
├── repository/
│   ├── PersonRepository.java
│   ├── MovieRepository.java
│   └── CreditRepository.java
└── service/
    ├── PersonService.java
    ├── MovieService.java
    └── NameService.java              # Core trending/aggregation logic
```

## Example Use Cases

1. **"Show me trending names this week"**
   - `GET /api/v1/names/trending/weekly`
   - Returns names from current box office movies ranked by revenue

2. **"What names were in the biggest movies of 2023?"**
   - `GET /api/v1/names/trending/yearly/2023`
   - Returns top names from 2023 movies by box office

3. **"Tell me about the name Emma"**
   - `GET /api/v1/people/search?q=Emma` to find ID
   - `GET /api/v1/people/{id}/stats` for statistics
   - `GET /api/v1/people/{id}/movies` for filmography

## Data Collection

This service is **read-only**. Data collection is handled by the separate **flicknames-collector** service which:
- Fetches movie data from TMDB API
- Populates the shared database
- Runs scheduled updates

## Production Considerations

For production deployment:
1. Switch from H2 to PostgreSQL
2. Configure connection pooling
3. Enable caching (Redis/Caffeine)
4. Add rate limiting
5. Configure proper logging and monitoring
6. Set up the flicknames-collector service for data updates

## Future Enhancements

- [ ] Box office tracking by week
- [ ] Name popularity trends over time
- [ ] Gender-based filtering
- [ ] Role-based filtering (actors only, directors only, etc.)
- [ ] Advanced search with multiple criteria
- [ ] Name analytics (decade trends, genre preferences)
