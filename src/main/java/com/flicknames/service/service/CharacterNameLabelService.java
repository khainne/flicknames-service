package com.flicknames.service.service;

import com.flicknames.service.entity.CharacterNameLabel;
import com.flicknames.service.entity.CharacterNameLabel.Verdict;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.repository.CharacterNameLabelRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Human labels for character first names.
 *
 * <p>Two jobs: (1) the override hook - {@link #lookup(String)} / {@link #lookupAll(Collection)}
 * / {@link #decisiveLabels()} let any parsing pipeline ask "has a human already decided this
 * string?" before trusting the parser; (2) {@link #apply(boolean, String)} writes labels into
 * the characters table on explicit request (dry run by default).
 *
 * <p>Saving or deleting a label never touches the characters table by itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterNameLabelService {

    private static final int APPLY_CHUNK = 500;
    private static final int APPLY_SAMPLE_LIMIT = 50;
    private static final Set<NameType> NAME_TYPES = EnumSet.of(NameType.STANDARD, NameType.SINGLE_NAME);

    private final CharacterNameLabelRepository labelRepository;
    private final ScreenCharacterRepository characterRepository;
    private final EntityManager entityManager;
    private final CharacterNameLabelQueueService queueService;

    /** What a human decided for a raw character string. Only NAME and NOT_A_NAME are decisions. */
    public record LabelResult(Verdict verdict, String firstName) {
        public boolean isName() {
            return verdict == Verdict.NAME;
        }
    }

    public record LabelRequest(String fullName, Verdict verdict, String firstName, String note, String labeledBy) {}

    public record LabelDTO(Long id, String fullName, String firstName, Verdict verdict, String note,
                           String labeledBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                           LocalDateTime appliedAt) {
        static LabelDTO of(CharacterNameLabel l) {
            return new LabelDTO(l.getId(), l.getFullName(), l.getFirstName(), l.getVerdict(), l.getNote(),
                l.getLabeledBy(), l.getCreatedAt(), l.getUpdatedAt(), l.getAppliedAt());
        }
    }

    public record SaveResult(LabelDTO label, boolean created, long matchingCharacters) {}

    public record LabelStats(long total, Map<Verdict, Long> byVerdict, LocalDateTime since,
                             long sinceTotal, Map<Verdict, Long> sinceByVerdict, long unapplied) {}

    public record NameParts(String firstName, String lastName, NameType nameType) {}

    public record CharacterChange(Long characterId, String fullName, NameParts before, NameParts after) {}

    public record ApplyReport(boolean dryRun, int labelsConsidered, int labelsWithoutCharacters,
                              int charactersMatched, int charactersChanged, int charactersVerifiedOnly,
                              List<CharacterChange> samples) {}

    public static class LabelValidationException extends RuntimeException {
        public LabelValidationException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- override hook

    /**
     * The human decision for this exact raw string, if any. SKIPPED labels are not decisions and
     * return empty, so callers fall back to the parser.
     */
    @Transactional(readOnly = true)
    public Optional<LabelResult> lookup(String fullName) {
        if (fullName == null) {
            return Optional.empty();
        }
        return labelRepository.findByFullName(fullName.trim()).flatMap(CharacterNameLabelService::toResult);
    }

    /** Batch form of {@link #lookup(String)}: decisions keyed by full name (absent = no decision). */
    @Transactional(readOnly = true)
    public Map<String, LabelResult> lookupAll(Collection<String> fullNames) {
        Map<String, LabelResult> results = new HashMap<>();
        List<String> names = fullNames.stream().filter(Objects::nonNull).map(String::trim).distinct().toList();
        for (int i = 0; i < names.size(); i += APPLY_CHUNK) {
            for (CharacterNameLabel label : labelRepository.findByFullNameIn(names.subList(i, Math.min(names.size(), i + APPLY_CHUNK)))) {
                toResult(label).ifPresent(r -> results.put(label.getFullName(), r));
            }
        }
        return results;
    }

    /** Every decisive label, for a bulk re-parse that wants one in-memory map instead of per-row lookups. */
    @Transactional(readOnly = true)
    public Map<String, LabelResult> decisiveLabels() {
        return labelRepository.findByVerdictInOrderByIdAsc(EnumSet.of(Verdict.NAME, Verdict.NOT_A_NAME)).stream()
            .collect(Collectors.toMap(CharacterNameLabel::getFullName,
                l -> new LabelResult(l.getVerdict(), l.getFirstName()), (a, b) -> a, HashMap::new));
    }

    private static Optional<LabelResult> toResult(CharacterNameLabel label) {
        return switch (label.getVerdict()) {
            case NAME, NOT_A_NAME -> Optional.of(new LabelResult(label.getVerdict(), label.getFirstName()));
            default -> Optional.empty();
        };
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public SaveResult save(LabelRequest request) {
        LabelRequest clean = validate(request);
        Optional<CharacterNameLabel> existing = labelRepository.findByFullName(clean.fullName());
        CharacterNameLabel label = existing.orElseGet(() -> CharacterNameLabel.builder().fullName(clean.fullName()).build());
        if (label.getVerdict() != clean.verdict() || !Objects.equals(label.getFirstName(), clean.firstName())) {
            label.setAppliedAt(null);
        }
        label.setVerdict(clean.verdict());
        label.setFirstName(clean.firstName());
        label.setNote(clean.note());
        label.setLabeledBy(clean.labeledBy());
        label = labelRepository.saveAndFlush(label);
        return new SaveResult(LabelDTO.of(label), existing.isEmpty(), characterRepository.countByFullName(label.getFullName()));
    }

    @Transactional
    public List<SaveResult> saveAll(List<LabelRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new LabelValidationException("labels must be a non-empty array");
        }
        if (requests.size() > 1000) {
            throw new LabelValidationException("at most 1000 labels per request");
        }
        // Validate everything first so a bad entry rejects the whole batch.
        requests.forEach(this::validate);
        return requests.stream().map(this::save).toList();
    }

    /** Removes the label. Characters already updated by {@link #apply} keep their values. */
    @Transactional
    public Optional<LabelDTO> delete(String fullName) {
        if (fullName == null) {
            return Optional.empty();
        }
        Optional<CharacterNameLabel> label = labelRepository.findByFullName(fullName.trim());
        label.ifPresent(labelRepository::delete);
        return label.map(LabelDTO::of);
    }

    @Transactional
    public Optional<LabelDTO> delete(Long id) {
        Optional<CharacterNameLabel> label = labelRepository.findById(id);
        label.ifPresent(labelRepository::delete);
        return label.map(LabelDTO::of);
    }

    LabelRequest validate(LabelRequest request) {
        if (request == null) {
            throw new LabelValidationException("request body is required");
        }
        String fullName = trimToNull(request.fullName());
        if (fullName == null) {
            throw new LabelValidationException("fullName is required");
        }
        if (fullName.length() > CharacterNameLabel.MAX_FULL_NAME) {
            throw new LabelValidationException("fullName is longer than " + CharacterNameLabel.MAX_FULL_NAME + " characters");
        }
        if (request.verdict() == null) {
            throw new LabelValidationException("verdict is required (NAME, NOT_A_NAME or SKIPPED)");
        }
        String firstName = null;
        if (request.verdict() == Verdict.NAME) {
            firstName = trimToNull(request.firstName());
            if (firstName == null) {
                throw new LabelValidationException("firstName is required when verdict is NAME");
            }
            firstName = firstName.replaceAll("\\s+", " ");
            if (firstName.length() > CharacterNameLabel.MAX_FIRST_NAME) {
                throw new LabelValidationException("firstName is longer than " + CharacterNameLabel.MAX_FIRST_NAME + " characters");
            }
        }
        String note = trimToNull(request.note());
        if (note != null && note.length() > CharacterNameLabel.MAX_NOTE) {
            throw new LabelValidationException("note is longer than " + CharacterNameLabel.MAX_NOTE + " characters");
        }
        String labeledBy = trimToNull(request.labeledBy());
        if (labeledBy != null && labeledBy.length() > CharacterNameLabel.MAX_LABELED_BY) {
            throw new LabelValidationException("labeledBy is longer than " + CharacterNameLabel.MAX_LABELED_BY + " characters");
        }
        return new LabelRequest(fullName, request.verdict(), firstName, note, labeledBy);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<LabelDTO> list(Verdict verdict, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<CharacterNameLabel> labels = verdict == null
            ? labelRepository.findAll(pageable)
            : labelRepository.findByVerdict(verdict, pageable);
        return labels.map(LabelDTO::of);
    }

    @Transactional(readOnly = true)
    public Optional<LabelDTO> get(String fullName) {
        return fullName == null ? Optional.empty() : labelRepository.findByFullName(fullName.trim()).map(LabelDTO::of);
    }

    @Transactional(readOnly = true)
    public LabelStats stats(LocalDateTime since) {
        Map<Verdict, Long> all = verdictCounts(labelRepository.countByVerdict());
        Map<Verdict, Long> recent = verdictCounts(labelRepository.countByVerdictSince(since));
        return new LabelStats(sum(all), all, since, sum(recent), recent,
            labelRepository.countByVerdictNotAndAppliedAtIsNull(Verdict.SKIPPED));
    }

    private static Map<Verdict, Long> verdictCounts(List<Object[]> rows) {
        Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) {
            counts.put(v, 0L);
        }
        for (Object[] row : rows) {
            counts.merge((Verdict) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }

    private static long sum(Map<Verdict, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Decisive labels (and SKIPPED ones too if asked), oldest first, for the golden-set export. */
    @Transactional(readOnly = true)
    public List<LabelDTO> exportable(boolean includeSkipped) {
        Set<Verdict> verdicts = includeSkipped ? EnumSet.allOf(Verdict.class) : EnumSet.of(Verdict.NAME, Verdict.NOT_A_NAME);
        return labelRepository.findByVerdictInOrderByIdAsc(verdicts).stream().map(LabelDTO::of).toList();
    }

    // ---------------------------------------------------------------- apply to characters

    /**
     * Writes decisive labels into characters with the same full_name: first_name (and name_type,
     * and last_name where it only duplicated the first name or the string is not a name), plus
     * manually_verified so automatic migrations leave the row alone. Idempotent; only rows that
     * differ are written. With {@code dryRun} nothing is written and the report says what would change.
     *
     * @param onlyFullName apply just this one label (null = all labels)
     */
    @Transactional
    public ApplyReport apply(boolean dryRun, String onlyFullName) {
        List<CharacterNameLabel> labels;
        if (onlyFullName != null && !onlyFullName.isBlank()) {
            labels = labelRepository.findByFullName(onlyFullName.trim())
                .filter(l -> l.getVerdict() != Verdict.SKIPPED)
                .map(List::of).orElse(List.of());
        } else {
            labels = labelRepository.findByVerdictInOrderByIdAsc(EnumSet.of(Verdict.NAME, Verdict.NOT_A_NAME));
        }

        int matched = 0;
        int changed = 0;
        int verifiedOnly = 0;
        Set<String> labelsWithCharacters = new HashSet<>();
        List<CharacterChange> samples = new ArrayList<>();
        List<Long> appliedIds = labels.stream().map(CharacterNameLabel::getId).toList();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < labels.size(); i += APPLY_CHUNK) {
            Map<String, CharacterNameLabel> byName = labels.subList(i, Math.min(labels.size(), i + APPLY_CHUNK)).stream()
                .collect(Collectors.toMap(CharacterNameLabel::getFullName, l -> l));
            for (ScreenCharacter character : characterRepository.findByFullNameIn(byName.keySet())) {
                CharacterNameLabel label = byName.get(character.getFullName());
                if (label == null) {
                    continue; // collation differences; exact match only
                }
                matched++;
                labelsWithCharacters.add(label.getFullName());
                NameParts before = new NameParts(character.getFirstName(), character.getLastName(), character.getNameType());
                NameParts after = planFor(character, label);
                boolean partsChange = !before.equals(after);
                if (!partsChange && character.isManuallyVerified()) {
                    continue;
                }
                if (partsChange) {
                    changed++;
                    if (samples.size() < APPLY_SAMPLE_LIMIT) {
                        samples.add(new CharacterChange(character.getId(), character.getFullName(), before, after));
                    }
                } else {
                    verifiedOnly++;
                }
                if (!dryRun) {
                    character.setFirstName(after.firstName());
                    character.setLastName(after.lastName());
                    character.setNameType(after.nameType());
                    character.setManuallyVerified(true);
                    character.setReviewStatus(label.getVerdict() == Verdict.NAME
                        ? ScreenCharacter.ReviewStatus.APPROVED : ScreenCharacter.ReviewStatus.NO_FIRST_NAME);
                    character.setReviewedAt(now);
                }
            }
            if (!dryRun) {
                entityManager.flush();
            }
            entityManager.clear();
        }

        if (!dryRun && !appliedIds.isEmpty()) {
            for (int i = 0; i < appliedIds.size(); i += APPLY_CHUNK) {
                entityManager.createQuery("UPDATE CharacterNameLabel l SET l.appliedAt = :now WHERE l.id IN :ids")
                    .setParameter("now", now)
                    .setParameter("ids", appliedIds.subList(i, Math.min(appliedIds.size(), i + APPLY_CHUNK)))
                    .executeUpdate();
            }
            queueService.invalidate(); // queue filters on stored first_name / name_type
            log.info("Applied {} name labels: {} characters changed, {} only marked verified", labels.size(), changed, verifiedOnly);
        }

        return new ApplyReport(dryRun, labels.size(), labels.size() - labelsWithCharacters.size(),
            matched, changed, verifiedOnly, samples);
    }

    /** The name parts a character should have under a decisive label. */
    static NameParts planFor(ScreenCharacter character, CharacterNameLabel label) {
        NameType type = character.getNameType();
        String last = character.getLastName();
        if (label.getVerdict() == Verdict.NAME) {
            String first = label.getFirstName();
            if (last != null && last.equalsIgnoreCase(first)) {
                last = null; // "Young Anna" parsed as Young / Anna
            }
            if (type == null || !NAME_TYPES.contains(type)) {
                type = character.getFullName().equalsIgnoreCase(first) ? NameType.SINGLE_NAME : NameType.STANDARD;
            }
            return new NameParts(first, last, type);
        }
        if (type == null || type == NameType.UNKNOWN || NAME_TYPES.contains(type)) {
            // It was treated as a name; it is a role/description. Any parsed surname came from the same misparse.
            return new NameParts(null, null, NameType.ROLE_DESCRIPTION);
        }
        // TITLE_SURNAME / ROLE_DESCRIPTION / NUMBERED_ROLE already mean "no first name"; keep the surname.
        return new NameParts(null, last, type);
    }
}
