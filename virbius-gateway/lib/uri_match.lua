-- URI pattern matching (shared with bind-scope / scene-registry OpenSpec).
local _M = {}

function _M.normalize_uri(raw)
    if not raw or raw == "" then
        return nil
    end
    local s = raw
    local q = string.find(s, "?", 1, true)
    if q then
        s = string.sub(s, 1, q - 1)
    end
    local h = string.find(s, "#", 1, true)
    if h then
        s = string.sub(s, 1, h - 1)
    end
    if string.sub(s, 1, 1) ~= "/" then
        s = "/" .. s
    end
    return s
end

function _M.uri_matches(route_uri, pattern)
    local uri = _M.normalize_uri(route_uri)
    local pat = _M.normalize_uri(pattern)
    if not uri or not pat then
        return false
    end
    if string.sub(pat, -1) == "*" then
        local prefix = string.sub(pat, 1, -2)
        return string.sub(uri, 1, string.len(prefix)) == prefix
    end
    return uri == pat
end

function _M.match_map(expected, actual)
    if not expected then
        return true
    end
    for k, v in pairs(expected) do
        local got = actual and actual[k]
        if got == nil or tostring(got) ~= tostring(v) then
            return false
        end
    end
    return true
end

return _M
