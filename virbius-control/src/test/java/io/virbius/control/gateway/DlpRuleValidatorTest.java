package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DlpRuleValidatorTest {

    @Test
    void acceptsAllowIntentAndPhoneEntity() {
        UpsertRuleRequest req = dlpReq(Map.of("entity_type", "phone_cn"));
        assertDoesNotThrow(() -> DlpRuleValidator.validateUpsert(req));
    }

    @Test
    void rejectsNonAllowIntent() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "dlp_phone",
                "poc-default",
                "edge",
                "dlp-dsl",
                "DLP_PHONE",
                0,
                "deny",
                Map.of(),
                Map.of("entity_type", "phone_cn"),
                "simple",
                null,
                null,
                null);
        assertThrows(IllegalArgumentException.class, () -> DlpRuleValidator.validateUpsert(req));
    }

    @Test
    void customRegexRequiresPattern() {
        UpsertRuleRequest req = dlpReq(Map.of("entity_type", "custom_regex"));
        assertThrows(IllegalArgumentException.class, () -> DlpRuleValidator.validateUpsert(req));
    }

    @Test
    void customRegexValidatesPatternSyntax() {
        UpsertRuleRequest req = dlpReq(Map.of("entity_type", "custom_regex", "pattern", "[unclosed"));
        assertThrows(IllegalArgumentException.class, () -> DlpRuleValidator.validateUpsert(req));
    }

    private static UpsertRuleRequest dlpReq(Map<String, Object> body) {
        return new UpsertRuleRequest(
                "dlp_phone",
                "poc-default",
                "edge",
                "dlp-dsl",
                "DLP_PHONE",
                0,
                "allow",
                Map.of(),
                body,
                "simple",
                null,
                null,
                null);
    }
}
