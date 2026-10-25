package com.flicknames.service.collector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TMDBPersonDTO {

    private Long id;
    private String name;
    private Integer gender;
    private String biography;

    @JsonProperty("profile_path")
    private String profilePath;

    @JsonProperty("imdb_id")
    private String imdbId;
}
