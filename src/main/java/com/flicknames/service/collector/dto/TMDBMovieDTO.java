package com.flicknames.service.collector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class TMDBMovieDTO {

    private Long id;
    private String title;

    @JsonProperty("original_title")
    private String originalTitle;

    private String overview;

    @JsonProperty("release_date")
    private LocalDate releaseDate;

    private Double popularity;

    @JsonProperty("vote_average")
    private Double voteAverage;

    @JsonProperty("vote_count")
    private Integer voteCount;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    private List<Integer> genres;

    private String status;

    private Long budget;

    private Long revenue;

    private Integer runtime;
}
