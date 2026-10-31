package com.flicknames.service.market;

import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.ScreenCharacter;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the weekly name index from credits and stores the top names per market per week. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NameIndexService {

    /** Names kept per market per week; anything below is reported as unranked. */
    static final int KEEP_TOP = 300;
    /** One call rebuilds at most this many weeks (about five years), to keep requests bounded. */
    static final int MAX_WEEKS_PER_CALL = 260;

    static final List<ScreenCharacter.NameType> CHARACTER_NAME_TYPES =
        List.of(ScreenCharacter.NameType.STANDARD, ScreenCharacter.NameType.SINGLE_NAME);

    private final EntityManager entityManager;
    private final NameIndexWeekRepository indexRepository;
    private final TransactionTemplate transactionTemplate;

    public Map<String, Object> rebuild(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate first = MarketWindow.weekStart(from);
        LocalDate last = MarketWindow.weekStart(to.isAfter(today) ? today : to);
        long started = System.currentTimeMillis();
        int weeks = 0;
        long rows = 0;
        LocalDate week = first;
        while (!week.isAfter(last) && weeks < MAX_WEEKS_PER_CALL) {
            for (Market market : Market.values()) {
                rows += rebuildWeek(market, week, today);
            }
            weeks++;
            week = week.plusWeeks(1);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fromWeek", first);
        report.put("lastWeekBuilt", week.minusWeeks(1));
        report.put("weeks", weeks);
        report.put("rowsWritten", rows);
        report.put("done", week.isAfter(last));
        report.put("nextFrom", week.isAfter(last) ? null : week);
        report.put("millis", System.currentTimeMillis() - started);
        log.info("Name index rebuild: {}", report);
        return report;
    }

    int rebuildWeek(Market market, LocalDate weekStart, LocalDate today) {
        LocalDate asOf = MarketWindow.asOf(weekStart, today);
        List<NameIndexCalculator.CreditRow> credits = loadCredits(market, MarketWindow.windowStart(asOf), asOf);
        List<NameIndexCalculator.IndexRow> rows = NameIndexCalculator.compute(credits, KEEP_TOP);
        Integer written = transactionTemplate.execute(status -> {
            indexRepository.deleteWeek(market, weekStart);
            List<NameIndexWeek> entities = new ArrayList<>(rows.size());
            for (NameIndexCalculator.IndexRow row : rows) {
                NameIndexWeek e = new NameIndexWeek();
                e.setMarket(market);
                e.setName(row.name());
                e.setNameKey(row.nameKey());
                e.setWeekStart(weekStart);
                e.setWeightedRevenue(row.weightedRevenue());
                e.setIndexValue(row.indexValue());
                e.setRankPosition(row.rank());
                e.setMovieCount(row.movieCount());
                entities.add(e);
            }
            indexRepository.saveAll(entities);
            return entities.size();
        });
        return written == null ? 0 : written;
    }

    List<NameIndexCalculator.CreditRow> loadCredits(Market market, LocalDate from, LocalDate to) {
        String nameExpr = market == Market.PERSON ? "p.firstName" : "ch.firstName";
        String join = market == Market.PERSON ? "JOIN cr.person p" : "JOIN cr.character ch";
        String extra = market == Market.PERSON ? "" : " AND ch.nameType IN :types";
        var query = entityManager.createQuery(
            "SELECT " + nameExpr + ", m.id, m.revenue, cr.roleType, cr.job, cr.order "
                + "FROM Credit cr " + join + " JOIN cr.movie m "
                + "WHERE m.releaseDate BETWEEN :from AND :to AND m.revenue > 0 AND " + nameExpr + " IS NOT NULL"
                + extra, Object[].class)
            .setParameter("from", from)
            .setParameter("to", to);
        if (market == Market.CHARACTER) {
            query.setParameter("types", CHARACTER_NAME_TYPES);
        }
        List<NameIndexCalculator.CreditRow> rows = new ArrayList<>();
        for (Object[] r : query.getResultList()) {
            double weight = CreditWeights.weight((Credit.RoleType) r[3], (String) r[4], (Integer) r[5]);
            rows.add(new NameIndexCalculator.CreditRow(
                (String) r[0], (Long) r[1], ((BigDecimal) r[2]).doubleValue(), weight));
        }
        return rows;
    }
}
