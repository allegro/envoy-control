function envoy_on_request(handle)
    handle:logInfo("running envoy_on_request")
    local path = handle:headers():get(":path")
    local method = handle:headers():get(":method")
    local service_name = handle:headers():get("x-service-name")
    local xff_header = handle:headers():get("x-forwarded-for")
    handle:streamInfo():dynamicMetadata():set("envoy.filters.http.lua", "request.info.path", path)
    handle:streamInfo():dynamicMetadata():set("envoy.filters.http.lua", "request.info.method", method)
    handle:streamInfo():dynamicMetadata():set("envoy.filters.http.lua", "request.info.service_name", service_name) -- this should be read from certificate
    handle:streamInfo():dynamicMetadata():set("envoy.filters.http.lua", "request.info.xff_header", xff_header)
end

function envoy_on_response(handle)
    handle:logInfo("running envoy_on_response")
    local path = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.path"]
    local method = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.method"]
    local service_name = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.service_name"]
    local xff_header = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.xff_header"]

    local rbacMetadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.rbac")
    local shadow_engine_result = rbacMetadata["shadow_engine_result"]
    if rbacMetadata ~= nil then
        if service_name == nil then service_name = "" end
        if shadow_engine_result == "denied" then
            local source_ip
            if xff_header ~= nil then
                source_ip = string.match(xff_header, '([^,]+)$') -- match everything except comma at the end ($)
            end
            if source_ip == nil then source_ip = "" end

            -- TODO(mfalkowski): print if this request has been blocked or only logged (using rbacMetadata["engine_result"])
            handle:logInfo("\nRBAC: Permission denied: shadow_engine: "..shadow_engine_result..", path: "..path..", method: "..method..", service-name: "..service_name..", ip: "..source_ip)
        end
    end
end
