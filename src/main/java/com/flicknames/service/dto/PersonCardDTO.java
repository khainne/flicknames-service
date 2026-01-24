package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight person DTO for lists and cards
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonCardDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private String profilePath;
    private String gender;
}
