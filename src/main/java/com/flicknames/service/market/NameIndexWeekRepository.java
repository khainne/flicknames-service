package com.flicknames.service.market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NameIndexWeekRepository extends JpaRepository<NameIndexWeek, Long> {

    @Modifying
    @Query("DELETE FROM NameIndexWeek n WHERE n.market = :market AND n.weekStart = :weekStart")
    int deleteWeek(@Param("market") Market market, @Param("weekStart") LocalDate weekStart);

    @Query("SELECT MAX(n.weekStart) FROM NameIndexWeek n WHERE n.market = :market")
    Optional<LocalDate> latestWeek(@Param("market") Market market);

    List<NameIndexWeek> findByMarketAndWeekStartOrderByRankPositionAsc(Market market, LocalDate weekStart);

    List<NameIndexWeek> findByMarketAndWeekStartAndNameKeyIn(Market market, LocalDate weekStart, Collection<String> nameKeys);

    List<NameIndexWeek> findByMarketAndNameKeyInAndWeekStartBetweenOrderByWeekStartAsc(
        Market market, Collection<String> nameKeys, LocalDate from, LocalDate to);
}
