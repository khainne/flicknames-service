# IMDb Dataset Import Guide

Import bulk movie and name data from IMDb's public non-commercial datasets.

## Overview

IMDb provides free datasets for non-commercial use at https://datasets.imdbws.com/

These TSV (tab-separated) files contain:
- **title.basics.tsv.gz** - Movie titles, years, runtime (600MB+)
- **name.basics.tsv.gz** - Person names and professions (200MB+)
- **title.principals.tsv.gz** - Cast and crew credits (1GB+)
- **title.ratings.tsv.gz** - IMDb ratings (optional)

## Legal Notice

IMDb datasets are available for **non-commercial use only** under the IMDb Dataset License:
https://www.imdb.com/interfaces/

✅ **Allowed:** Personal projects, research, education
❌ **Not allowed:** Commercial applications without licensing

For Flicknames (a baby name discovery app), this is appropriate non-commercial use.

## How to Download

```bash
# Download datasets
wget https://datasets.imdbws.com/title.basics.tsv.gz
wget https://datasets.imdbws.com/name.basics.tsv.gz
wget https://datasets.imdbws.com/title.principals.tsv.gz

# Optional: ratings for filtering quality
wget https://datasets.imdbws.com/title.ratings.tsv.gz
```

These are large files (1-2GB total compressed). Keep them gzipped - the importer handles compressed files automatically.

## Import Process

The import is designed to be **memory-efficient** and **incremental**:

### 1. Import Movies (title.basics.tsv.gz)

Imports movies only (excludes TV shows) within a specified year range:

```bash
POST /api/v1/imdb/import/movies?filePath=/path/to/title.basics.tsv.gz&minYear=2000&maxYear=2025
```

Parameters:
- `filePath` - Local path to title.basics.tsv.gz
- `minYear` - Start year (default: 2000)
- `maxYear` - End year (default: 2025)

This will import:
- Movie titles
- Release years
- Runtime
- IMDb IDs (tconst format: tt1234567)

**Performance:** Processes ~10,000 movies/minute, skips duplicates automatically.

### 2. Import People (name.basics.tsv.gz)

Imports only people who appear in the movies we've imported:

```bash
POST /api/v1/imdb/import/people?principalsFilePath=/path/to/title.principals.tsv.gz&peopleFilePath=/path/to/name.basics.tsv.gz
```

Why two files?
1. `title.principals.tsv.gz` tells us which people (nconst IDs) are in our movies
2. `name.basics.tsv.gz` contains the actual names for those people

This two-pass approach avoids importing millions of unused people.

**Performance:** Processes ~20,000 people/minute.

### 3. Import Credits (title.principals.tsv.gz)

Links people to movies with character names:

```bash
POST /api/v1/imdb/import/credits?filePath=/path/to/title.principals.tsv.gz
```

This creates:
- Credit records (who worked on which movie)
- Character entities (parsed from JSON character arrays)
- Links to existing Person and Movie records

**Performance:** Processes ~50,000 credits/minute.

## Full Import Example

```bash
# Step 1: Import movies from 2020-2024
curl -X POST "http://localhost:8080/api/v1/imdb/import/movies?filePath=/data/title.basics.tsv.gz&minYear=2020&maxYear=2024"

# Step 2: Import people who worked on those movies
curl -X POST "http://localhost:8080/api/v1/imdb/import/people?principalsFilePath=/data/title.principals.tsv.gz&peopleFilePath=/data/name.basics.tsv.gz"

# Step 3: Import credits linking them together
curl -X POST "http://localhost:8080/api/v1/imdb/import/credits?filePath=/data/title.principals.tsv.gz"
```

## Data Quality

**Advantages over TMDB:**
- ✅ Massive dataset (millions of movies, names)
- ✅ No API rate limits
- ✅ Free for non-commercial use
- ✅ Historical data back to early cinema

**Limitations:**
- ❌ No box office revenue data (use TMDB for this)
- ❌ Less metadata (no posters, plots, etc.)
- ❌ Requires local file storage

## Combining IMDb + TMDB

Best practice: Use **both** data sources:

1. **IMDb for bulk names:** Import decades of movies to get comprehensive name coverage
2. **TMDB for trending:** Add box office revenue and recent releases via TMDB API

The `DataSource` tracking ensures no duplicates:
- Each movie/person tracks its source (IMDb, TMDB, or both)
- Duplicate checking prevents redundant API calls
- You can enrich IMDb movies with TMDB data later

## Storage Requirements

Estimated database sizes after import:

| Year Range | Movies | People | Credits | DB Size |
|------------|--------|--------|---------|---------|
| 2020-2024  | ~50K   | ~200K  | ~2M     | ~500MB  |
| 2010-2024  | ~200K  | ~800K  | ~8M     | ~2GB    |
| 2000-2024  | ~500K  | ~2M    | ~20M    | ~5GB    |

Railway's free tier includes 512MB PostgreSQL - recommend limiting to recent years (2020-2024) or upgrading plan.

## Troubleshooting

**File not found:**
- Ensure file paths are absolute: `/home/user/data/file.tsv.gz`
- Files must be accessible from the app's filesystem

**Out of memory:**
- The importer uses streaming (low memory)
- But large imports may need more heap: `-Xmx2g`

**Slow imports:**
- Imports run synchronously (may timeout on web requests)
- Consider running as background job or CLI command
- Or split into smaller year ranges

**Duplicates:**
- Already imported? Check `data_sources` table
- Re-running imports skips existing data automatically

## Next Steps

After import:
1. Check trending names: `GET /api/v1/all-names/trending/weekly?limit=20`
2. View movies: `GET /api/v1/movies/recent`
3. Search by name: `GET /api/v1/names/{firstName}/people`

Enrich with TMDB:
- Use `POST /api/v1/collector/popular` to add box office data
- This enables revenue-based trending (the core Flicknames feature)
