package com.flicknames.service.market;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The name market: each first name is quoted like a stock, by its share of weighted box office
 * from films released in the last six weeks. Reads are public; rebuilds are admin-only (the
 * /api/v1/admin prefix needs X-Admin-Key).
 */
@RestController
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final NameIndexService nameIndexService;
    private final RevenueRefreshService revenueRefreshService;

    @GetMapping("/api/v1/market/movers")
    @Operation(summary = "Top names, biggest gainers and biggest losers this week, with sparklines and drivers")
    public MarketDtos.Movers movers(@RequestParam(defaultValue = "PERSON") Market market,
                                    @RequestParam(defaultValue = "10") int limit) {
        return marketService.movers(market, limit, today())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The name index hasn't been built yet"));
    }

    @GetMapping("/api/v1/market/names/{name}/history")
    @Operation(summary = "One name's weekly index over time (default: the last five years)")
    public MarketDtos.History nameHistory(@PathVariable String name,
                                          @RequestParam(defaultValue = "PERSON") Market market,
                                          @RequestParam(defaultValue = "WEEK") MarketDtos.Interval interval,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusYears(5);
        return marketService.history(market, List.of(name), start, end, interval).values().stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
    }

    @GetMapping("/api/v1/market/compare")
    @Operation(summary = "Weekly index for up to 8 comma-separated names (default: the last five years)")
    public Map<String, MarketDtos.History> compare(@RequestParam String names,
                                                   @RequestParam(defaultValue = "PERSON") Market market,
                                                   @RequestParam(defaultValue = "WEEK") MarketDtos.Interval interval,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusYears(5);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return marketService.history(market, Arrays.asList(names.split(",")), start, end, interval);
    }

    @GetMapping("/api/v1/market/heatmap")
    @Operation(summary = "The most prominent names over a period, with their average index per month or year",
               description = "Defaults to the top 20 names over the last two years, by month.")
    public MarketDtos.Heatmap heatmap(@RequestParam(defaultValue = "PERSON") Market market,
                                      @RequestParam(defaultValue = "MONTH") MarketDtos.Interval interval,
                                      @RequestParam(defaultValue = "20") int top,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusYears(2);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return marketService.heatmap(market, start, end, interval, top);
    }

    @PostMapping("/api/v1/admin/market/rebuild")
    @Operation(summary = "Rebuild the weekly name index between two dates (at most about five years per call)",
               description = "Call again with nextFrom until done is true.")
    public Map<String, Object> rebuild(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return nameIndexService.rebuild(from, to != null ? to : today(), today());
    }

    @PostMapping("/api/v1/admin/market/refresh-revenue")
    @Operation(summary = "Re-fetch TMDB revenue for recent films and record snapshots (dry run by default)")
    public Map<String, Object> refreshRevenue(@RequestParam(defaultValue = "120") int days,
                                              @RequestParam(defaultValue = "true") boolean dryRun) {
        return revenueRefreshService.refresh(days, dryRun, today());
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
