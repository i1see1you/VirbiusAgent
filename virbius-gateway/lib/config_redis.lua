local json_util = require("json_util")

local _M = {}

local REDIS_TIMEOUT = tonumber(os.getenv("VIRBIUS_CONFIG_REDIS_TIMEOUT_MS")) or 200
local CACHE_TTL = tonumber(os.getenv("VIRBIUS_CONFIG_CACHE_TTL_SEC")) or 5

local caches = {}

local function redis_connect()
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
        return nil, "connect failed: " .. (err or "unknown")
    end
    return red, nil
end

local function fetch_config(tenant_id)
    local pointer_prefix = os.getenv("VIRBIUS_GATEWAY_POINTER_PREFIX") or "virbius:config:gateway"
    local blob_prefix = os.getenv("VIRBIUS_GATEWAY_BLOB_PREFIX") or "virbius:artifacts:gateway"

    local red, err = redis_connect()
    if not red then
        return nil, err
    end

    local pointer_key = pointer_prefix .. ":" .. tenant_id
    local raw, perr = red:hgetall(pointer_key)
    if not raw or #raw == 0 then
        red:set_keepalive(10000, 100)
        return nil, "no pointer for " .. tenant_id
    end

    local pointer = {}
    for i = 1, #raw, 2 do
        pointer[raw[i]] = raw[i + 1]
    end

    local revision = tonumber(pointer.artifact_revision)
    if not revision then
        red:set_keepalive(10000, 100)
        return nil, "no revision in pointer"
    end

    local al_key = blob_prefix .. ":" .. tenant_id .. ":r" .. revision .. ":access-lists"
    local al_raw, alerr = red:get(al_key)
    if not al_raw then
        red:set_keepalive(10000, 100)
        return nil, "failed to get access-lists: " .. (alerr or "unknown")
    end

    local sr_raw
    local sr_key = blob_prefix .. ":" .. tenant_id .. ":r" .. revision .. ":scene-registry"
    sr_raw, _ = red:get(sr_key)

    red:set_keepalive(10000, 100)

    local access_lists = json_util.decode(al_raw)
    if not access_lists then
        return nil, "failed to parse access-lists json"
    end

    local scene_registry
    if sr_raw then
        scene_registry = json_util.decode(sr_raw)
    end
    if not scene_registry and access_lists.scene_registry then
        scene_registry = { scene_registry = access_lists.scene_registry }
    end

    return {
        revision = revision,
        fetched_at = ngx.time(),
        access_lists = access_lists,
        scene_registry = scene_registry,
        access_lists_sha256 = pointer.access_lists_sha256,
        scene_registry_sha256 = pointer.scene_registry_sha256,
    }
end

function _M.load(tenant_id)
    tenant_id = tenant_id or "default"
    local now = ngx.time()
    local cache = caches[tenant_id]

    if cache and now - cache.fetched_at < CACHE_TTL then
        return cache, nil
    end

    local result, err = fetch_config(tenant_id)
    if result then
        caches[tenant_id] = result
        return result, nil
    end

    if cache then
        cache.fetched_at = now
        return cache, err
    end

    return nil, err
end

function _M.clear(tenant_id)
    if tenant_id then
        caches[tenant_id] = nil
    else
        caches = {}
    end
end

return _M
