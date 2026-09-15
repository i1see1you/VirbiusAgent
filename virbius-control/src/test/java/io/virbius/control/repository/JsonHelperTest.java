package io.virbius.control.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonHelperTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void toJsonEncodesInstantAsIsoString() {
        String json = JsonHelper.toJson(Map.of("t", T));
        assertTrue(json.contains("2026-01-01T00:00:00Z"), json);
        assertFalse(json.contains("\"t\":1"), json);
    }

    @Test
    void toJsonEncodesRuleDetailWithEffectiveTo() {
        RuleRevision rule = new RuleRevision(
                "default",
                "r1",
                2,
                "default",
                "cloud",
                "groovy",
                "X",
                0,
                "allow",
                Map.of(),
                Map.of("k", "v"),
                "full",
                null,
                T,
                T,
                T,
                false,
                null);
        Map<String, Object> detail = RuleResponseMapper.toDetail(rule);
        assertEquals("2026-01-01T00:00:00Z", detail.get("effective_to"));
        String json = JsonHelper.toJson(List.of(detail));
        assertTrue(json.contains("2026-01-01T00:00:00Z"), json);
    }
}
