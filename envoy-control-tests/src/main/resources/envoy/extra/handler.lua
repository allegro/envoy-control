local M = {}

local url = require("net.url")

function M:envoy_on_request(request_handle)

    local path = request_handle:headers():get(":path")
    if path == nil then
        return
    end

    local serviceTags = url.getQueryParamValues(path, "service-tag")
    if serviceTags == nil or #serviceTags == 0 then
        return
    end

    local tags = {}
    for _,tag in ipairs(serviceTags) do
        if tag == "canary" then
            request_handle:streamInfo():dynamicMetadata():set("envoy.lb", "canary", "1")
        else
            tags[#tags+1] = tag
        end
    end
    if #tags == 0 then
        return
    end
    table.sort(tags)
    local tagsJoined = table.concat(tags, ",")
    request_handle:streamInfo():dynamicMetadata():set("envoy.lb", "tag", tagsJoined)
end

return M