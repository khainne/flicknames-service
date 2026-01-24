package com.flicknames.service.service;

import com.flicknames.service.dto.ProfessionCountDTO;
import com.flicknames.service.dto.ProfessionSummaryDTO;
import com.flicknames.service.repository.CreditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for querying profession/job data from movie credits
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfessionService {

    private final CreditRepository creditRepository;

    /**
     * Get all professions with summary statistics
     */
    public List<ProfessionSummaryDTO> getAllProfessions() {
        List<Object[]> results = creditRepository.findAllProfessionsWithCounts();

        return results.stream()
            .map(this::mapToProfessionSummary)
            .collect(Collectors.toList());
    }

    /**
     * Get profession breakdown for a specific first name
     */
    public List<ProfessionCountDTO> getProfessionsByFirstName(String firstName) {
        List<Object[]> results = creditRepository.findProfessionCountsByFirstName(firstName);

        // Calculate total for percentages
        long total = results.stream()
            .mapToLong(r -> ((Long) r[1]).longValue())
            .sum();

        return results.stream()
            .map(r -> mapToProfessionCount(r, total))
            .collect(Collectors.toList());
    }

    /**
     * Get count of unique names for a profession
     */
    public Long getUniqueNameCountByProfession(String profession) {
        return creditRepository.countUniqueNamesByProfession(profession);
    }

    private ProfessionSummaryDTO mapToProfessionSummary(Object[] result) {
        String job = (String) result[0];
        String department = (String) result[1];
        Long totalPeople = ((Number) result[2]).longValue();

        // Determine popularity based on people count
        String popularity;
        if (totalPeople > 1000) {
            popularity = "high";
        } else if (totalPeople > 100) {
            popularity = "medium";
        } else {
            popularity = "low";
        }

        return ProfessionSummaryDTO.builder()
            .name(job)
            .department(department)
            .totalPeople(totalPeople.intValue())
            .popularity(popularity)
            .build();
    }

    private ProfessionCountDTO mapToProfessionCount(Object[] result, long total) {
        String job = (String) result[0];
        Long count = ((Number) result[1]).longValue();
        Double percentage = total > 0 ? (count.doubleValue() / total) * 100.0 : 0.0;

        return ProfessionCountDTO.builder()
            .name(job)
            .count(count.intValue())
            .percentage(Math.round(percentage * 10) / 10.0)  // Round to 1 decimal
            .build();
    }
}
