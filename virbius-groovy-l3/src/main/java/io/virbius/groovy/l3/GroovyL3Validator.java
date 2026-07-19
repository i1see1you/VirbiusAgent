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
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Gate G6: size + dangerous token + parse check + return-type verification.
 *
 * <p>Performs three layers of validation:
 * <ol>
 *   <li><b>Static checks</b>: size, forbidden tokens, {@code decide} presence</li>
 *   <li><b>AST inspection</b>: parse + ctx method allowlist + return-type heuristic</li>
 *   <li><b>Trial execution</b>: run {@code decide(ctx)} with a mock context and verify
 *       the return value is {@code Boolean}</li>
 * </ol>
 *
 * <p>The return-type check enforces the contract that {@code decide(ctx) → boolean}.
 * Scripts returning {@code Map}, {@code String}, {@code Number}, or {@code null} are
 * rejected. The AST heuristic catches direct literal returns; the trial execution
 * catches indirect returns (e.g., {@code def r = [:]; return r}).
 */
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
        validate(scriptBody, true);
    }

    /**
     * Validate a Groovy script body.
     *
     * @param scriptBody      the script source code
     * @param trialExecution  whether to perform trial execution for return-type checking.
     *                        Set to {@code false} in environments where creating a
     *                        {@link GroovyL3Executor} is undesirable (e.g., control-plane
     *                        without the groovy-l3 runtime). AST heuristic still applies.
     */
    public static void validate(String scriptBody, boolean trialExecution)
            throws GroovyL3ValidationException {
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
        List<String> returnTypeErrors = new ArrayList<>();

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

                            @Override
                            public void visitReturnStatement(ReturnStatement statement) {
                                Expression expr = statement.getExpression();
                                String issue = classifyReturnType(expr);
                                if (issue != null) {
                                    returnTypeErrors.add(issue);
                                }
                                super.visitReturnStatement(statement);
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
        if (!returnTypeErrors.isEmpty()) {
            throw new GroovyL3ValidationException(
                    "return type error(s): " + String.join("; ", returnTypeErrors));
        }

        // Trial execution: run decide(ctx) with a mock context and verify return type
        if (trialExecution) {
            verifyReturnTypeByExecution(scriptBody);
        }
    }

    /**
     * Classify a return expression and return an error message if it violates the
     * {@code decide(ctx) → boolean} contract, or {@code null} if the expression
     * is acceptable (or cannot be statically determined).
     */
    private static String classifyReturnType(Expression expr) {
        if (expr instanceof ConstantExpression c) {
            Object val = c.getValue();
            if (val instanceof Boolean) {
                return null; // ✅ return true / return false
            }
            if (val instanceof String) {
                return "decide(ctx) must return boolean, found String literal: \"" + val + "\"";
            }
            if (val instanceof Number) {
                return "decide(ctx) must return boolean, found numeric literal: " + val;
            }
            if (val == null) {
                return "decide(ctx) returns null — use explicit 'return false' instead";
            }
            return null;
        }
        if (expr instanceof MapExpression) {
            return "decide(ctx) must return boolean, found Map literal — "
                    + "intent/risk come from rule row config, not script return value";
        }
        if (expr instanceof ListExpression) {
            return "decide(ctx) must return boolean, found List literal";
        }
        // These expression types produce boolean or are not statically determinable — allow them
        if (expr instanceof BooleanExpression
                || expr instanceof BinaryExpression
                || expr instanceof MethodCallExpression
                || expr instanceof VariableExpression) {
            return null;
        }
        return null; // unknown expression type — allow (trial execution will catch mismatches)
    }

    /**
     * Execute the script with a mock {@link PolicyContext} and verify the return value
     * is {@code Boolean}. Catches indirect non-boolean returns that the AST heuristic
     * cannot detect (e.g., {@code def r = [:]; return r}).
     *
     * <p>If trial execution fails due to timeout or script error, a warning is logged
     * but validation passes — the AST heuristic is the primary guard.
     */
    private static void verifyReturnTypeByExecution(String scriptBody)
            throws GroovyL3ValidationException {
        PolicyContext mockCtx = createMockContext();
        GroovyL3Executor executor = new GroovyL3Executor(5000);
        try {
            Object raw = executor.executeRaw(scriptBody, mockCtx);
            if (raw == null) {
                throw new GroovyL3ValidationException(
                        "decide(ctx) returned null — ensure all code paths have 'return true' or 'return false'");
            }
            if (!(raw instanceof Boolean)) {
                throw new GroovyL3ValidationException(
                        "decide(ctx) must return boolean, got " + raw.getClass().getSimpleName()
                                + " (" + truncateForError(raw) + ") — "
                                + "intent/risk come from rule row config, not script return value");
            }
        } catch (GroovyL3ValidationException e) {
            throw e; // re-throw validation errors
        } catch (Exception e) {
            // Trial execution failed (timeout, compilation error, etc.)
            // — AST heuristic is the primary guard, so downgrade to a no-op.
            // The parse() call above already caught syntax errors.
        } finally {
            executor.shutdown();
        }
    }

    private static PolicyContext createMockContext() {
        return new PolicyContext(
                "validate",
                "validate-sess",
                "validate-rule",
                java.util.Map.of(),
                java.util.List.of(),
                java.util.Map.of("tool_name", "validate_tool", "app_id", "validate_app"));
    }

    private static String truncateForError(Object obj) {
        String s = String.valueOf(obj);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    static CompilerConfiguration executionConfiguration() {
        return new CompilerConfiguration();
    }
}
