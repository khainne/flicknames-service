# Flicknames Data Collector

The data collector fetches movie and credit information from The Movie Database (TMDB) and populates the Flicknames database with first names from movies.

## Setup

1. Get a TMDB API key from https://www.themoviedb.org/settings/api
2. Set the `TMDB_API_KEY` environment variable:
   ```bash
   export TMDB_API_KEY=your_api_key_here
   ```
   Or on Railway, set it in the Variables tab.

## API Endpoints

### Collect a Single Movie
```bash
POST /api/v1/collector/movie/{tmdbMovieId}
```
Fetches a specific movie and all its credits by TMDB ID.

Example:
```bash
curl -X POST http://localhost:8080/api/v1/collector/movie/313369
```

### Collect Popular Movies
```bash
POST /api/v1/collector/popular?pages=1
```
Fetches popular movies from TMDB (20 movies per page).

Example:
```bash
curl -X POST "http://localhost:8080/api/v1/collector/popular?pages=3"
```

### Collect Movies by Year
```bash
POST /api/v1/collector/year/{year}?pages=1
```
Fetches top box office movies for a specific year, sorted by revenue.

Example:
```bash
curl -X POST "http://localhost:8080/api/v1/collector/year/2024?pages=5"
```

## How It Works

1. **TMDBClient** - Makes rate-limited API calls to TMDB
2. **DataCollectorService** - Transforms TMDB data and saves to database:
   - Creates Person entities from cast and crew
   - Creates Character entities from character names
   - Creates Movie entities with box office revenue
   - Links them all via Credit entities
3. **Name Extraction** - Automatically parses full names into first/last names for aggregation

## Rate Limiting

The collector respects TMDB's API limits:
- Default: 4 requests per second (conservative)
- Configurable via `tmdb.rate-limit.requests-per-second` property

## Data Collection Strategy

For a comprehensive baby name dataset, consider:

1. **Recent Years** - Collect last 5 years for trending names:
   ```bash
   POST /api/v1/collector/year/2024?pages=10
   POST /api/v1/collector/year/2023?pages=10
   ```

2. **Popular Classics** - Popular movies have the most recognizable names:
   ```bash
   POST /api/v1/collector/popular?pages=10
   ```

3. **Specific Movies** - Add iconic films manually:
   ```bash
   POST /api/v1/collector/movie/550     # Fight Club
   POST /api/v1/collector/movie/680     # Pulp Fiction
   ```

## Notes

- Duplicate checking: The collector automatically skips existing movies/people/characters
- Factual data only: We only collect names, credits, and box office (not copyrighted content)
- Transaction safety: Each movie is collected in its own transaction
