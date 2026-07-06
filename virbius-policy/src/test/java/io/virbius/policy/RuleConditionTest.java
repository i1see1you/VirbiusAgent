package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuleConditionTest {

    @Test
    void evaluateGte() {
        RuleCondition c = new RuleCondition("gte", 120);
        assertTrue(c.evaluate(120));
        assertTrue(c.evaluate(121));
        assertFalse(c.evaluate(119));
    }

    @Test
    void evaluateGt() {
        RuleCondition c = new RuleCondition("gt", 10);
        assertTrue(c.evaluate(11));
        assertFalse(c.evaluate(10));
    }
}
