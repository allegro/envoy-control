function envoy_on_request(handle)
    local path = handle:headers():get(":path")
    local method = handle:headers():get(":method")
    local xff_header = handle:headers():get("x-forwarded-for")
    local metadata = handle:streamInfo():dynamicMetadata()
    local client_identity_header_names = handle:metadata():get("client_identity_headers") or {}
    local request_id_header_names = handle:metadata():get("request_id_headers") or {}
    local request_id = first_header_value_from_list(request_id_header_names, handle)
    local trusted_header_name = handle:metadata():get("trusted_client_identity_header") or ""
    local client_name = ""
    if trusted_header_name ~= "" then
        client_name = handle:headers():get(trusted_header_name) or ""
    end

    if client_name == "" then
        client_name = first_header_value_from_list(client_identity_header_names, handle)
        if trusted_header_name ~= "" and client_name ~= "" and handle:connection():ssl() ~= nil then
            client_name = client_name .. " (not trusted)"
        end
    end

    metadata:set("envoy.filters.http.lua", "request.info.path", path)
    metadata:set("envoy.filters.http.lua", "request.info.method", method)
    metadata:set("envoy.filters.http.lua", "request.info.xff_header", xff_header)
    metadata:set("envoy.filters.http.lua", "request.info.client_name", client_name)
    metadata:set("envoy.filters.http.lua", "request.info.request_id", request_id)
end

function first_header_value_from_list(header_list, handle)
    for _,h in ipairs(header_list) do
        local value = handle:headers():get(h) or ""
        if value ~= "" then
            return value
        end
    end

    return ""
end

function envoy_on_response(handle)
    local rbacMetadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.rbac")

    if rbacMetadata == nil or rbacMetadata["shadow_engine_result"] ~= "denied" then
        return
    end

    local lua_metadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua") or {}
    local client_name = lua_metadata["request.info.client_name"] or ""
    local path = lua_metadata["request.info.path"] or ""
    local protocol = handle:connection():ssl() == nil and "http" or "https"
    local method = lua_metadata["request.info.method"] or ""
    local xff_header = lua_metadata["request.info.xff_header"] or ""
    local source_ip = string.match(xff_header, '[^,]+$') or ""
    local request_id = lua_metadata["request.info.request_id"] or ""
    local statusCode = handle:headers():get(":status") or "0"
    handle:logInfo("\nINCOMING_PERMISSIONS { \"method\": \""..method.."\", \"path\": \""..path.."\", \"clientIp\": \""..source_ip.."\", \"clientName\": \""..escape(client_name).."\", \"protocol\": \""..protocol.."\", \"requestId\": \""..escape(request_id).."\", \"statusCode\": "..statusCode.." }")
end

escapeList = {
    ["\\"] = "\\\\",
    ["\""] = "\\\"",
    ["\b"] = "\\b",
    ["\f"] = "\\f",
    ["\n"] = "\\n",
    ["\r"] = "\\r",
    ["\t"] = "\\t",
}

function escape(val)
    return string.gsub(val, "[\\\"\b\f\n\r\t]", escapeList)
end
