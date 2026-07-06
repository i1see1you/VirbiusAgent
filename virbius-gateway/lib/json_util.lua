-- JSON helpers (OpenResty cjson.safe; APISIX may override via env).
local _M = {}

local cjson_ok, cjson = pcall(require, "cjson.safe")
if not cjson_ok then
    cjson = nil
end

function _M.set_codec(encode_fn, decode_fn)
    _M.encode = encode_fn
    _M.decode = decode_fn
end

if not _M.encode then
    function _M.encode(val)
        if cjson then
            return cjson.encode(val)
        end
        error("no JSON encoder available")
    end
end

if not _M.decode then
    function _M.decode(raw)
        if not raw or raw == "" then
            return nil
        end
        if cjson then
            return cjson.decode(raw)
        end
        error("no JSON decoder available")
    end
end

return _M
