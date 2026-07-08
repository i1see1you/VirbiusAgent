-- OpenResty access phase: read compiler-flattened effective JSON, scene_registry, agent evaluate.
local function add_lib_path()
    local info = debug.getinfo(1, "S")
    local source = info and info.source or ""
    if source:sub(1, 1) == "@" then
        source = source:sub(2)
    end
    local plugin_dir = source:match("^(.*)/")
    if plugin_dir then
        package.path = plugin_dir .. "/../../lib/?.lua;" .. package.path
    end
    local env_root = os.getenv("VIRBIUS_GATEWAY_LIB")
    if env_root and env_root ~= "" then
        package.path = env_root:gsub("/$", "") .. "/?.lua;" .. package.path
    end
end
add_lib_path()

local json_util = require("json_util")
local effective_mod = require("effective")
local trace = require("trace")
local prompt = require("prompt")
local context_vars = require("context_vars")
local scene_registry = require("scene_registry")
local access_lists = require("access_lists")
local config_redis = require("config_redis")
local list_redis = require("list_redis")
local http = require("resty.http")

local function get_header(name)
    return ngx.req.get_headers()[name] or ngx.req.get_headers()[string.lower(name)]
end

local function json_exit(status, body)
    ngx.status = status
    ngx.header["Content-Type"] = "application/json"
    ngx.say(json_util.encode(body))
    return ngx.exit(status)
end

local effective_path = ngx.var.virbius_effective
local doc, err = effective_mod.load(effective_path)
if not doc then
    ngx.log(ngx.ERR, "virbius openresty: ", err)
    return json_exit(500, { code = "INTERNAL", message = err or "effective config load failed" })
end

local conf = doc.virbius
if conf.evaluate == false then
    return
end

local trace_hdr = get_header("X-Virbius-Trace-Id")
local trace_id = trace_hdr
if trace_hdr and trace_hdr ~= "" then
    if not trace.valid(trace_hdr) then
        return json_exit(400, {
            code = "INVALID_ARGUMENT",
            message = "invalid X-Virbius-Trace-Id",
        })
    end
else
    trace_id = trace.new()
    ngx.log(ngx.WARN, "virbius openresty generated trace_id=", trace_id)
end

local device_id = get_header("X-Virbius-Device-Id")
local session_id = get_header("X-Virbius-Session-Id")
local client_ip = ngx.var.remote_addr

local user_id
if conf.auth_mode == "required" then
    user_id = get_header("X-Authenticated-User-Id")
    if not user_id or user_id == "" then
        return json_exit(401, {
            code = "UNAUTHENTICATED",
            message = "authentication required",
            trace_id = trace_id,
        })
    end
else
    user_id = get_header("X-Virbius-User-Id")
end
local content = prompt.read_prompt_content()

ngx.req.clear_header("X-Virbius-Scene")
local route_uri = ngx.var.uri
local query = context_vars.query_args()
local headers_map = {}

local config_cache = config_redis.load(conf.tenant_id)
if not config_cache then
    ngx.log(ngx.WARN, "virbius openresty config_redis unavailable, falling back to file")
end
local lists_source = config_cache and config_cache.access_lists or conf.lists_file

local hits, vars_ctx, bindings, extended_defs = access_lists.check(
    lists_source,
    get_header,
    content,
    user_id,
    device_id,
    client_ip
)

local scene_id = nil
local registry = config_cache
    and scene_registry.load_from_cache(config_cache)
    or scene_registry.load_from_file(conf.scene_registry_file, conf.lists_file)
if registry then
    local resolved, src = scene_registry.resolve(registry, vars_ctx and vars_ctx.app_id, route_uri, query, headers_map)
    if resolved then
        scene_id = resolved
    elseif registry.fail_on_unknown_app or registry.fail_on_unresolved_scene then
        return json_exit(403, {
            code = "UNKNOWN_SCENE",
            message = "scene resolution failed: " .. tostring(src),
            trace_id = trace_id,
        })
    end
