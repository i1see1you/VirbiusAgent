package io.virbius.engine.api;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B3: maps an output-review verdict to a disposal action
 * ({@code reject | omit | redact | warn}).
 *
 * <p>Overrides come from {@code virbius.output-review.action-map}
 * (e.g. {@code "PII=redact,PROMPT_INJECTION=omit"}); keyword fallback applies
 * when a reason code is not listed, and {@code reject} is the safe default for
 * any blocked verdict.
 */
@Component
public class OutputActionResolver {

    public static final String REJECT = "reject";
    public static final String OMIT = "omit";
    public static final String REDACT = "redact";
    public static final String WARN = "warn";

    private final Map<String, String> overrides = new HashMap<>();

    public OutputActionResolver(
            @Value("${virbius.output-review.action-map:PII=redact,PROMPT_INJECTION=omit,OUTPUT_CREDENTIAL_LEAK=redact}") String mapping) {
        if (mapping != null) {
            for (String pair : mapping.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                    overrides.put(kv[0].trim().toUpperCase(), kv[1].trim().toLowerCase());
                }
            }
        }
    }

    /**
     * @param reasonCode the verdict's reason code (may be null)
     * @param blocked    whether the verdict blocks the content
     * @return one of reject / omit / redact / warn
     */
    public String resolve(String reasonCode, boolean blocked) {
        if (!blocked) {
            return WARN;
        }
        String rc = reasonCode == null ? "" : reasonCode.toUpperCase();
        if (overrides.containsKey(rc)) {
            return overrides.get(rc);
        }
        if (rc.contains("PII") || rc.contains("CREDENTIAL") || rc.contains("SENSITIVE")) {
            return REDACT;
        }
        if (rc.contains("INJECTION") || rc.contains("JAILBREAK")) {
            return OMIT;
        }
        return REJECT;
    }
}
