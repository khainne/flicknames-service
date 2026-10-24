package com.flicknames.service.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps ScreenCharacter.reviewStatus to and from its column, tolerating values written by
 * earlier migrations that no longer exist in the enum. A plain @Enumerated mapping throws on
 * such rows, which broke every query that loaded the affected characters.
 */
@Slf4j
@Converter
public class ReviewStatusConverter implements AttributeConverter<ScreenCharacter.ReviewStatus, String> {

    private static final Map<String, ScreenCharacter.ReviewStatus> LEGACY_VALUES = Map.of(
        "FLAGGED_BAD_PARSE", ScreenCharacter.ReviewStatus.INCORRECT_PARSE
    );

    private static final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    @Override
    public String convertToDatabaseColumn(ScreenCharacter.ReviewStatus status) {
        return status == null ? null : status.name();
    }

    @Override
    public ScreenCharacter.ReviewStatus convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ScreenCharacter.ReviewStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            ScreenCharacter.ReviewStatus legacy = LEGACY_VALUES.get(value);
            if (legacy != null) {
                return legacy;
            }
            if (warnedUnknown.add(value)) {
                log.warn("Unknown character review_status '{}' in database; treating it as FLAGGED", value);
            }
            return ScreenCharacter.ReviewStatus.FLAGGED;
        }
    }
}
