package io.virbius.engine.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * C1: temporal behaviour-chain detection. Mines the session tool history for
 * multi-step violation patterns that no single rule catches: read-sensitive →
 * external egress, write → execute, and monotonic privilege escalation
 * (READ → WRITE → EXEC → NETWORK).
 *
 * <p>Patterns are data-defined in code with argument-level conditions
 * (sensitive path prefix + external host) to avoid the classic false positive
 * of "any read followed by any network call". Emission is {@code dry_run} by
 * default — signals are produced (visible in audit) but not enforced until
 * {@code virbius.chain-patterns.enforce=true}, so normal long tasks are never
 * mis-blocked out of the box.
 */
@Component
public class ChainPatternDetector {

    private static final Logger log = LoggerFactory.getLogger(ChainPatternDetector.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enforce;

    private static final Set<String> READ_TOOLS =
            Set.of("read_file", "cat", "read", "get_file", "download", "list_dir");
    private static final Set<String> WRITE_TOOLS =
            Set.of("write_file", "delete_file", "rm", "mv", "cp", "truncate", "edit_file", "db_write");
    private static final Set<String> EXEC_TOOLS =
            Set.of("exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess");
    private static final Set<String> NET_TOOLS =
            Set.of("curl", "http_get", "http_post", "fetch", "webhook_call", "wget", "request");
    private static final List<String> SENSITIVE_PREFIXES =
            List.of("/etc/", "/root/", "/home/", "/.ssh", "id_rsa", ".env", "credentials", "secret", "/var/");

    private record StepCriterion(Set<String> tools, Predicate<Map<String, Object>> argCheck) {}

    private record ChainPattern(
            String id, List<StepCriterion> steps, int window, int riskDelta, String intentAction) {}

    private final List<ChainPattern> patterns = buildPatterns();

    public ChainPatternDetector(
            @Value("${virbius.chain-patterns.enforce:false}") boolean enforce) {
        this.enforce = enforce;
    }

    private static List<ChainPattern> buildPatterns() {
        List<ChainPattern> list = new ArrayList<>();
        // P1: sensitive read → external egress.
        list.add(new ChainPattern("chain-exfil", List.of(
                new StepCriterion(READ_TOOLS, a -> {
                    String p = pathOf(a);
                    return p != null && SENSITIVE_PREFIXES.stream().anyMatch(s -> p.contains(s));
                }),
                new StepCriterion(NET_TOOLS, a -> {
                    String u = urlOf(a);
                    return u != null && !isInternalHost(u);
                })), 5, 20, "challenge"));
        // P2: write/delete → execute.
        list.add(new ChainPattern("chain-write-exec", List.of(
                new StepCriterion(WRITE_TOOLS, a -> true),
                new StepCriterion(EXEC_TOOLS, a -> true)), 6, 25, "challenge"));
        // P3: privilege escalation READ → WRITE → EXEC (monotonic affinity climb).
        list.add(new ChainPattern("chain-priv-esc", List.of(
                new StepCriterion(READ_TOOLS, a -> true),
                new StepCriterion(WRITE_TOOLS, a -> true),
                new StepCriterion(EXEC_TOOLS, a -> true)), 8, 15, "challenge"));
        return list;
    }

    /**
     * @param history session tool history, most recent first
     * @return CHAIN_ABUSE signals for any matched pattern (empty when none)
     */
    public List<SignalDto> detect(List<Map<String, Object>> history, String toolName, String argsJson) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Call> calls = toOrderedCalls(history);
        calls.add(new Call(toolName, parseArgs(argsJson)));

        List<SignalDto> signals = new ArrayList<>();
        for (ChainPattern p : patterns) {
            if (matchesInWindow(p, calls)) {
                String mode = enforce ? "full" : "dry_run";
                if (!enforce) {
                    log.info("chain pattern hit (dry_run): pattern={} tool={} window={}",
                            p.id(), toolName, p.window());
                }
                signals.add(new SignalDto(
                        p.id(), 1, "cloud", "cloud", p.riskDelta(),
                        "CHAIN_ABUSE", p.intentAction(), mode, null, null));
            }
        }
        return signals;
    }

    private record Call(String tool, Map<String, Object> args) {}

    private boolean matchesInWindow(ChainPattern p, List<Call> calls) {
        int from = Math.max(0, calls.size() - p.window());
        List<Call> window = calls.subList(from, calls.size());
        int step = 0;
        for (Call c : window) {
            if (step >= p.steps().size()) {
                break;
            }
            StepCriterion sc = p.steps().get(step);
            if (c.tool() != null && sc.tools().contains(c.tool()) && sc.argCheck().test(c.args())) {
                step++;
            }
        }
        return step == p.steps().size();
    }

    private List<Call> toOrderedCalls(List<Map<String, Object>> historyMostRecentFirst) {
        List<Call> out = new ArrayList<>();
        for (int i = historyMostRecentFirst.size() - 1; i >= 0; i--) {
            Map<String, Object> e = historyMostRecentFirst.get(i);
            Object tool = e.get("tool_name");
            Map<String, Object> args = parseArgs(e.get("args"));
            out.add(new Call(tool != null ? tool.toString() : null, args));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(Object args) {
        if (args == null) {
            return Map.of();
        }
        if (args instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String s = args.toString().trim();
        if (s.isEmpty()) {
            return Map.of();
        }
        try {
            return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String pathOf(Map<String, Object> a) {
        for (String k : new String[] {"path", "file", "filename", "target"}) {
            Object v = a.get(k);
            if (v != null) {
                return v.toString();
            }
        }
        return null;
    }

    private static String urlOf(Map<String, Object> a) {
        for (String k : new String[] {"url", "endpoint", "uri"}) {
            Object v = a.get(k);
            if (v != null) {
                return v.toString();
            }
        }
        return null;
    }

    static boolean isInternalHost(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                return false;
            }
            if (host.endsWith(".internal") || host.endsWith(".local") || host.endsWith(".svc.cluster.local")) {
                return true;
            }
            if (host.startsWith("10.") || host.startsWith("172.") || host.startsWith("192.168.")) {
                return true;
            }
            return host.equals("localhost") || host.equals("127.0.0.1");
        } catch (Exception e) {
            return false;
        }
    }
}
