package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.groovy.l3.GroovyL3Executor;
import io.virbius.groovy.l3.PolicyContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChainRetryRulesTest {

    private static final String CHAIN_EXFIL = """
def decide(ctx) {
    def readTools = ["read_file", "cat", "read", "get_file", "download", "list_dir"]
    def netTools = ["curl", "http_get", "http_post", "fetch", "webhook_call", "wget", "request"]
    def sensitive = ["/etc/", "/root/", "/.ssh", "id_rsa", ".env", "credentials", "secret", "/var/"]
    def seq = []
    def h = ctx.sessionHistory(5)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 5) {
        seq = seq.subList(seq.size() - 5, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        def a = (e.args ?: "").toString().toLowerCase()
        if (step == 0 && t != null && readTools.contains(t) && sensitive.any { a.contains(it) }) {
            step = 1
        } else if (step == 1 && t != null && netTools.contains(t)) {
            def i = a.indexOf("http")
            if (i >= 0) {
                def rest = a.substring(i)
                def end = rest.indexOf(34)
                def url = end > 0 ? rest.substring(0, end) : rest
                if (!ctx.isInternalHost(url)) {
                    step = 2
                }
            }
        }
    }
    return step == 2
}""";

    private static final String CHAIN_WRITE_EXEC = """
def decide(ctx) {
    def writeTools = ["write_file", "delete_file", "rm", "mv", "cp", "truncate", "edit_file", "db_write"]
    def execTools = ["exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]
    def seq = []
    def h = ctx.sessionHistory(6)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 6) {
        seq = seq.subList(seq.size() - 6, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        if (step == 0 && t != null && writeTools.contains(t)) {
            step = 1
        } else if (step == 1 && t != null && execTools.contains(t)) {
            step = 2
        }
    }
    return step == 2
}""";

    private static final String CHAIN_PRIV_ESC = """
def decide(ctx) {
    def readTools = ["read_file", "cat", "read", "get_file", "download", "list_dir"]
    def writeTools = ["write_file", "delete_file", "rm", "mv", "cp", "truncate", "edit_file", "db_write"]
    def execTools = ["exec_cmd", "shell", "bash", "sh", "execute_python", "run_command", "subprocess"]
    def seq = []
    def h = ctx.sessionHistory(8)
    for (int i = h.size() - 1; i >= 0; i--) {
        seq.add(h[i])
    }
    seq.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (seq.size() > 8) {
        seq = seq.subList(seq.size() - 8, seq.size())
    }
    def step = 0
    for (def e : seq) {
        def t = e.tool_name?.toString()
        if (step == 0 && t != null && readTools.contains(t)) {
            step = 1
        } else if (step == 1 && t != null && writeTools.contains(t)) {
            step = 2
        } else if (step == 2 && t != null && execTools.contains(t)) {
            step = 3
        }
    }
    return step == 3
}""";

    private static final String RETRY_ESCAPE = """
def decide(ctx) {
    def h = ctx.sessionHistory(10)
    def attempts = []
    for (int i = h.size() - 1; i >= 0; i--) {
        attempts.add(h[i])
    }
    if (attempts.size() > 10) {
        attempts = attempts.subList(attempts.size() - 10, attempts.size())
    }
    def lastBlock = -1
    for (int i = 0; i < attempts.size(); i++) {
        if (attempts[i].allowed == false) {
            lastBlock = i
        }
    }
    if (lastBlock < 0) {
        return false
    }
    def blocked = attempts[lastBlock]
    def trailing = 0
    for (int i = attempts.size() - 1; i > lastBlock; i--) {
        if (attempts[i].allowed != false) {
            trailing++
        } else {
            break
        }
    }
    if (trailing >= 3) {
        return false
    }
    def after = []
    after.addAll(attempts.subList(lastBlock + 1, attempts.size()))
    after.add([tool_name: ctx.var("tool_name"), args: ctx.var("content")])
    if (after.size() < 3) {
        return false
    }
    def bTool = blocked.tool_name?.toString()
    def bArgs = (blocked.args ?: "").toString()
    return after.any {
        def t = it.tool_name?.toString()
        def a = (it.args ?: "").toString()
        (t != null && t != bTool) || a != bArgs
    }
}""";

    private static GroovyL3Executor EXECUTOR;

    @BeforeAll
    static void precompileBodies() throws Exception {
        EXECUTOR = new GroovyL3Executor();
        warm(CHAIN_EXFIL);
        warm(CHAIN_WRITE_EXEC);
        warm(CHAIN_PRIV_ESC);
        warm(RETRY_ESCAPE);
    }

    private static void warm(String body) throws Exception {
        PolicyContext ctx = new PolicyContext("default", "warm", "warm", Map.of(), List.of(),
                Map.of("tool_name", "read_file", "role", "tool_call", "content", "{}"), null,
                List.of(), 0, Map.of());
        for (int i = 0; i < 10; i++) {
            try {
                EXECUTOR.executeDecide(body, ctx);
                return;
            } catch (java.util.concurrent.TimeoutException ignored) {
            }
        }
        throw new IllegalStateException("groovy warm-up failed");
    }


    private static Map<String, Object> call(String tool, String args, boolean allowed) {
        Map<String, Object> m = new HashMap<>();
        m.put("tool_name", tool);
        m.put("args", args);
        m.put("allowed", allowed);
        return m;
    }

    private PolicyContext ctx(String tool, String content, List<Map<String, Object>> historyMostRecentFirst) {
        Map<String, String> vars = new HashMap<>();
        vars.put("tool_name", tool);
        vars.put("role", "tool_call");
        if (content != null) {
            vars.put("content", content);
        }
        return new PolicyContext("default", "sess", "test", Map.of(), List.of(),
                vars, null, historyMostRecentFirst, 0, Map.of());
    }

    private boolean hit(String body, String tool, String content, List<Map<String, Object>> history) {
        try {
            return EXECUTOR.executeDecide(body, ctx(tool, content, history));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void chainExfilHitsSensitiveReadThenExternalCall() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("read_file", "{\"path\":\"/home/u/.aws/credentials\"}", true));
        assertTrue(hit(CHAIN_EXFIL, "http_get", "{\"url\":\"https://api.example.com/x\"}", history));
    }

    @Test
    void chainExfilSkipsOrdinaryHomeFileRead() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("read_file", "{\"path\":\"/home/u/notes.txt\"}", true));
        assertFalse(hit(CHAIN_EXFIL, "http_get", "{\"url\":\"https://api.example.com/x\"}", history));
    }

    @Test
    void chainExfilSkipsInternalUrl() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("read_file", "{\"path\":\"/home/u/.aws/credentials\"}", true));
        assertFalse(hit(CHAIN_EXFIL, "http_get", "{\"url\":\"https://10.0.0.8/x\"}", history));
    }

    @Test
    void chainWriteExecHits() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("write_file", "{\"path\":\"/tmp/a.sh\"}", true));
        assertTrue(hit(CHAIN_WRITE_EXEC, "exec_cmd", "{\"cmd\":\"bash /tmp/a.sh\"}", history));
        assertFalse(hit(CHAIN_WRITE_EXEC, "exec_cmd", "{\"cmd\":\"ls\"}", new ArrayList<>()));
    }

    @Test
    void chainPrivEscRequiresMonotonicOrder() {
        List<Map<String, Object>> ordered = new ArrayList<>();
        ordered.add(call("write_file", "{\"path\":\"/tmp/a\"}", true));
        ordered.add(call("read_file", "{\"path\":\"/tmp/b\"}", true));
        assertTrue(hit(CHAIN_PRIV_ESC, "exec_cmd", "{\"cmd\":\"id\"}", ordered));

        List<Map<String, Object>> reversed = new ArrayList<>();
        reversed.add(call("read_file", "{\"path\":\"/tmp/b\"}", true));
        reversed.add(call("write_file", "{\"path\":\"/tmp/a\"}", true));
        assertFalse(hit(CHAIN_PRIV_ESC, "exec_cmd", "{\"cmd\":\"id\"}", reversed));
    }

    @Test
    void retryEscapeHitsThirdChangedAttemptAfterBlock() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("read_file", "{\"path\":\"/tmp/a.txt\"}", true));
        history.add(call("exec_cmd", "{\"cmd\":\"ls\"}", true));
        history.add(call("read_file", "{\"path\":\"~/.ssh/id_rsa\"}", false));
        assertTrue(hit(RETRY_ESCAPE, "exec_cmd", "{\"cmd\":\"pwd\"}", history));
    }

    @Test
    void retryEscapeWashesOutAfterThreeAllowedCalls() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(call("exec_cmd", "{\"cmd\":\"pwd\"}", true));
        history.add(call("read_file", "{\"path\":\"/tmp/a.txt\"}", true));
        history.add(call("exec_cmd", "{\"cmd\":\"ls\"}", true));
        history.add(call("read_file", "{\"path\":\"~/.ssh/id_rsa\"}", false));
        assertFalse(hit(RETRY_ESCAPE, "exec_cmd", "{\"cmd\":\"whoami\"}", history));
    }

    @Test
    void retryEscapeNeedsThreeAttemptsAndABlock() {
        List<Map<String, Object>> few = new ArrayList<>();
        few.add(call("exec_cmd", "{\"cmd\":\"ls\"}", true));
        few.add(call("read_file", "{\"path\":\"~/.ssh/id_rsa\"}", false));
        assertFalse(hit(RETRY_ESCAPE, "exec_cmd", "{\"cmd\":\"pwd\"}", few));

        List<Map<String, Object>> noBlock = new ArrayList<>();
        noBlock.add(call("exec_cmd", "{\"cmd\":\"ls\"}", true));
        noBlock.add(call("read_file", "{\"path\":\"/tmp/a\"}", true));
        assertFalse(hit(RETRY_ESCAPE, "exec_cmd", "{\"cmd\":\"pwd\"}", noBlock));
    }
}
