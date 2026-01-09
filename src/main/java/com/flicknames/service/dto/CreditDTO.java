package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditDTO {
    private Long id;
    private String roleType;
    private String department;
    private String job;
    private String character;
    private Integer order;
    private PersonDTO person;
    private MovieDTO movie;
}
