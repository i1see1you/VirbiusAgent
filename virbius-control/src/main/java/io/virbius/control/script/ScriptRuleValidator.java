package io.virbius.control.script;

import io.virbius.control.groovy.GroovyRuleBodies;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.groovy.l3.GroovyL3ValidationException;
import io.virbius.groovy.l3.GroovyL3Validator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ScriptRuleValidator {

    private static final Pattern LIST_REF =
            Pattern.compile("(?:ctx\\.)?listMatch\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CUM_REF =
            Pattern.compile("(?:getCumulative|ctx\\.getCumulative)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;

    public ScriptRuleValidator(ListMetaRepository listMetaRepo, CumulativeRepository cumulativeRepo) {
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
    }

    public Map<String, Object> validate(String tenantId, String runtime, Object body) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String rt = runtime != null ? runtime.trim().toLowerCase() : "";
        String script = "";
        try {
            if ("lua".equals(rt) || "groovy".equals(rt)) {
                script = ScriptRuleBodies.asExecutableScript(body, rt);
            } else {
                script = GroovyRuleBodies.asScript(body);
            }
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return Map.of(
                    "valid", false,
                    "errors", errors,
                    "warnings", warnings,
                    "referenced_lists", List.of(),
                    "referenced_cumulatives", List.of());
        }

        if (script.isBlank()) {
            errors.add("script body is empty");
        } else if ("groovy".equals(rt)) {
            validateGroovy(script, errors);
        } else if ("lua".equals(rt)) {
            validateLua(script, errors);
        } else if ("prompt".equals(rt)) {
            if (script.length() > 4096) {
                warnings.add("prompt body is long (>4096 chars)");
            }
        } else if (!"lua-dsl".equals(rt)) {
            warnings.add("runtime " + rt + ": no script validation");
        }

        Set<String> lists = extract(LIST_REF, script);
        Set<String> cumulatives = extract(CUM_REF, script);
        for (String name : lists) {
            if (listMetaRepo.getMeta(tenantId, name).isEmpty()) {
                errors.add("list not found: " + name);
            }
        }
        for (String name : cumulatives) {
            if (cumulativeRepo.get(tenantId, name).isEmpty()) {
                errors.add("cumulative not found: " + name);
            }
        }

        if ("lua".equals(rt) && script.contains("ctx.listMatch")) {
            warnings.add("gateway lua prefers listMatch(...), not ctx.listMatch(...)");
        }
        if ("groovy".equals(rt) && script.matches("(?s).*\\blistMatch\\s*\\(.*")) {
            warnings.add("cloud groovy prefers ctx.listMatch(...)");
        }

        return Map.of(
                "valid", errors.isEmpty(),
                "errors", errors,
                "warnings", warnings,
                "referenced_lists", List.copyOf(lists),
                "referenced_cumulatives", List.copyOf(cumulatives));
    }

    public void validateOrThrow(String tenantId, String runtime, Object body) {
        Map<String, Object> result = validate(tenantId, runtime, body);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void validateGroovy(String script, List<String> errors) {
        try {
            GroovyL3Validator.validate(script);
        } catch (GroovyL3ValidationException e) {
            errors.add(e.getMessage());
        }
    }

    private static void validateLua(String script, List<String> errors) {
        try {
            LuaScriptValidator.validate(script);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private static Set<String> extract(Pattern pattern, String script) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(script);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
