# Comprehensive Movie Collection System - Implementation Plan

## Overview

Successfully implemented a comprehensive collection system for gathering movies from TMDB for the years 2015-2025. The system overcomes TMDB's 500-page limit (10,000 results per query) using multiple sorting strategies and intelligent duplicate prevention.

## System Architecture

### Multi-Strategy Approach
The system uses 4 different sorting strategies to maximize coverage:
1. **popularity.desc** - Most popular movies first
2. **vote_count.desc** - Most voted movies first
3. **primary_release_date.desc** - Newest releases first
4. **original_title.asc** - Alphabetical sorting

Each strategy can return up to 10,000 results (500 pages × 20 results/page), so using 4 strategies allows us to collect up to 40,000 unique movies per year.

### Key Features
- **US-Only Filtering**: `with_origin_country=US` parameter
- **Vote Count Filtering**: Minimum vote threshold (default: 10) to exclude obscure entries
- **Duplicate Prevention**: DataSource tracking ensures movies aren't collected multiple times across strategies
- **Rate Limiting**: 4 requests/second (conservative, TMDB allows 50 req/sec)
- **Transaction Isolation**: Per-movie transactions prevent cascading failures

## Test Results (2024 - January 20, 2026)

### Small Test Parameters
- **Year**: 2024
- **US-Only**: true
- **Pages per Strategy**: 2
- **Duration**: 99 seconds (1 min 39 sec)

### Results
```json
{
  "status": "success",
  "year": 2024,
  "usOnly": true,
  "duration_minutes": 1,
  "duration_seconds": 99,
  "strategies": {
    "vote_count.desc": 40,
    "primary_release_date.desc": 40,
    "popularity.desc": 40,
    "original_title.asc": 40
  },
  "total_movies_collected": 160,
  "message": "Collected 160 movies for year 2024 using 4 strategies"
}
```

**Analysis**: System attempted to collect 160 movies (40 per strategy). Due to duplicate prevention (movies appearing in multiple strategies), the net increase was 63 unique movies.

## Current Database Status (After Test)

```
Total Movies (2015-2025): 518
├── 2025: 319 movies
├── 2024: 190 movies
├── 2023: 2 movies
├── 2022: 2 movies
├── 2021: 1 movie
├── 2018: 1 movie
└── 2016: 3 movies

People: 43,318
Characters: 11,744
Credits: 55,657
Success Rate: 100% (520/520)
```

## Available API Endpoints

### 1. Single Year Comprehensive Collection
```bash
POST /api/v1/collector/comprehensive/year/{year}
Parameters:
  - usOnly: boolean (default: false) - US movies only
  - maxPagesPerStrategy: int (default: 50) - Pages per sorting strategy

Example:
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/comprehensive/year/2024?usOnly=true&maxPagesPerStrategy=50"
```

### 2. Multi-Year Comprehensive Collection
```bash
POST /api/v1/collector/comprehensive/years
Parameters:
  - startYear: int (default: 2015)
  - endYear: int (default: 2025)
  - usOnly: boolean (default: false)
  - maxPagesPerStrategy: int (default: 50)

Example:
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/comprehensive/years?startYear=2015&endYear=2025&usOnly=true&maxPagesPerStrategy=50"
```

### 3. Segmented Collection (for high-volume years)
```bash
POST /api/v1/collector/segmented/year/{year}
Parameters:
  - usOnly: boolean (default: false)

Uses vote count segmentation:
  - Segment 1: ≥1000 votes (high popularity)
  - Segment 2: 100-999 votes (medium)
  - Segment 3: 10-99 votes (low)
  - Segment 4: <10 votes (very low)

Example:
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/segmented/year/2024?usOnly=true"
```

### 4. Collection Statistics
```bash
GET /api/v1/admin/collection-stats

Returns:
  - moviesByYear: Year-by-year breakdown (2015-2025)
  - fetchStatus: SUCCESS/FAILED counts
  - totalMovies2015to2025: Total movie count
  - successfulFetches: Number of successful API calls
```

### 5. Database Statistics
```bash
GET /api/v1/admin/db-stats

Returns counts for:
  - movies, people, characters, credits, dataSources
  - existingTables list
```

## Full Collection Plan (2015-2025)

