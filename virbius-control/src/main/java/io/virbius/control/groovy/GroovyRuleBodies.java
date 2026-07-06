package io.virbius.control.groovy;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class GroovyRuleBodies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GroovyRuleBodies() {}

    public static String asScript(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return body.toString();
        }
    }
}
