-- Load JSON files with mtime-based cache (macOS + Linux stat).
local json_util = require("json_util")

local _M = {}

local caches = {}

local function file_mtime(path)
    local cmd_bsd = 'stat -f %m "' .. path:gsub('"', '\\"') .. '" 2>/dev/null'
    local f = io.popen(cmd_bsd)
    if f then
        local out = f:read("*a")
        f:close()
        local m = tonumber(out)
        if m then
            return m
        end
    end
    local cmd_gnu = 'stat -c %Y "' .. path:gsub('"', '\\"') .. '" 2>/dev/null'
    f = io.popen(cmd_gnu)
    if f then
        local out = f:read("*a")
        f:close()
        return tonumber(out) or 0
    end
    return 0
end

function _M.load_json(path, cache_key)
    if not path or path == "" then
        return nil
    end
    local key = cache_key or path
    local mtime = file_mtime(path)
    local cache = caches[key]
    if cache and cache.path == path and cache.mtime == mtime and cache.data then
        return cache.data
    end
    local fh = io.open(path, "r")
    if not fh then
        return nil
    end
    local raw = fh:read("*a")
    fh:close()
    local data = json_util.decode(raw)
    if not data then
        return nil
    end
    caches[key] = { path = path, mtime = mtime, data = data }
    return data
end

function _M.clear()
    caches = {}
end

return _M
