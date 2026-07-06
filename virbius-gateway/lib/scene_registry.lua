-- scene_registry load + runtime resolve_scene(app_id, route_uri, query, headers).
local file_cache = require("file_cache")
local uri_match = require("uri_match")

local _M = {}

local registry_cache_key = "scene_registry"

function _M.load_from_file(path, lists_file_fallback)
    if path and path ~= "" then
        local root = file_cache.load_json(path, registry_cache_key .. ":" .. path)
        if root then
            return root.scene_registry or root
        end
    end
    if lists_file_fallback and lists_file_fallback ~= "" then
        local lists = file_cache.load_json(lists_file_fallback, "lists:" .. lists_file_fallback)
        if lists and lists.scene_registry then
            return lists.scene_registry
        end
    end
    return nil
end

function _M.load_from_cache(config_cache)
    if not config_cache then
        return nil
    end
    local sr = config_cache.scene_registry
    if sr then
        return sr.scene_registry or sr
    end
    local al = config_cache.access_lists
    if al and al.scene_registry then
        return al.scene_registry
    end
    return nil
end

local function default_scene_for_app(scenes, app_id)
    for scene_id, def in pairs(scenes or {}) do
        if def.app_id == app_id and def.default then
            return scene_id
        end
    end
    return nil
end

function _M.resolve(registry, app_id, route_uri, query, headers)
    if not registry or not app_id or app_id == "" then
        return nil, "missing_app_id"
    end
    local scenes = registry.scenes
    if not scenes then
        return nil, "no_scenes"
    end
    local known = false
    for _, def in pairs(scenes) do
        if def.app_id == app_id then
            known = true
            break
        end
    end
    if not known then
        if registry.fail_on_unknown_app then
            return nil, "unknown_app"
        end
        return default_scene_for_app(scenes, app_id), "default"
    end
    local candidates = {}
    for scene_id, def in pairs(scenes) do
        if def.app_id ~= app_id then
            goto continue
        end
        if not def.uris or #def.uris == 0 then
            goto continue
        end
        local uri_hit = false
        for _, pat in ipairs(def.uris) do
            if uri_match.uri_matches(route_uri, pat) then
                uri_hit = true
                break
            end
        end
        if not uri_hit then
            goto continue
        end
        local match = def.match or {}
        if not uri_match.match_map(match.query, query) then
            goto continue
        end
        if not uri_match.match_map(match.headers, headers) then
            goto continue
        end
        candidates[#candidates + 1] = {
            scene_id = scene_id,
            priority = def.priority or 0,
        }
        ::continue::
    end
    if #candidates > 0 then
        table.sort(candidates, function(a, b)
            return a.priority > b.priority
        end)
        return candidates[1].scene_id, "rule"
    end
    local def_scene = default_scene_for_app(scenes, app_id)
    if def_scene then
        return def_scene, "default"
    end
    if registry.fail_on_unresolved_scene then
        return nil, "unresolved"
    end
    return nil, "unresolved"
end

return _M
