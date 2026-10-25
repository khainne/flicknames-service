package com.flicknames.service.entity;

import jakarta.persistence.Converter;

import java.util.Map;

/** ScreenCharacter.reviewStatus: FLAGGED_BAD_PARSE came from an earlier, since-removed migration. */
@Converter
public class ReviewStatusConverter extends LenientEnumConverter<ScreenCharacter.ReviewStatus> {

    public ReviewStatusConverter() {
        super(ScreenCharacter.ReviewStatus.class, ScreenCharacter.ReviewStatus.FLAGGED,
            Map.of("FLAGGED_BAD_PARSE", ScreenCharacter.ReviewStatus.INCORRECT_PARSE));
    }
}
