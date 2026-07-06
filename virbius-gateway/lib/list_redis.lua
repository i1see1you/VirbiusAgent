-- Redis ZSET list match (user_id / device_id / var) with TTL match-result cache.

local _M = {}

local CACHE_TTL = tonumber(os.getenv("VIRBIUS_LIST_MATCH_CACHE_TTL_SEC")) or 60
local REDIS_TIMEOUT = tonumber(os.getenv("VIRBIUS_LIST_REDIS_TIMEOUT_MS")) or 50
local match_cache = {}

local function cache_get(key, now)
    local e = match_cache[key]
    if not e then
        return nil
    end
    if now - e.cached_at > CACHE_TTL then
        match_cache[key] = nil
        return nil
    end
    if e.hit and e.score and e.score > 0 and e.score <= now then
        match_cache[key] = nil
        return nil
    end
    return e.hit
end

local function cache_put(key, hit, score, now)
    match_cache[key] = { hit = hit, score = score or 0, cached_at = now }
end

local function redis_zscore(redis_key, member)
    local redis = require("resty.redis")
    local red = redis:new()
    red:set_timeout(REDIS_TIMEOUT)
    local redis_url = os.getenv("VIRBIUS_REDIS_URL") or "127.0.0.1:6379"
    local host, port = redis_url:match("^redis://([^:/]+):?(%d*)")
    if not host then
        host, port = redis_url:match("^([^:/]+):?(%d*)")
    end
    host = host or "127.0.0.1"
    port = tonumber(port) or 6379
    local ok, err = red:connect(host, port)
    if not ok then
        ngx.log(ngx.WARN, "virbius list redis connect failed: ", err)
        return nil
    end
    local score, rerr = red:zscore(redis_key, member)
    red:set_keepalive(10000, 100)
    if score == ngx.null or score == nil then
        return nil
    end
    if rerr then
        ngx.log(ngx.WARN, "virbius list redis zscore failed: ", rerr)
        return nil
    end
    return tonumber(score)
end

local function is_active(score, now)
    if score == nil then
        return false
    end
    if score == 0 then
        return true
    end
    return score > now
end

function _M.match(redis_key, tenant_id, list_name, lookup_value)
    if not lookup_value or lookup_value == "" or not redis_key or redis_key == "" then
        return false
    end
    local now = ngx.time()
    local cache_key = (tenant_id or "") .. ":" .. (list_name or "") .. ":" .. lookup_value
    local cached = cache_get(cache_key, now)
    if cached ~= nil then
        return cached
    end
    local score = redis_zscore(redis_key, lookup_value)
    if score == nil then
        cache_put(cache_key, false, 0, now)
        return false
    end
    local hit = is_active(score, now)
    cache_put(cache_key, hit, score, now)
    return hit
end

return _M
