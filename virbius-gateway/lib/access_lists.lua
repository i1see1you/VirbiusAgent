-- Gateway access list matching + enforce merge (MVP; bind_scope filter in P2).
local file_cache = require("file_cache")
local context_vars = require("context_vars")
local list_redis = require("list_redis")

local _M = {}

local function keyword_hit(content, keywords)
    if not content or content == "" or not keywords then
        return false
    end
    local lower = string.lower(content)
    for _, kw in ipairs(keywords) do
        if kw and kw ~= "" then
            if kw:match("[\128-\255]") or kw:match("[\194-\244]") then
                if content:find(kw, 1, true) then
                    return true
                end
            elseif lower:find(string.lower(kw), 1, true) then
                return true
            end
        end
    end
    return false
end

local function ip_to_num(ip)
    local a, b, c, d = ip:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
    if not a then
        return nil
    end
    return tonumber(a) * 16777216 + tonumber(b) * 65536 + tonumber(c) * 256 + tonumber(d)
end

local function ip_in_cidr(ip, cidr)
    local base, prefix = cidr:match("^([^/]+)/(%d+)$")
    if not base then
        return ip == cidr
    end
    local ipn = ip_to_num(ip)
    local basen = ip_to_num(base)
    prefix = tonumber(prefix)
    if not ipn or not basen or not prefix or prefix > 32 then
        return false
    end
    local bit = require("bit")
    local mask = 0
    if prefix > 0 then
        mask = bit.bnot(bit.rshift(0xffffffff, prefix))
    end
    return bit.band(ipn, mask) == bit.band(basen, mask)
end

local function ip_in_any(ip, cidrs)
    if not ip or not cidrs then
        return false
    end
    for _, cidr in ipairs(cidrs) do
        if ip_in_cidr(ip, cidr) then
            return true
        end
    end
    return false
end

local function in_set(val, arr)
    if not val or not arr then
        return false
    end
    for _, v in ipairs(arr) do
        if v == val then
            return true
        end
    end
    return false
end

local function var_logical_name(dim)
    if type(dim) ~= "string" or #dim < 5 then
        return nil
    end
    if dim:sub(1, 4) ~= "var:" then
        return nil
    end
    local logical = dim:sub(5)
    if logical == "" then
        return nil
    end
    return logical
end

local function memory_list_blocks(doc)
    if not doc then
        return {}
    end
    return doc.memory_lists or {}
end

local function entry_active(expires_at)
    if not expires_at or expires_at == "" then
        return true
    end
    local y, m, d, hh, mm, ss = expires_at:match("^(%d+)%-(%d+)%-(%d+)T(%d+):(%d+):(%d+)")
    if not y then
        return true
    end
    local exp = os.time({
        year = tonumber(y),
        month = tonumber(m),
        day = tonumber(d),
        hour = tonumber(hh),
        min = tonumber(mm),
        sec = tonumber(ss),
    })
    return exp > ngx.time()
end

