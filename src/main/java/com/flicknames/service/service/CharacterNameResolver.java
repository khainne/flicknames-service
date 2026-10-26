package com.flicknames.service.service;

import com.flicknames.service.entity.CharacterNameLabel;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.service.CharacterNameLabelService.NameParts;
import com.flicknames.service.util.charactername.CharacterNameParseResult;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Sets the name parts of a newly collected character: a decisive human label for the exact string
 * wins (and marks the row verified, as applying labels does); otherwise {@link CharacterNameParserV2},
 * mapped to the stored fields the same way as {@link CharacterNameReparseService}.
 */
@Component
@RequiredArgsConstructor
public class CharacterNameResolver {

    private final CharacterNameLabelService labelService;
    private final CharacterNameParserV2 parser = new CharacterNameParserV2();

    public void resolve(ScreenCharacter character) {
        String fullName = character.getFullName();
        var label = labelService.lookup(fullName);
        if (label.isPresent()) {
            NameParts parts = CharacterNameLabelService.planFor(
                ScreenCharacter.builder().fullName(fullName).nameType(NameType.UNKNOWN).build(),
                CharacterNameLabel.builder().fullName(fullName).verdict(label.get().verdict())
                    .firstName(label.get().firstName()).build());
            character.setFirstName(parts.firstName());
            character.setLastName(parts.lastName());
            character.setNameType(parts.nameType());
            character.setParserVersion(CharacterNameReparseService.LABEL_VERSION);
            character.setParseConfidence(1.0);
            character.setManuallyVerified(true);
            character.setReviewStatus(label.get().isName()
                ? ScreenCharacter.ReviewStatus.APPROVED : ScreenCharacter.ReviewStatus.NO_FIRST_NAME);
            character.setReviewedAt(LocalDateTime.now());
            return;
        }
        CharacterNameParseResult result = parser.parse(fullName);
        character.setFirstName(result.firstName());
        character.setLastName(CharacterNameReparseService.lastName(result));
        character.setNameType(CharacterNameReparseService.storedType(result));
        character.setParserVersion(result.parserVersion());
        character.setParseConfidence(result.confidence());
    }
}
