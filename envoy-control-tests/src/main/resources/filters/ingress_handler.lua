local M = {}

function M:envoy_on_request(handle)
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

function M:envoy_on_response(handle)
    handle:logInfo("running envoy_on_response")
    local rbacMetadata = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.rbac")

    if rbacMetadata ~= nil then
        local service_name = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.service_name"]
        if service_name == nil then service_name = "" end

        local shadow_engine_result = rbacMetadata["shadow_engine_result"]
        if shadow_engine_result == "denied" then

            local path = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.path"]

            local protocol = "https"
            -- TODO: https://github.com/envoyproxy/envoy/blob/f6b86a58b264b46a57d71a9b3b0989b2969df408/include/envoy/network/connection.h#L224
            -- TODO: StreamInfo::downstreamSslConnection.
            if handle:connection():ssl() == nil then
                protocol = "http"
            end

            local method = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.method"]
            local xff_header = handle:streamInfo():dynamicMetadata():get("envoy.filters.http.lua")["request.info.xff_header"]

            local source_ip
            if xff_header ~= nil then
                source_ip = string.match(xff_header, '([^,]+)$') -- match everything except comma at the end ($)
            end
            if source_ip == nil then source_ip = "" end

            -- TODO(mfalkowski): print if this request has been blocked or only logged (using rbacMetadata["engine_result"])
            -- TODO(awawrzyniak): there is no key engine_result in rbacMetadata structure
            -- TODO(mfalkowski): so let's print a response code instead. If it is 403 we are 95% sure that request was blocked

            local statusCode = handle:headers():get(":status")
            handle:logInfo("\nAccess denied for request: method = "..method..", path = "..path..", clientIp = "..source_ip..", clientName = "..service_name..", protocol = "..protocol..", statusCode = "..statusCode)
        end
    end
end

return M