package io.virbius.groovy.l3;

import groovy.lang.GroovyShell;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/** Gate G6: size + dangerous token + parse check (PoC no full AST sandbox; runtime only exposes ctx via Binding). */
public final class GroovyL3Validator {

    public static final int MAX_BODY_BYTES = 32 * 1024;

    private static final java.util.Set<String> FORBIDDEN_TOKENS =
            java.util.Set.of("Runtime", "ProcessBuilder", "Class.forName", "System.exit", "@Grab", "GroovyShell");

    private static final Set<String> ALLOWED_CTX_METHODS =
            Arrays.stream(PolicyContext.class.getMethods())
                    .map(Method::getName)
                    .collect(Collectors.toUnmodifiableSet());

    private GroovyL3Validator() {}

    public static void validate(String scriptBody) throws GroovyL3ValidationException {
        if (scriptBody == null || scriptBody.isBlank()) {
            throw new GroovyL3ValidationException("groovy body is empty");
        }
        if (scriptBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new GroovyL3ValidationException("groovy body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        String lower = scriptBody.toLowerCase();
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token.toLowerCase())) {
                throw new GroovyL3ValidationException("forbidden token in groovy script: " + token);
            }
        }
        if (!scriptBody.contains("decide")) {
            throw new GroovyL3ValidationException("groovy script must define decide(ctx)");
        }
        List<String> unknownMethods = new ArrayList<>();

        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new CompilationCustomizer(CompilePhase.SEMANTIC_ANALYSIS) {
            @Override
            public void call(SourceUnit source, GeneratorContext context, ClassNode classNode)
                    throws CompilationFailedException {
                for (MethodNode method : classNode.getMethods()) {
                    Statement body = method.getCode();
                    if (body != null) {
                        body.visit(new CodeVisitorSupport() {
                            @Override
                            public void visitMethodCallExpression(MethodCallExpression call) {
                                Expression obj = call.getObjectExpression();
                                if (obj instanceof VariableExpression
                                        && "ctx".equals(((VariableExpression) obj).getName())) {
                                    String name = call.getMethodAsString();
                                    if (name != null && !ALLOWED_CTX_METHODS.contains(name)) {
                                        unknownMethods.add(name);
                                    }
                                }
                                super.visitMethodCallExpression(call);
                            }
                        });
                    }
                }
            }
        });

        try {
            new GroovyShell(cc).parse(scriptBody);
        } catch (Exception e) {
            throw new GroovyL3ValidationException("groovy parse failed: " + e.getMessage());
        }
        if (!unknownMethods.isEmpty()) {
            throw new GroovyL3ValidationException(
                    "unknown ctx method(s): " + String.join(", ", unknownMethods));
        }
    }

    static CompilerConfiguration executionConfiguration() {
        return new CompilerConfiguration();
    }
}
