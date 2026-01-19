package com.flicknames.service.collector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class TMDBCreditsDTO {

    private Long id; // Movie ID
    private List<CastMember> cast;
    private List<CrewMember> crew;

    @Data
    public static class CastMember {
        private Long id;
        private String name;

        @JsonProperty("original_name")
        private String originalName;

        private String character;

        @JsonProperty("credit_id")
        private String creditId;

        private Integer gender; // 0: not set, 1: female, 2: male, 3: non-binary

        private Integer order;

        @JsonProperty("profile_path")
        private String profilePath;
    }

    @Data
    public static class CrewMember {
        private Long id;
        private String name;

        @JsonProperty("original_name")
        private String originalName;

        private String job;
        private String department;

        @JsonProperty("credit_id")
        private String creditId;

        private Integer gender; // 0: not set, 1: female, 2: male, 3: non-binary

        @JsonProperty("profile_path")
        private String profilePath;
    }
}
