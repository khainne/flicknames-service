package com.flicknames.service.entity;

import jakarta.persistence.Converter;

import java.util.Map;

/** ScreenCharacter.nameType: SSA_UNVERIFIED came from an earlier, since-removed migration. */
@Converter
public class NameTypeConverter extends LenientEnumConverter<ScreenCharacter.NameType> {

    public NameTypeConverter() {
        super(ScreenCharacter.NameType.class, ScreenCharacter.NameType.UNKNOWN, Map.of());
    }
}
