package io.virbius.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shells out to the {@code virbius-expr} Go CLI to compile Lua {@code decide(ctx)} scripts
 * into flat DAG IR (Expression + ActionBinding JSON) at deployment time.
 *
 * <p>The compiled IR is embedded directly in the gateway artifact snapshot — it is never
 * persisted to the database. Lua source code remains the single source of truth.
 *
 * <p>If the CLI binary is unavailable or compilation fails, the rule is silently skipped
 * (fail-open) with a warning log, so that non-Lua rules or rules with unsupported syntax
 * do not block artifact generation.
 */
@Component
public class ExpressionCompilerClient {

    private static final Logger log = LoggerFactory.getLogger(ExpressionCompilerClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String binaryPath;
    private final long timeoutMs;

    public ExpressionCompilerClient(
            @Value("${virbius.expr.binary:virbius-expr}") String binaryPath,
            @Value("${virbius.expr.timeout-ms:5000}") long timeoutMs) {
        this.binaryPath = binaryPath;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Compile a single Lua script into a CompiledRule JSON node (Expression + ActionBinding).
     *
     * @param script     the Lua {@code decide(ctx)} script source (must contain {@code return <expr>})
     * @param exprId     unique expression ID (usually rule_id)
     * @param ruleId     rule ID for the action binding
     * @param action     action: "block" | "challenge" | "review"
     * @param reason     human-readable reason
     * @param riskScore  risk score (0-100)
     * @return parsed JSON node, or {@code null} if compilation failed
     */
    public JsonNode compile(String script, String exprId, String ruleId, String action, String reason, int riskScore) {
        if (script == null || script.isBlank()) {
            return null;
        }
        try {
            Path tmp = Files.createTempFile("virbius-lua-", ".lua");
            try {
                Files.writeString(tmp, script, StandardCharsets.UTF_8);
                List<String> cmd = new ArrayList<>();
                cmd.add(binaryPath);
                cmd.add("--file");
                cmd.add(tmp.toString());
                cmd.add("--id");
                cmd.add(exprId);
                cmd.add("--script");
                cmd.add("--with-action");
                cmd.add("--rule-id");
                cmd.add(ruleId);
                cmd.add("--action");
                cmd.add(action != null ? action : "block");
                cmd.add("--reason");
                cmd.add(reason != null ? reason : "expression matched");
                cmd.add("--risk-score");
                cmd.add(String.valueOf(riskScore));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                Process proc = pb.start();
                byte[] stdout = proc.getInputStream().readAllBytes();
                byte[] stderr = proc.getErrorStream().readAllBytes();
                boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    log.warn("virbius-expr timeout for rule={} ({}ms)", ruleId, timeoutMs);
                    return null;
                }
                if (proc.exitValue() != 0) {
                    log.warn("virbius-expr failed for rule={}: {}", ruleId, new String(stderr, StandardCharsets.UTF_8).trim());
                    return null;
                }
                return mapper.readTree(stdout);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.warn("virbius-expr I/O error for rule={}: {}", ruleId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("virbius-expr interrupted for rule={}", ruleId);
            return null;
        } catch (Exception e) {
            log.warn("virbius-expr unexpected error for rule={}: {}", ruleId, e.getMessage());
            return null;
        }
    }

    /**
     * Compile a list of rules into expression IR entries.
     * Each entry is a Map with "expression" and "action" keys.
     *
     * @param compiledRules list of [script, exprId, ruleId, action, reason, riskScore]
     * @return list of parsed JSON maps, excluding failures
     */
    public List<Map<String, Object>> compileBatch(List<CompileRequest> requests) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (CompileRequest req : requests) {
            JsonNode node = compile(
                    req.script(), req.exprId(), req.ruleId(),
                    req.action(), req.reason(), req.riskScore());
            if (node != null && !node.isMissingNode()) {
                results.add(mapper.convertValue(node, Map.class));
            }
        }
        return results;
    }

    /** Request DTO for batch compilation. */
    public record CompileRequest(
            String script,
            String exprId,
            String ruleId,
            String action,
            String reason,
            int riskScore) {}
}
