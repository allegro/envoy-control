function envoy_on_request(handle)
    local path = handle:headers():get(":path")
    local method = handle:headers():get(":method")
    local service_name = handle:headers():get("x-service-name")
    local xff_header = handle:headers():get("x-forwarded-for")
    local metadata = handle:streamInfo():dynamicMetadata()
    metadata:set("envoy.filters.http.lua", "request.info.path", path)
    metadata:set("envoy.filters.http.lua", "request.info.method", method)
    metadata:set("envoy.filters.http.lua", "request.info.service_name", service_name)
    metadata:set("envoy.filters.http.lua", "request.info.xff_header", xff_header)
end

function envoy_on_response(handle)
    local rbacMetadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.rbac")

    if rbacMetadata == nil or rbacMetadata["shadow_engine_result"] ~= "denied" then
        return
    end

    local lua_metadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua") or {}
    local service_name = lua_metadata["request.info.service_name"] or ""
    local path = lua_metadata["request.info.path"] or ""
    local protocol = handle:connection():ssl() == nil and "http" or "https"
    local method = lua_metadata["request.info.method"] or ""
    local xff_header = lua_metadata["request.info.xff_header"] or ""
    local source_ip = string.match(xff_header, '[^,]+$') or ""
    local statusCode = handle:headers():get(":status") or "0"
    handle:logInfo("\nINCOMING_PERMISSIONS { \"method\": \""..method.."\", \"path\": \""..path.."\", \"clientIp\": \""..source_ip.."\", \"clientName\": \""..service_name.."\", \"protocol\": \""..protocol.."\", \"statusCode\": "..statusCode.." }")
end
