package com.flicknames.service.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MovieRevenueSnapshotRepository extends JpaRepository<MovieRevenueSnapshot, Long> {

    Optional<MovieRevenueSnapshot> findFirstByMovieIdOrderBySnapshotDateDesc(Long movieId);
}
