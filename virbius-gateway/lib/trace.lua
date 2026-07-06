-- Trace id validation (UUID v4) and generation.
local _M = {}

local UUID_V4 =
    "^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89abAB]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$"

function _M.valid(id)
    if not id or id == "" or #id ~= 36 then
        return false
    end
    return id:match(UUID_V4) ~= nil
end

function _M.new()
    local ok, uuid = pcall(require, "resty.jit-uuid")
    if ok then
        uuid.seed()
        return uuid()
    end
    -- Pure-Lua UUID v4 fallback when resty.jit-uuid is unavailable.
    local hex = "0123456789abcdef"
    local function rand_hex(n)
        local out = {}
        for _ = 1, n do
            local i = math.random(1, 16)
            out[#out + 1] = hex:sub(i, i)
        end
        return table.concat(out)
    end
    math.randomseed(ngx.now() * 1000 + ngx.worker.pid())
    return string.format(
        "%s-%s-4%s-%s%s-%s",
        rand_hex(8),
        rand_hex(4),
        rand_hex(3),
        hex:sub(math.random(9, 12), math.random(9, 12)),
        rand_hex(3),
        rand_hex(12)
    )
end

return _M
