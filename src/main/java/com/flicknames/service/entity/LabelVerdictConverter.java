package com.flicknames.service.entity;

import jakarta.persistence.Converter;

import java.util.Map;

/** CharacterNameLabel.verdict: unknown values (from a future or rolled-back version) read as SKIPPED. */
@Converter
public class LabelVerdictConverter extends LenientEnumConverter<CharacterNameLabel.Verdict> {

    public LabelVerdictConverter() {
        super(CharacterNameLabel.Verdict.class, CharacterNameLabel.Verdict.SKIPPED, Map.of());
    }
}
