package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieWithCreditsDTO {
    private MovieDTO movie;
    private List<CreditDTO> cast;
    private List<CreditDTO> crew;
}
