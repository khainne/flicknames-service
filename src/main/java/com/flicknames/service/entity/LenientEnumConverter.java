package com.flicknames.service.entity;

import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an enum to and from its name in a column, tolerating values written by earlier
 * migrations that no longer exist in the enum. A plain @Enumerated(STRING) mapping throws on
 * such rows, which breaks every query that loads them. Known legacy values map to their
 * replacement; anything else maps to the fallback with a one-time warning per value.
 */
@Slf4j
public abstract class LenientEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;
    private final E fallback;
    private final Map<String, E> legacyValues;
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    protected LenientEnumConverter(Class<E> type, E fallback, Map<String, E> legacyValues) {
        this.type = type;
        this.fallback = fallback;
        this.legacyValues = legacyValues;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name();
    }

    @Override
    public E convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            E legacy = legacyValues.get(value);
            if (legacy != null) {
                return legacy;
            }
            if (warnedUnknown.add(value)) {
                log.warn("Unknown {} '{}' in database; treating it as {}", type.getSimpleName(), value, fallback);
            }
            return fallback;
        }
    }
}
