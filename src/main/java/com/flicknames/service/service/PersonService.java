package com.flicknames.service.service;

import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.dto.PersonStatsDTO;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Person;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonService {

    private final PersonRepository personRepository;
    private final CreditRepository creditRepository;

    public PersonDTO getPersonById(Long id) {
        Person person = personRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Person not found with id: " + id));
        return mapToDTO(person);
    }

    public Page<PersonDTO> searchPeople(String name, Pageable pageable) {
        return personRepository.findByFullNameContainingIgnoreCase(name, pageable)
            .map(this::mapToDTO);
    }

    public PersonStatsDTO getPersonStats(Long id) {
        Person person = personRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Person not found with id: " + id));

        List<Credit> credits = creditRepository.findByPersonId(id);

        BigDecimal totalBoxOffice = credits.stream()
            .map(c -> c.getMovie().getRevenue())
            .filter(revenue -> revenue != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        int totalMovies = (int) credits.stream()
            .map(c -> c.getMovie().getId())
            .distinct()
            .count();

        List<Object[]> jobStats = creditRepository.findJobStatsByPersonId(id);
        Map<String, Integer> jobCounts = new HashMap<>();
        for (Object[] stat : jobStats) {
            jobCounts.put((String) stat[0], ((Long) stat[1]).intValue());
        }

        long castRoles = credits.stream()
            .filter(c -> c.getRoleType() == Credit.RoleType.CAST)
            .count();

        long crewRoles = credits.stream()
            .filter(c -> c.getRoleType() == Credit.RoleType.CREW)
            .count();

        return PersonStatsDTO.builder()
            .person(mapToDTO(person))
            .totalBoxOffice(totalBoxOffice)
            .totalMovies(totalMovies)
            .jobCounts(jobCounts)
            .castRoles((int) castRoles)
            .crewRoles((int) crewRoles)
            .build();
    }

    private PersonDTO mapToDTO(Person person) {
        return PersonDTO.builder()
            .id(person.getId())
            .firstName(person.getFirstName())
            .lastName(person.getLastName())
            .fullName(person.getFullName())
            .gender(person.getGender())
            .profilePath(person.getProfilePath())
            .biography(person.getBiography())
            .build();
    }
}
