package io.virbius.groovy.l3;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Sandbox-execute L3 {@code decide(ctx)} (G3-G5). Timeout or exception: caller fail-opens.
 */
public final class GroovyL3Executor {

    public static final long DEFAULT_TIMEOUT_MS = 50;

    private final long timeoutMs;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Class<? extends Script>> scriptClassCache = new ConcurrentHashMap<>();
    private final CompilerConfiguration compilerConfig;
    private final GroovyClassLoader classLoader;

    public GroovyL3Executor() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public GroovyL3Executor(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "groovy-l3");
            t.setDaemon(true);
            return t;
        });
        this.compilerConfig = GroovyL3Validator.executionConfiguration();
        this.classLoader = new GroovyClassLoader(GroovyL3Executor.class.getClassLoader(), compilerConfig);
    }

    public GroovyL3Decision execute(String scriptBody, PolicyContext ctx) throws Exception {
        boolean hit = executeDecide(scriptBody, ctx);
        String mode = ctx.enforceMode(ctx.currentRuleId());
        if (!hit) {
            return GroovyL3Decision.allow(mode);
        }
        if ("dry_run".equalsIgnoreCase(mode)) {
            return new GroovyL3Decision("review", mode);
        }
        if ("canary".equalsIgnoreCase(mode)
                && !ctx.inCanaryBucket(ctx.sessionId(), ctx.canaryPercent(ctx.currentRuleId()))) {
            return new GroovyL3Decision("review", mode);
        }
        return new GroovyL3Decision("block", mode);
    }

    /** Pre-compile a script body into the cache. Idempotent — subsequent calls are no-ops. */
    public void precompile(String scriptBody) {
        String body = normalizeBody(scriptBody);
        String cacheKey = Integer.toHexString(body.hashCode());
        scriptClassCache.computeIfAbsent(cacheKey, k -> compileClass(body));
    }

    /** Run {@code decide(ctx)} and return {@code true} when the rule hits. */
    public boolean executeDecide(String scriptBody, PolicyContext ctx) throws Exception {
        Objects.requireNonNull(ctx, "ctx");
        String body = normalizeBody(scriptBody);
        String cacheKey = Integer.toHexString(body.hashCode());
        Class<? extends Script> scriptClass = scriptClassCache.computeIfAbsent(cacheKey, k -> compileClass(body));

        Future<Boolean> future = executor.submit(() -> runDecide(scriptClass, ctx));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("groovy L3 exceeded " + timeoutMs + "ms");
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Script> compileClass(String body) {
        Class<?> clazz = classLoader.parseClass(wrapScript(body), "L3_" + Integer.toHexString(body.hashCode()));
        return (Class<? extends Script>) clazz;
    }

    private static String wrapScript(String body) {
        return body.strip() + "\ndecide(ctx)\n";
    }

    private static final MlModelUtil mlModel = new MlModelUtil();

    private static boolean runDecide(Class<? extends Script> scriptClass, PolicyContext ctx)
            throws ReflectiveOperationException {
        Script instance = scriptClass.getDeclaredConstructor().newInstance();
        Binding binding = new Binding();
        binding.setVariable("ctx", ctx);
        binding.setVariable("listMatch", new ListMatchClosure(ctx));
        binding.setVariable("getCumulative", new GetCumulativeClosure(ctx));
        binding.setVariable("mlPredict", new MlPredictClosure());
        instance.setBinding(binding);
        Object raw = instance.run();
        return toBoolean(raw);
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action == null) {
                return false;
            }
            String s = action.toString().toLowerCase();
            return !"allow".equals(s) && !"false".equals(s);
        }
        if (raw instanceof String s) {
            return !s.isBlank() && !"allow".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s);
        }
        return false;
    }

    private static final class ListMatchClosure {
        private final PolicyContext ctx;

        ListMatchClosure(PolicyContext ctx) {
            this.ctx = ctx;
        }

        public boolean doCall(String listName) {
            return ctx.listMatch(listName);
        }

        public boolean doCall(String listName, String value) {
            return ctx.listMatch(listName, value);
        }
    }

    private static final class GetCumulativeClosure {
        private final PolicyContext ctx;

        GetCumulativeClosure(PolicyContext ctx) {
            this.ctx = ctx;
        }

        public long doCall(String cumulativeName) {
            return ctx.getCumulative(cumulativeName);
        }
    }

    private static final class MlPredictClosure {
        @SuppressWarnings("unchecked")
        public Map<String, Object> doCall(String url, Map<String, Object> features) {
            return mlModel.predict(url, features);
        }
    }

    static String normalizeBody(String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            return GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT.strip();
        }
        String t = scriptBody.strip();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
            return t.substring(1, t.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
        }
        return t;
    }
}
