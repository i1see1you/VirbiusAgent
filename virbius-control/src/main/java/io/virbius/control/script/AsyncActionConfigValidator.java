package io.virbius.control.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates {@code async_action_config} for async rules at write time (upsert) and defensively
 * at publish time. Format validation only — outbound webhook hosts are enforced at runtime by
 * the engine's {@code virbius.async-action.webhook.allowed-hosts} (empty = allow all), so a
 * config that passes here may still be skipped by the engine; that is documented behavior.
 *
 * <p>Legacy {@code redis_stream} is rejected outright (the project has no shipped release and
 * no stored async rules); use {@code kafka}.
 */
public final class AsyncActionConfigValidator {

    /** S1: bound template/config size so giant blobs never reach the store. */
    public static final int MAX_CONFIG_CHARS = 8192;
    public static final int MAX_TOPIC_CHARS = 249;
    public static final int MAX_URL_CHARS = 2048;
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");

    private AsyncActionConfigValidator() {}

    /** @throws IllegalArgumentException on any validation error (mapped to HTTP 400). */
    public static void validate(String config, ObjectMapper mapper) {
        if (config == null || config.isBlank()) {
            return; // async without config = log-only hit (existing contract, unchanged)
        }
        if (config.length() > MAX_CONFIG_CHARS) {
            throw new IllegalArgumentException(
                    "async_action_config must be at most " + MAX_CONFIG_CHARS + " characters");
        }
        JsonNode root;
        try {
            root = mapper.readTree(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("async_action_config must be valid JSON");
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("async_action_config must be a JSON object");
        }
        List<String> errors = new ArrayList<>();
        String type = text(root.get("type"));
        if (type.isEmpty()) {
            errors.add("type is required (webhook|kafka)");
        } else if ("redis_stream".equals(type)) {
            errors.add("type redis_stream is removed; use kafka");
        } else if (!"webhook".equals(type) && !"kafka".equals(type)) {
            errors.add("type must be webhook or kafka");
        }

        if ("webhook".equals(type)) {
            String url = text(root.get("url"));
            if (url.isEmpty()) {
                errors.add("webhook url is required");
            } else {
                if (url.length() > MAX_URL_CHARS) {
                    errors.add("webhook url must be at most " + MAX_URL_CHARS + " characters");
                }
                String lower = url.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    errors.add("webhook url scheme must be http or https");
                }
            }
            JsonNode body = root.get("body");
            if (body != null && !body.isNull() && !body.isObject()) {
                errors.add("webhook body must be an object");
            }
        }
        if ("kafka".equals(type)) {
            String topic = text(root.get("topic"));
            if (!topic.isEmpty() && !TOPIC.matcher(topic).matches()) {
                errors.add("kafka topic must match [A-Za-z0-9._-]{1,249}");
            }
            JsonNode message = root.get("message");
            if (message != null && !message.isNull() && !message.isObject()) {
                errors.add("kafka message must be an object");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid async_action_config: " + String.join("; ", errors));
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }
}