end
if not scene_id or scene_id == "" then
    scene_id = conf.scene_fallback or conf.scene
end
if not scene_id or scene_id == "" then
    return json_exit(403, {
        code = "UNKNOWN_SCENE",
        message = "scene not resolved",
        trace_id = trace_id,
    })
end
ngx.req.set_header("X-Virbius-Scene", scene_id)

vars_ctx = context_vars.filter_by_scope(vars_ctx, bindings, vars_ctx and vars_ctx.app_id, scene_id)

local ext_vars = context_vars.compute_extended_vars(vars_ctx, extended_defs, vars_ctx and vars_ctx.app_id, scene_id)
for k, v in pairs(ext_vars) do vars_ctx[k] = v end

local prior_signals = nil
if hits and #hits > 0 then
    local merged = access_lists.merge_actions(hits, session_id)
    if merged.effective_action == "block" and merged.primary then
        return json_exit(403, {
            code = "POLICY_BLOCK",
            message = "blocked by gateway access list",
            trace_id = trace_id,
            reason_code = merged.primary.reason_code,
            rule_id = merged.primary.rule_id,
            effective_action = "block",
            max_risk_score = merged.max_risk_score,
        })
    end
    if merged.effective_action == "challenge" and merged.primary then
        return json_exit(428, {
            code = "POLICY_CAPTCHA",
            message = "challenge required by gateway access list",
            trace_id = trace_id,
            reason_code = merged.primary.reason_code,
            rule_id = merged.primary.rule_id,
            effective_action = "challenge",
            max_risk_score = merged.max_risk_score,
        })
    end
    if merged.effective_action == "review" then
        prior_signals = access_lists.hits_to_prior_signals(hits)
    end
end

if content == "" then
    ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
    return
end

local agent_url = conf.agent_url or "http://127.0.0.1:9070"
local timeout_ms = conf.timeout_ms or 3000

local httpc = http.new()
httpc:set_timeout(timeout_ms)
local payload = json_util.encode({
    tenant_id = conf.tenant_id or "default",
    scene = scene_id,
    role = "user",
    content = content,
    trace_id = trace_id,
    session_id = session_id,
    user_id = user_id,
    device_id = device_id,
    client_ip = client_ip,
    vars = vars_ctx,
    prior_signals = prior_signals,
    route_uri = route_uri,
    query = query,
    headers = headers_map,
})
local res, req_err = httpc:request_uri(agent_url .. "/v1/evaluate", {
    method = "POST",
    body = payload,
    headers = { ["Content-Type"] = "application/json" },
})
if not res then
    ngx.log(ngx.ERR, "virbius openresty agent error: ", req_err)
    if conf.fail_mode == "close" then
        return json_exit(503, { code = "UNAVAILABLE", message = "agent unavailable" })
    end
    ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
    return
end

if res.status == 400 then
    local body = json_util.decode(res.body)
    return json_exit(400, body or { code = "INVALID_ARGUMENT", message = res.body })
end
if res.status >= 500 then
    if conf.fail_mode == "close" then
        return json_exit(503, { code = "UNAVAILABLE", message = "agent error" })
    end
    ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
    return
end

local out = json_util.decode(res.body)
if out and out.effective_action == "block" then
    return json_exit(403, {
        code = "POLICY_BLOCK",
        message = "blocked by virbius policy",
        trace_id = trace_id,
        reason_code = out.reason_code,
        rule_id = out.rule_id,
        effective_action = "block",
        max_risk_score = out.max_risk_score,
    })
end
if out and out.effective_action == "challenge" then
    return json_exit(428, {
        code = "POLICY_CAPTCHA",
        message = "challenge required by virbius policy",
        trace_id = trace_id,
        reason_code = out.reason_code,
        rule_id = out.rule_id,
        effective_action = "challenge",
        max_risk_score = out.max_risk_score,
    })
end

-- === Agent Tool Pre-check ===
-- If the request is a tool call (POST to /mcp/{app_id}/tools/call or /tools/call),
-- perform pre-check before proxying.

