package com.flicknames.service.research.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CulturalUsageDTO {
    private String culturalOrigin;
    private String culturalMeaning;
    private Integer prevalence;
}
