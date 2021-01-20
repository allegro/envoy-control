function envoy_on_request(handle)
    local path = handle:headers():get(":path")
    local method = handle:headers():get(":method")
    local xff_header = handle:headers():get("x-forwarded-for")
    local metadata = handle:streamInfo():dynamicMetadata()
    local client_identity_header_names = handle:metadata():get("client_identity_headers") or {}
    local clients_allowed_to_all_endpoints = handle:metadata():get("clients_allowed_to_all_endpoints") or {}
    local request_id_header_names = handle:metadata():get("request_id_headers") or {}
    local request_id = first_header_value_from_list(request_id_header_names, handle)
    local trusted_header_name = handle:metadata():get("trusted_client_identity_header") or ""
    local client_name = ""
    local allowed_client = false
    local trusted_client = false
    if trusted_header_name ~= "" then
        client_name = handle:headers():get(trusted_header_name) or ""
        allowed_client = is_allowed_client(client_name, clients_allowed_to_all_endpoints)
        if client_name ~= "" then
            trusted_client = true
        end
    end

    if client_name == "" then
        client_name = first_header_value_from_list(client_identity_header_names, handle)
        allowed_client = is_allowed_client(client_name, clients_allowed_to_all_endpoints)
        if trusted_header_name ~= "" and client_name ~= "" and handle:connection():ssl() ~= nil then
            client_name = client_name .. " (not trusted)"
        end
    end

    metadata:set("envoy.filters.http.lua", "request.info.path", path)
    metadata:set("envoy.filters.http.lua", "request.info.method", method)
    metadata:set("envoy.filters.http.lua", "request.info.xff_header", xff_header)
    metadata:set("envoy.filters.http.lua", "request.info.client_name", client_name)
    metadata:set("envoy.filters.http.lua", "request.info.trusted_client", trusted_client)
    metadata:set("envoy.filters.http.lua", "request.info.allowed_client", allowed_client)
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

function is_allowed_client(client_name, clients_allowed_to_all_endpoints)
    for _,h in ipairs(clients_allowed_to_all_endpoints) do
        if client_name ~= "" and h ~= "" and client_name == h then
            return true
        end
    end

    return false
end

function envoy_on_response(handle)
    local rbacMetadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.rbac") or {}
    local is_shadow_denied = (rbacMetadata["shadow_engine_result"] or "") == "denied"
    local lua_metadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua") or {}
    local allowed_client = lua_metadata["request.info.allowed_client"] or false
    local upstream_request_time = handle:headers():get("x-envoy-upstream-service-time")
    local rbac_action = "shadow_denied"

    if is_shadow_denied and upstream_request_time == nil then
        rbac_action = "denied"
    end

    if should_log_request(is_shadow_denied, allowed_client) then
        log_request(lua_metadata, handle, rbac_action)
    end
end

function should_log_request(is_shadow_denied, allowed_client)
    if (not is_shadow_denied) and allowed_client == true then
        return true
    else
        return is_shadow_denied
    end
end

function log_request(lua_metadata, handle, rbac_action)
    local client_name = lua_metadata["request.info.client_name"] or ""
    local trusted_client = lua_metadata["request.info.trusted_client"] or false
    local allowed_client = lua_metadata["request.info.allowed_client"] or false
    local path = lua_metadata["request.info.path"] or ""
    local protocol = handle:connection():ssl() == nil and "http" or "https"
    local method = lua_metadata["request.info.method"] or ""
    local xff_header = lua_metadata["request.info.xff_header"] or ""
    local source_ip = string.match(xff_header, '[^,]+$') or ""
    local request_id = lua_metadata["request.info.request_id"] or ""
    local statusCode = handle:headers():get(":status") or "0"
    handle:logInfo("\nINCOMING_PERMISSIONS { \"method\": \""..method..
        "\", \"path\": \""..path..
        "\", \"clientIp\": \""..source_ip..
        "\", \"clientName\": \""..escape(client_name)..
        "\", \"trustedClient\": "..tostring(trusted_client)..
        ", \"clientAllowedToAllEndpoints\": "..tostring(allowed_client)..
        ", \"protocol\": \""..protocol..
        "\", \"requestId\": \""..escape(request_id)..
        "\", \"statusCode\": "..statusCode..
        ", \"rbacAction\": \""..rbac_action.."\" }")
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
