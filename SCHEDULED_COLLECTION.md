# Automatic Scheduled Data Collection

The Flicknames service can automatically collect movie data from TMDB on a schedule.

## Default Schedule (when enabled)

**Popular Movies:**
- Runs: Daily at 3:00 AM
- Collects: 3 pages (60 movies, ~120 API calls)
- Duration: ~30 seconds with rate limiting

**Current Year Movies:**
- Runs: Weekly on Sunday at 4:00 AM
- Collects: 5 pages (100 movies, ~200 API calls)
- Duration: ~50 seconds with rate limiting

## Enabling Automatic Collection

### On Railway (Production)

Add this environment variable:
```
COLLECTOR_SCHEDULE_ENABLED=true
```

That's it! The scheduler will start automatically on the next deployment.

### Locally (Development)

Edit `application.properties`:
```properties
collector.schedule.enabled=true
```

Then run:
```bash
./mvnw spring-boot:run
```

## Customizing the Schedule

You can customize what gets collected and when via Railway environment variables:

### Change Schedule Times

Use cron expressions:
```
COLLECTOR_POPULAR_CRON=0 0 2 * * *          # 2 AM daily
COLLECTOR_CURRENT_YEAR_CRON=0 0 5 * * MON  # 5 AM every Monday
```

Cron format: `second minute hour day month dayOfWeek`
- `0 0 3 * * *` = 3 AM every day
- `0 0 4 * * SUN` = 4 AM every Sunday
- `0 30 2 * * MON-FRI` = 2:30 AM weekdays only

### Change Collection Size

Adjust how many pages to collect per run:
```
COLLECTOR_POPULAR_PAGES=5      # Collect 5 pages (100 movies)
COLLECTOR_CURRENT_YEAR_PAGES=10 # Collect 10 pages (200 movies)
```

**Note:** More pages = more API calls = longer runtime
- 1 page = 20 movies = ~40 API calls = ~10 seconds
- 5 pages = 100 movies = ~200 API calls = ~50 seconds
- 10 pages = 200 movies = ~400 API calls = ~100 seconds

### Disable Specific Jobs

Disable individual scheduled jobs:
```
COLLECTOR_POPULAR_ENABLED=false           # Disable popular movies collection
COLLECTOR_CURRENT_YEAR_ENABLED=false      # Disable current year collection
```

## Safety Features

✅ **Rate Limiting:** 4 requests/second (very conservative)
✅ **Duplicate Prevention:** Checks `DataSource` table, skips already-fetched movies
✅ **Error Handling:** Failed collections are logged but don't crash the app
✅ **Configurable:** Can enable/disable or adjust schedules without code changes

## Monitoring

Check Railway logs to see scheduled runs:
```
Starting scheduled collection of popular movies (3 pages) at 2024-01-19T03:00:00
Collecting movie with TMDB ID: 550
Successfully collected movie: Fight Club (1999) with 203 credits
...
Successfully completed scheduled popular movies collection
```

## API Quota Impact

With default settings:
- **Daily:** 3 pages = ~120 API calls
- **Weekly:** 5 pages = ~200 API calls
- **Total per week:** ~1,040 API calls

TMDB free tier allows **50 requests/second** with no daily limit. Our usage is extremely conservative.

## Manual Override

Even with scheduling enabled, you can still manually trigger collections:
```bash
# Collect additional movies manually
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/popular?pages=10"

# Or collect specific movies
curl -X POST "https://flicknames-service-production.up.railway.app/api/v1/collector/movie/550"
```

Manual collections also respect rate limiting and duplicate checking.

## Disabling the Scheduler

To turn off automatic collection:

**On Railway:**
Remove the `COLLECTOR_SCHEDULE_ENABLED` variable or set it to `false`

The app will continue running normally, but scheduled jobs won't execute.

## Timezone

Scheduled times use the server's timezone:
- Railway uses **UTC** by default
- 3 AM UTC = 10 PM EST (previous day) / 7 PM PST (previous day)
- Adjust cron expressions to match your preferred local time

Example for US Eastern Time (EST):
```
COLLECTOR_POPULAR_CRON=0 0 8 * * *  # 8 AM UTC = 3 AM EST
```

## Best Practices

**For Development/Testing:**
- Keep `collector.schedule.enabled=false` (default)
- Use manual endpoints to control when data is collected

**For Production:**
1. Start with defaults (3 pages popular daily, 5 pages yearly weekly)
2. Monitor database size and API usage
3. Adjust schedules/pages as needed
4. Consider running jobs during low-traffic hours (early morning)

**For High Volume:**
- Increase pages: `COLLECTOR_POPULAR_PAGES=10`
- Increase frequency: `COLLECTOR_POPULAR_CRON=0 0 3 * * *` (daily) → `0 0 */6 * * *` (every 6 hours)
- Rate limiting ensures you never exceed TMDB limits
