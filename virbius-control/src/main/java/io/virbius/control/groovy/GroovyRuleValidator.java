package io.virbius.control.groovy;

import io.virbius.control.domain.RuleRevision;
import io.virbius.groovy.l3.GroovyL3ValidationException;
import io.virbius.groovy.l3.GroovyL3Validator;
import org.springframework.stereotype.Component;

@Component
public class GroovyRuleValidator {

    public void validateRevision(RuleRevision draft) {
        if (draft == null || !"groovy".equals(draft.runtime())) {
            return;
        }
        try {
            GroovyL3Validator.validate(GroovyRuleBodies.asScript(draft.body()));
        } catch (GroovyL3ValidationException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
