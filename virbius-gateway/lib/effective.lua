-- Load compiler-emitted effective route JSON (OpenResty path A).
local file_cache = require("file_cache")

local _M = {}

function _M.load(path)
    if not path or path == "" then
        return nil, "missing effective config path"
    end
    local doc = file_cache.load_json(path, "effective:" .. path)
    if not doc then
        return nil, "cannot read effective config: " .. path
    end
    if not doc.virbius then
        return nil, "effective config missing virbius block"
    end
    return doc, nil
end

return _M