local function active_values(entries)
    local out = {}
    if not entries then
        return out
    end
    for _, e in ipairs(entries) do
        local val = e
        local exp = nil
        if type(e) == "table" then
            val = e.value
            exp = e.expires_at
        end
        if val and val ~= "" and entry_active(exp) then
            out[#out + 1] = val
        end
    end
    return out
end

local function resolve_lookup_value(dim, content, user_id, device_id, client_ip, vars_map)
    if dim == "keyword" or dim == "content" then
        return content
    end
    if dim == "user_id" then
        return user_id
    end
    if dim == "device_id" then
        return device_id
    end
    if dim == "ip_cidr" or dim == "ip" then
        return client_ip
    end
    return nil
end

local function match_redis_list_block(block, tenant_id, content, user_id, device_id, client_ip, vars_map)
    local dim = block.dimension or ""
    local redis_key = block.redis_key
    if not redis_key or redis_key == "" then
        return false
    end
    local logical = var_logical_name(dim)
    if logical then
        local val = vars_map and vars_map[logical]
        if not val or val == "" then
            return false
        end
        return list_redis.match(redis_key, tenant_id, block.list_name, val)
    end
    local lookup = resolve_lookup_value(dim, content, user_id, device_id, client_ip, vars_map)
    if not lookup or lookup == "" then
        return false
    end
    return list_redis.match(redis_key, tenant_id, block.list_name, lookup)
end

local function collect_redis_list_hits(redis_index, tenant_id, content, user_id, device_id, client_ip, vars_map)
    if not redis_index or #redis_index == 0 then
        return {}
    end
    local hits = {}
    for _, block in ipairs(redis_index) do
        if match_redis_list_block(block, tenant_id, content, user_id, device_id, client_ip, vars_map) then
            local score = block.risk_score or 100
            if score <= 0 then
                return {}
            end
            hits[#hits + 1] = {
                rule_id = block.rule_id or block.list_name or "list_match",
                rule_revision = block.rule_revision or 1,
                reason_code = block.reason_code or "LIST_MATCH",
                risk_score = score,
                intent_action = block.intent_action or normalize_intent(nil, score),
                enforce_mode = block.enforce_mode or "dry_run",
                canary_percent = block.canary_percent,
            }
        end
    end
    return hits
end

local function match_list_block(block, content, user_id, device_id, client_ip, vars_map)
    local dim = block.dimension or ""
    local values = active_values(block.entries)
    if #values == 0 then
        return false
    end
    if dim == "keyword" then
        return keyword_hit(content, values)
    end
    if dim == "user_id" then
        return in_set(user_id, values)
    end
    if dim == "device_id" then
        return in_set(device_id, values)
    end
    if dim == "ip_cidr" or dim == "ip" then
        return ip_in_any(client_ip, values)
    end
    local logical = var_logical_name(dim)
    if logical then
        local val = vars_map and vars_map[logical]
        return val and val ~= "" and in_set(val, values)
    end
    return false
end

local function in_canary_bucket(session_id, percent)
    if not percent then
        return false
    end
    if percent >= 100 then
        return true
    end
    if percent <= 0 then
        return false
    end
    local key = session_id
    if not key or key == "" then
        key = "default"
    end
    local bucket = ngx.crc32_long(key) % 100
    return bucket < percent
end

local function normalize_mode(mode)
    if not mode or mode == "" then
        return "dry_run"
    end
    return string.lower(mode)
end

local function intent_priority(intent)
    if not intent or intent == "" then
        return 0
    end
    intent = string.lower(intent)
    if intent == "deny" then
        return 100
    elseif intent == "captcha" then
        return 50
    elseif intent == "review" then
        return 30
    end
    return 0
end

local function is_allow_intent(intent)
    if not intent or intent == "" then
        return false
    end
    return string.lower(intent) == "allow"
end

local function normalize_intent(intent, risk_score)
    if intent and intent ~= "" then
        intent = string.lower(intent)
        if intent == "allow" or intent == "deny" or intent == "captcha" or intent == "review" then
            return intent
        end
    end
    risk_score = risk_score or 0
    if risk_score >= 100 then
        return "deny"
    elseif risk_score <= 0 then
        return "allow"
    end
    return "review"
end

local function is_full(mode)
    return normalize_mode(mode) == "full"
end

local function is_canary_effective(hit, session_id)
    if normalize_mode(hit.enforce_mode) ~= "canary" then
        return false
    end
    return in_canary_bucket(session_id, hit.canary_percent)
end

local function effective_enforce(hits, session_id)
    for _, h in ipairs(hits) do
        if is_full(h.enforce_mode) then
            return true
        end
    end
    for _, h in ipairs(hits) do
        if is_canary_effective(h, session_id) then
            return true
        end
    end
    return false
end

local function pick_primary(hits)
    local primary = hits[1]
    for _, h in ipairs(hits) do
        if (h.risk_score or 0) > (primary.risk_score or 0) then
            primary = h
        elseif (h.risk_score or 0) == (primary.risk_score or 0)
            and (h.rule_revision or 1) > (primary.rule_revision or 1) then
            primary = h
        elseif (h.risk_score or 0) == (primary.risk_score or 0)
            and (h.rule_revision or 1) == (primary.rule_revision or 1)
            and (h.rule_id or "") > (primary.rule_id or "") then
            primary = h
        end
    end
    return primary
end

function _M.merge_actions(hits, session_id)
    if not hits or #hits == 0 then
        return { effective_action = "allow", max_risk_score = 0, primary = nil }
    end
    for _, h in ipairs(hits) do
        if is_allow_intent(h.intent_action) then
            return { effective_action = "allow", max_risk_score = 0, primary = nil }
        end
    end
    local max_priority = 0
    for _, h in ipairs(hits) do
        local p = intent_priority(h.intent_action)
        if p > max_priority then
            max_priority = p
        end
    end
    if max_priority <= 0 then
        return { effective_action = "allow", max_risk_score = 0, primary = nil }
    end
    local top = {}
    for _, h in ipairs(hits) do
        if intent_priority(h.intent_action) == max_priority then
            top[#top + 1] = h
        end
    end
    local max_risk_score = 0
    for _, h in ipairs(top) do
        if (h.risk_score or 0) > max_risk_score then
            max_risk_score = h.risk_score or 0
        end
    end
    local primary = pick_primary(top)
    local intent = normalize_intent(primary.intent_action, primary.risk_score)
    local effective = effective_enforce(top, session_id)
    local effective_action = "allow"
    if intent == "deny" then
        effective_action = effective and "block" or "review"
    elseif intent == "captcha" then
        effective_action = effective and "captcha" or "review"
    elseif intent == "review" then
        effective_action = "review"
    end
    return { effective_action = effective_action, max_risk_score = max_risk_score, primary = primary }
end

function _M.hits_to_prior_signals(hits)
    local out = {}
    for _, h in ipairs(hits) do
        if not is_allow_intent(h.intent_action) then
            out[#out + 1] = {
                rule_id = h.rule_id,
                rule_revision = h.rule_revision or 1,
                source = "gateway",
                layer = "gateway",
                score = h.risk_score,
                intent_action = h.intent_action or normalize_intent(nil, h.risk_score),
                reason_code = h.reason_code,
                enforce_mode = h.enforce_mode or "dry_run",
                canary_percent = h.canary_percent,
            }
        end
    end
    return out
end

local function collect_named_list_hits(lists, content, user_id, device_id, client_ip, vars_map)
    if not lists then
        return {}
    end
    local hits = {}
    for _, block in ipairs(lists) do
        if match_list_block(block, content, user_id, device_id, client_ip, vars_map) then
            local score = block.risk_score or 100
            if score <= 0 then
                return {}
            end
            hits[#hits + 1] = {
                rule_id = block.rule_id or block.list_name or "list_match",
                rule_revision = block.rule_revision or 1,
                reason_code = block.reason_code or "LIST_MATCH",
                risk_score = score,
                intent_action = block.intent_action or normalize_intent(nil, score),
                enforce_mode = block.enforce_mode or "dry_run",
                canary_percent = block.canary_percent,
            }
        end
    end
    return hits
end

function _M.check(lists_source, get_header, content, user_id, device_id, client_ip)
    local lists
    if type(lists_source) == "table" then
        lists = lists_source
    else
        lists = file_cache.load_json(lists_source, "access_lists:" .. lists_source)
    end
    if not lists then
        return nil, {}, {}, {}
    end
    local bindings = lists.context_bindings or {}
    local extended_defs = lists.extended_vars or {}
    local vars_ctx = context_vars.resolve(bindings, get_header, user_id, device_id, client_ip)
    local tenant_id = lists.tenant_id or "default"
    local memory_blocks = memory_list_blocks(lists)
    local hits = collect_named_list_hits(memory_blocks, content, user_id, device_id, client_ip, vars_ctx)
    local redis_hits = collect_redis_list_hits(
        lists.redis_list_index, tenant_id, content, user_id, device_id, client_ip, vars_ctx)
    for _, h in ipairs(redis_hits) do
        hits[#hits + 1] = h
    end
    if #hits == 0 then
        return nil, vars_ctx, bindings, extended_defs
    end
    return hits, vars_ctx, bindings, extended_defs
end

return _M
