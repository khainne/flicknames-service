package com.flicknames.service.util.charactername;

/** Machine-readable reasons attached to every {@link CharacterNameParseResult}. */
public enum ParseReason {
    // normalisation and annotation extraction
    EMPTY_INPUT,
    UNIFORM_CASE_RECASED,
    ANNOTATION_REMOVED,
    AS_CREDIT_REMOVED,
    DASH_DESCRIPTION_REMOVED,
    COMMA_DESCRIPTION_REMOVED,
    NICKNAME_EXTRACTED,
    NICKNAME_ONLY,
    // multi-role credits
    MULTI_ROLE,
    SEGMENTS_DISAGREE,
    // segment classes that yield no first name
    SELF_CREDIT,
    NUMBERED,
    POSSESSIVE,
    ARTICLE,
    ACRONYM,
    LOWERCASE_DESCRIPTOR,
    ROLE_HEAD_NOUN,
    ROLE_WORD,
    STOPWORD,
    NAME_PARTICLE,
    SURNAME_ONLY,
    DEMONYM,
    ADJECTIVE_DESCRIPTOR,
    ENGLISH_WORD,
    COMPOUND_ROLE_WORD,
    SURNAME_TITLE,
    TITLE_ONLY,
    INITIAL_ONLY,
    MALFORMED_TOKEN,
    NON_LATIN_SCRIPT,
    // segment classes that yield a first name
    AGE_KIN_PREFIX_STRIPPED,
    GIVEN_TITLE_STRIPPED,
    ADJECTIVE_STRIPPED,
    ROLE_PREFIX_STRIPPED,
    NAME_FROM_NICKNAME,
    TITLE_BEFORE_GIVEN_NAME,
    AMBIGUOUS_TITLE_OR_NAME,
    DESCRIPTION_AFTER_NAME,
    SURNAME_FIRST,
    // lexicon validation of the chosen token
    LEXICON_COMMON,
    LEXICON_KNOWN,
    LEXICON_RARE,
    LEXICON_UNKNOWN,
    CASING_NORMALISED
}
