package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Simple profession count for word cloud visualizations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfessionCountDTO {
    private String name;
    private Integer count;
    private Double percentage;
}
