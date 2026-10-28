package com.flicknames.service.service;

import com.flicknames.service.entity.DiminutiveUsage.Usage;
import com.flicknames.service.service.DiminutiveUsageModel.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiminutiveUsageModelTest {

    @Test
    void keepsKnownIdsOnceAndDropsUnknownAnswers() {
        List<Pair> pairs = List.of(new Pair(1, "Nick", "Nicholas"), new Pair(2, "Liam", "William"));
        DiminutiveUsageModel.Result result = DiminutiveUsageModel.parse("""
            {"results": [{"id": 1, "usage": "nickname"}, {"id": 2, "usage": "independent"},
                         {"id": 2, "usage": "nickname"}, {"id": 9, "usage": "nickname"}, {"id": 1, "usage": "maybe"}]}""",
            pairs, 10, 5);
        assertThat(result.usages()).isEqualTo(Map.of(1L, Usage.NICKNAME, 2L, Usage.INDEPENDENT));
        assertThat(DiminutiveUsageModel.parse("not json", pairs, 0, 0).error()).startsWith("unreadable JSON");
    }
}
