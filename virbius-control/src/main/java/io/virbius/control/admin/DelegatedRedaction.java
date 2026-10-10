package io.virbius.control.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.response.ApiResult;

/**
 * Request-scoped redaction for the delegated credential plane (v2.5-min).
 *
 * <p>Controllers register the submitted value BEFORE any processing
 * (validation is manual — never bean-validation — so binding cannot fire
 * first). This advice redacts every registered variant from log output and
 * returns generic messages to clients: no request content is ever echoed.
 */
final class DelegatedRedaction {

    private static final Logger log = LoggerFactory.getLogger(DelegatedRedaction.class);
    static final String REDACTED = "[REDACTED]";

    private static final ThreadLocal<List<String>> NEEDLES =
            ThreadLocal.withInitial(ArrayList::new);

    private DelegatedRedaction() {}

    /** Register the submitted value plus base64/urlencode variants. */
    static void register(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        List<String> list = NEEDLES.get();
        list.add(value);
        list.add(Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)));
        list.add(Base64.getUrlEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)));
        list.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    static String scrub(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String needle : NEEDLES.get()) {
            if (!needle.isEmpty()) {
                out = out.replace(needle, REDACTED);
            }
        }
        return out;
    }

    static void clear() {
        NEEDLES.remove();
    }
}

/** Advice scoped to the delegated controller: scrub logs, echo nothing. */
@RestControllerAdvice(assignableTypes = DelegatedCredentialAdminController.class)
class DelegatedCredentialAdminControllerAdvice {

    private static final Logger log =
            LoggerFactory.getLogger(DelegatedCredentialAdminControllerAdvice.class);

    @ExceptionHandler(BusinessException.class)
    public Object business(BusinessException e) {
        try {
            // Business codes are safe to echo; detail goes to the (scrubbed) log.
            log.warn("delegated credential request rejected: {}",
                    DelegatedRedaction.scrub(e.getMessage()));
            return ApiResult.error(e.getCode(), e.getMessage());
        } finally {
            DelegatedRedaction.clear();
        }
    }

    @ExceptionHandler(Exception.class)
    public Object generic(Exception e) {
        try {
            // Full stack trace is logged with the scrubbed message only; the
            // client gets a generic body — request content is never echoed.
            log.error("delegated credential request failed (class={}): {}",
                    e.getClass().getSimpleName(),
                    DelegatedRedaction.scrub(String.valueOf(e.getMessage())),
                    e);
            return ResponseEntity.status(500)
                    .body(ApiResult.error(500, "delegated_credential_request_failed"));
        } finally {
            DelegatedRedaction.clear();
        }
    }
}
