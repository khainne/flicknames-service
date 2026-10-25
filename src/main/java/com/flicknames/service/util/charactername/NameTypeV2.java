package com.flicknames.service.util.charactername;

/**
 * Classification produced by {@link CharacterNameParserV2}.
 *
 * <p>Deliberately separate from {@code ScreenCharacter.NameType}: v2 runs side by side with the
 * current parser and does not touch the stored enum or the database schema.
 */
public enum NameTypeV2 {
    /** Given name plus surname: "Peter Parker". */
    PERSONAL_NAME,
    /** A single word that reads as a name: "Boudica", "Sean". */
    SINGLE_NAME,
    /** Given name behind an age or kinship prefix: "Young Nancy", "Auntie Rani". */
    AGE_OR_KIN_VARIANT,
    /** Given name behind a title: "Princess Leia", "Dr. Roshan Manik". */
    TITLED_NAME,
    /** Given name followed by a description: "Fred the Janitor". */
    NAME_WITH_DESCRIPTION,
    /** Title plus surname only: "Officer Daniels". No first name. */
    TITLE_SURNAME,
    /** The performer plays themselves: "Self - Host", "Himself". */
    SELF,
    /** Numbered extra: "Cop #2", "Woman at the Bar 4". */
    NUMBERED_ROLE,
    /** Defined by another character: "Erika's Son". */
    RELATIONAL_ROLE,
    /** Occupation, description, group or creature: "Swamp Tour Guide", "The Bride", "Fremen". */
    ROLE_DESCRIPTION,
    /** Nothing usable left after removing annotations. */
    EMPTY
}
