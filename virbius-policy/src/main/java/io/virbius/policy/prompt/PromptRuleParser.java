package io.virbius.policy.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PromptRuleParser {

    public static final int VERSION = 1;
    public static final int MAX_CRITERION_CHARS = 800;
    public static final int MAX_EXAMPLES = 5;
    public static final int MAX_EXAMPLE_CHARS = 200;

    private PromptRuleParser() {}

    public static PromptRuleSpec parse(Object body, ObjectMapper mapper) {
        if (body == null) {
            throw new PromptRuleValidationException("prompt judge body is required");
        }
        JsonNode node;
        try {
            if (body instanceof JsonNode jsonNode) {
                node = jsonNode;
            } else if (body instanceof String text) {
                node = mapper.readTree(text);
            } else {
                node = mapper.valueToTree(body);
            }
        } catch (JsonProcessingException e) {
            throw new PromptRuleValidationException("prompt judge body must be valid JSON", e);
        }
        return parse(node);
    }

    public static PromptRuleSpec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            throw new PromptRuleValidationException("prompt judge body must be a JSON object");
        }

        List<String> errors = new ArrayList<>();
        int version = intField(root, "version", -1, errors);
        if (version != VERSION) {
            errors.add("version must be " + VERSION);
        }

        String modeText = textField(root, "mode", errors);
        PromptRuleSpec.Mode mode = null;
        if (modeText == null || modeText.isBlank()) {
            errors.add("mode is required");
        } else if (!"judge".equalsIgnoreCase(modeText.trim())) {
            errors.add("mode must be judge; built-in category rules are not judge rules");
        } else {
            mode = PromptRuleSpec.Mode.JUDGE;
        }

        String criterion = textField(root, "criterion", errors);
        if (criterion == null || criterion.isBlank()) {
            errors.add("criterion is required");
        } else {
            criterion = criterion.trim();
            if (criterion.length() > MAX_CRITERION_CHARS) {
                errors.add("criterion must be at most " + MAX_CRITERION_CHARS + " characters");
            }
        }

        List<String> hitExamples = parseExamples(root.get("examples"), errors);
        double threshold = parseThreshold(root.get("threshold"), errors);
        PromptRuleSpec.ErrorPolicy onError = parseErrorPolicy(root.get("on_error"), errors);
        boolean enforcementReady = booleanField(root, "enforcement_ready", false, errors);
        if (onError == PromptRuleSpec.ErrorPolicy.FAIL_CLOSED && !enforcementReady) {
            errors.add("on_error=fail_closed requires enforcement_ready=true");
        }

        if (!errors.isEmpty()) {
            throw new PromptRuleValidationException(
                    "invalid prompt judge rule: " + String.join("; ", errors));
        }
        return new PromptRuleSpec(
                version,
                mode,
                criterion,
                hitExamples,
                threshold,
                onError,
                enforcementReady);
    }

    private static List<String> parseExamples(JsonNode examples, List<String> errors) {
        if (examples == null || examples.isNull()) {
            return List.of();
        }
        if (!examples.isObject()) {
            errors.add("examples must be an object");
            return List.of();
        }
        JsonNode hit = examples.get("hit");
        if (hit == null || hit.isNull()) {
            return List.of();
        }
        if (!hit.isArray()) {
            errors.add("examples.hit must be an array");
            return List.of();
        }
        if (hit.size() > MAX_EXAMPLES) {
            errors.add("examples.hit must contain at most " + MAX_EXAMPLES + " items");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : hit) {
            if (!item.isTextual()) {
                errors.add("examples.hit items must be strings");
                continue;
            }
            String text = item.asText().trim();
            if (text.isEmpty()) {
                errors.add("examples.hit items must not be empty");
            } else if (text.length() > MAX_EXAMPLE_CHARS) {
                errors.add("examples.hit items must be at most " + MAX_EXAMPLE_CHARS + " characters");
            } else {
                out.add(text);
            }
        }
        return out;
    }

    private static double parseThreshold(JsonNode threshold, List<String> errors) {
        if (threshold == null || threshold.isNull() || !threshold.isNumber()) {
            errors.add("threshold must be a number");
            return 0.5;
        }
        double value = threshold.asDouble();
        if (value < 0.05 || value > 0.95) {
            errors.add("threshold must be between 0.05 and 0.95");
            return 0.5;
        }
        return value;
    }

    private static PromptRuleSpec.ErrorPolicy parseErrorPolicy(JsonNode node, List<String> errors) {
        if (node == null || node.isNull()) {
            return PromptRuleSpec.ErrorPolicy.FAIL_OPEN;
        }
        if (!node.isTextual()) {
            errors.add("on_error must be fail_open or fail_closed");
            return PromptRuleSpec.ErrorPolicy.FAIL_OPEN;
        }
        String value = node.asText().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "fail_open" -> PromptRuleSpec.ErrorPolicy.FAIL_OPEN;
            case "fail_closed" -> PromptRuleSpec.ErrorPolicy.FAIL_CLOSED;
            default -> {
                errors.add("on_error must be fail_open or fail_closed");
                yield PromptRuleSpec.ErrorPolicy.FAIL_OPEN;
            }
        };
    }

    private static String textField(JsonNode root, String field, List<String> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(field + " must be a string");
            return null;
        }
        return node.asText();
    }

    private static int intField(JsonNode root, String field, int defaultValue, List<String> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(field + " must be an integer");
            return defaultValue;
        }
        return node.asInt();
    }

    private static boolean booleanField(
            JsonNode root, String field, boolean defaultValue, List<String> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            errors.add(field + " must be a boolean");
            return defaultValue;
        }
        return node.asBoolean();
    }
}
