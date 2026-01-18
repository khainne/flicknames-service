package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private String gender;
    private String description;
}
