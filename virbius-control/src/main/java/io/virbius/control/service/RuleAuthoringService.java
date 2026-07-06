package io.virbius.control.service;

import io.virbius.control.ruleauthoring.ConditionCompiler;
import io.virbius.control.ruleauthoring.ConditionParser;
import io.virbius.control.ruleauthoring.RuleRecipeCatalog;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RuleAuthoringService {

    public Map<String, Object> compile(String layer, String runtime, Map<String, Object> condition) {
        return ConditionCompiler.compile(layer, runtime, condition);
    }

    public Map<String, Object> parse(String layer, String runtime, String script) {
        return ConditionParser.parse(layer, runtime, script);
    }

    public Map<String, Object> recipes(String layer) {
        return Map.of("recipes", RuleRecipeCatalog.recipes(layer));
    }
}