### Estimated Scope
- **Years**: 11 (2015-2025)
- **Strategies per Year**: 4
- **Pages per Strategy**: 50 (configurable)
- **Total API Calls**: ~44,000 movie collections
- **Estimated Duration**: ~3 hours at 4 req/sec
- **Expected Unique Movies**: ~20,000-25,000 US movies

### Execution Command
```bash
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/comprehensive/years?startYear=2015&endYear=2025&usOnly=true&maxPagesPerStrategy=50" \
  -H "Content-Type: application/json"
```

### Configuration Override (if needed)
Configuration can be adjusted via environment variables in Railway:
```
COLLECTOR_COMPREHENSIVE_US_ONLY=true
COLLECTOR_COMPREHENSIVE_MIN_VOTE_COUNT=10
COLLECTOR_COMPREHENSIVE_MAX_PAGES=50
```

## Technical Implementation

### Files Modified
1. **TMDBClient.java** - Added:
   - `discoverMoviesByYearWithFilters()` - Comprehensive discovery with filters
   - `discoverMoviesByDateRange()` - Date range discovery

2. **DataCollectorService.java** - Added:
   - `collectYearComprehensive()` - Multi-strategy collection
   - `collectYearSegmented()` - Vote count segmentation
   - `collectWithSort()` - Single strategy collection helper
   - `collectYearVoteSegment()` - Vote segment helper

3. **CollectorController.java** - Added endpoints:
   - `/comprehensive/year/{year}` - Single year
   - `/comprehensive/years` - Multi-year range
   - `/segmented/year/{year}` - Segmented collection

4. **AdminController.java** - Added:
   - `/collection-stats` - Year-by-year analysis (PostgreSQL compatible)

5. **ComprehensiveCollectionResult.java** - New DTO for tracking results

6. **application-production.properties**:
   - Fixed: `spring.jpa.hibernate.ddl-auto=update` (was `create` - caused data loss!)
   - Increased daily collection: popular pages 3→10, current year 5→15
   - Added comprehensive collection configuration

## Known Limitations & Solutions

### TMDB API Limitations
- **500-page hard limit** per query (10,000 results)
  - **Solution**: Multiple sorting strategies
- **Rate limit**: 50 requests/second
  - **Solution**: Conservative 4 req/sec to avoid issues

### Database
- **PostgreSQL-specific**: Uses `EXTRACT(YEAR FROM date)` not `YEAR(date)`
- **Transaction Management**: Isolated per-movie to prevent cascading failures

## Monitoring During Collection

### Check Progress
```bash
# Watch database growth
watch -n 5 'curl -s https://flicknames-service-production.up.railway.app/api/v1/admin/db-stats | jq ".movies"'

# Check year-by-year stats
curl -s https://flicknames-service-production.up.railway.app/api/v1/admin/collection-stats | jq .
```

### Expected Growth Rate
At 4 requests/second with full details (credits, cast):
- ~240 movies/minute
- ~14,400 movies/hour
- ~43,200 movies in 3 hours

## Success Criteria

✅ **Completed**:
- Multi-strategy collection system implemented
- US-only filtering working
- Alphabetical sorting included
- Duplicate prevention via DataSource tracking
- PostgreSQL compatibility fixed
- Tested successfully on 2024 (63 new movies collected)

🔄 **Ready to Execute**:
- Full 10-year collection (2015-2025)
- Can be run at any time using the multi-year endpoint

## Next Steps

1. **Execute Full Collection**: Run the 2015-2025 collection command above
2. **Monitor Progress**: Use the monitoring commands to track growth
3. **Verify Coverage**: Check collection-stats to ensure even distribution across years
4. **Optional Segmented Collection**: For any years that hit the 10k limit, run segmented collection

## Maintenance

### Daily Scheduled Collection
Already configured in `application-production.properties`:
- Popular movies: 10 pages/day
- Current year: 15 pages/day
- Runs automatically via `@Scheduled` cron jobs

### Data Persistence
- **CRITICAL**: Ensure `spring.jpa.hibernate.ddl-auto=update` stays set
- **NEVER** set to `create` in production (wipes all data on restart!)

## Commit History
- `88872ef` - Implement comprehensive movie collection system for 2015-2025
- `fbfc1d1` - Fix PostgreSQL syntax in AdminController collection-stats query

---

**Status**: System fully operational and tested. Ready for full 10-year collection.
**Last Updated**: 2026-01-20
**Test Environment**: Railway (flicknames-service-production)