local function is_tool_call_request()
    local method = ngx.req.get_method()
    local uri = ngx.var.uri
    if method ~= "POST" then
        return false
    end
    if uri:match("/tools/call$") then
        return true
    end
    return false
end

local function read_tool_call_body()
    ngx.req.read_body()
    local body = ngx.req.get_body_data()
    if not body or body == "" then
        return nil
    end
    local ok, data = pcall(json_util.decode, body)
    if not ok or not data then
        return nil
    end
    return data
end

if is_tool_call_request() then
    local tool_data = read_tool_call_body()
    if tool_data then
        local tool_name = tool_data.tool_name or tool_data.name
        local args = tool_data.args or tool_data.parameters or {}
        local agent_app_id = conf.tenant_id .. ":" .. (tool_data.app_id or ngx.var.app_id or "default")

        -- 1. Tool allowlist check (reuse access_lists module)
        local tool_allowlist_check = access_lists.match(lists_source, "tool-allowlist", tool_name)
        if tool_allowlist_check == false then
            return json_exit(403, {
                code = "TOOL_NOT_ALLOWED",
                message = "tool '" .. tostring(tool_name) .. "' is not in allowlist",
                trace_id = trace_id,
                tool_name = tool_name,
            })
        end

        -- 2. Tool rate counter (reuse list_redis module)
        local session_tool_key = "tool:" .. tostring(tool_name) .. "-session:" .. tostring(session_id)
        local count = list_redis.get_cumulative(session_tool_key)
        local rate_limit = conf.tool_rate_limit or 50
        if count and count >= rate_limit then
            return json_exit(429, {
                code = "TOOL_RATE_EXCEEDED",
                message = "tool '" .. tostring(tool_name) .. "' rate limit exceeded (" .. rate_limit .. "/min)",
                trace_id = trace_id,
                tool_name = tool_name,
            })
        end

        -- 3. Fast path: skip engine call for low-risk tools with low session risk
        local fast_path_tools = conf.fast_path_tools or {}
        local session_risk = list_redis.get_cumulative("risk:session:" .. tostring(session_id)) or 0
        local is_fast_path = false
        for _, ft in ipairs(fast_path_tools) do
            if ft == tool_name then
                is_fast_path = true
                break
            end
        end

        if not is_fast_path or tonumber(session_risk) >= 30 then
            -- 4. Call virbius-engine for final evaluation
            local engine_url = conf.engine_url or "http://127.0.0.1:8082"
            local engine_timeout = conf.engine_timeout_ms or 3000

            local httpc = http.new()
            httpc:set_timeout(engine_timeout)

            local engine_payload = json_util.encode({
                trace_id = trace_id,
                session_id = session_id,
                tool_name = tool_name,
                args = args,
                app_id = agent_app_id,
                tenant_id = conf.tenant_id or "default",
                scene = scene_id or conf.scene,
                user_id = user_id,
                device_id = device_id,
            })

            local res, err = httpc:request_uri(engine_url .. "/v1/evaluate", {
                method = "POST",
                body = engine_payload,
                headers = { ["Content-Type"] = "application/json" },
            })

            if res then
                local out = json_util.decode(res.body)
                if out and out.effective_action == "block" then
                    return json_exit(403, {
                        code = "TOOL_BLOCKED",
                        message = "tool call blocked by virbius policy",
                        trace_id = trace_id,
                        tool_name = tool_name,
                        reason_code = out.reason_code,
                        rule_id = out.rule_id,
                        effective_action = "block",
                        max_risk_score = out.max_risk_score,
                    })
                end
            else
                ngx.log(ngx.ERR, "virbius gateway engine error: ", err)
                if conf.fail_mode == "close" then
                    return json_exit(503, { code = "ENGINE_UNAVAILABLE", message = "policy engine unavailable", trace_id = trace_id })
                end
                ngx.log(ngx.WARN, "virbius gateway fail_open for tool call: ", tool_name)
            end
        end
    end
end

ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
