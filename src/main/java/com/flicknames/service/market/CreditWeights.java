package com.flicknames.service.market;

import com.flicknames.service.entity.Credit;

import java.util.Set;

/**
 * How much one credit counts toward a name's index: top-billed cast (and the characters they
 * play) x3, other cast x1, key crew (director, writers, composer) x1, everyone else in the crew x0.1.
 */
public final class CreditWeights {

    static final double TOP_BILLED = 3.0;
    static final double CAST = 1.0;
    static final double KEY_CREW = 1.0;
    static final double OTHER_CREW = 0.1;
    static final int TOP_BILLED_MAX_ORDER = 3;

    static final Set<String> KEY_CREW_JOBS = Set.of(
        "Director", "Writer", "Screenplay", "Story", "Novel", "Author", "Original Music Composer");

    private CreditWeights() {
    }

    public static double weight(Credit.RoleType roleType, String job, Integer castOrder) {
        if (roleType == Credit.RoleType.CAST) {
            return castOrder != null && castOrder <= TOP_BILLED_MAX_ORDER ? TOP_BILLED : CAST;
        }
        return job != null && KEY_CREW_JOBS.contains(job) ? KEY_CREW : OTHER_CREW;
    }
}
