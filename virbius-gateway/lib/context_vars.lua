-- context_bindings → logical vars (header / query / subject / network).
local _M = {}

local function query_args()
    return ngx.req.get_uri_args() or {}
end

local function query_value(name)
    local args = query_args()
    local val = args[name]
    if type(val) == "table" then
        return val[1]
    end
    if val == nil then
        return nil
    end
    return tostring(val)
end

function _M.query_args()
    return query_args()
end

function _M.resolve(bindings, get_header, user_id, device_id, client_ip)
    local out = {}
    local defs = bindings and bindings.vars or {}
    for logical, def in pairs(defs) do
        local from = def.from
        local val = nil
        if from == "query" and def.name then
            val = query_value(def.name)
        elseif from == "header" and def.name and get_header then
            val = get_header(def.name)
        elseif from == "subject" and def.field == "user_id" then
            val = user_id
        elseif from == "subject" and def.field == "device_id" then
            val = device_id
        elseif from == "network" and def.field == "ip" then
            val = client_ip
        end
        if val and val ~= "" then
            out[logical] = tostring(val)
        end
    end
    return out
end

--- Post-filter resolved vars by scope definition.
--- Keeps only vars whose scope matches the current app_id / scene.
--- @param vars table  resolved vars
--- @param bindings table  context_bindings block (with .vars)
--- @param app_id string|nil
--- @param scene string|nil
--- @return table filtered vars
function _M.filter_by_scope(vars, bindings, app_id, scene)
    local defs = bindings and bindings.vars or {}
    local out = {}
    for logical, val in pairs(vars) do
        local def = defs[logical]
        if def and def.scope and def.scope.bind_scope then
            local s = def.scope
            if s.bind_scope == "service" then
                if s.app_ids and type(s.app_ids) == "table" and app_id then
                    local matched = false
                    for _, id in ipairs(s.app_ids) do
                        if id == app_id then matched = true; break end
                    end
                    if matched then out[logical] = val end
                    -- else skip
                end
                -- no app_ids or no app_id → keep (conservative)
            elseif s.bind_scope == "route" then
                if s.scenes and type(s.scenes) == "table" and scene then
                    local matched = false
                    for _, sc in ipairs(s.scenes) do
                        if sc == scene then matched = true; break end
                    end
                    if matched then out[logical] = val end
                    -- else skip
                end
            else
                out[logical] = val
            end
        else
            out[logical] = val
        end
    end
    return out
end

--- Compute extended vars from Lua expressions referencing resolved request-factor vars.
--- @param vars table  resolved & scope-filtered request-factor vars
--- @param extended_defs table  extended_vars block (with .vars)
--- @param app_id string|nil
--- @param scene string|nil
--- @return table computed extended vars
function _M.compute_extended_vars(vars, extended_defs, app_id, scene)
    local defs = extended_defs and extended_defs.vars or {}
    local out = {}
    for logical, def in pairs(defs) do
        local s = def.scope
        if s and s.bind_scope then
            if s.bind_scope == "service" then
                if s.app_ids and type(s.app_ids) == "table" and app_id then
                    local matched = false
                    for _, id in ipairs(s.app_ids) do
                        if id == app_id then matched = true; break end
                    end
                    if not matched then goto continue end
                end
            elseif s.bind_scope == "route" then
                if s.scenes and type(s.scenes) == "table" and scene then
                    local matched = false
                    for _, sc in ipairs(s.scenes) do
                        if sc == scene then matched = true; break end
                    end
                    if not matched then goto continue end
                end
            end
        end
        local expr = def.expr
        if expr and expr ~= "" then
            local ctx_proxy = { var = function(name) return vars[name] end }
            local fn, err = load("return " .. expr, "@ext_" .. logical, "t", { ctx = ctx_proxy })
            if fn then
                local ok, val = pcall(fn)
                if ok and val ~= nil and val ~= "" then
                    out[logical] = tostring(val)
                end
            elseif err then
                ngx.log(ngx.WARN, "extended var " .. logical .. " load error: " .. err)
            end
        end
        ::continue::
    end
    return out
end

return _M
