function envoy_on_request(handle)
    local metadata = handle:metadata()
    local client_identity_header_names = metadata:get('client_identity_headers') or {}
    local clients_allowed_to_all_endpoints = metadata:get('clients_allowed_to_all_endpoints') or {}
    local request_id_header_names = metadata:get('request_id_headers') or {}
    local trusted_header_name = metadata:get('trusted_client_identity_header') or ''

    local headers = handle:headers()
    local path = headers:get(':path')
    local method = headers:get(':method')
    local authority = headers:get(':authority') or ''
    local lua_destination_authority = headers:get('x-lua-destination-authority') or ''
    local xff_header = headers:get('x-forwarded-for')

    local request_id = first_header_value_from_list(request_id_header_names, headers)
    local client_name = ''
    local allowed_client = false
    local trusted_client = false
    if trusted_header_name ~= '' then
        client_name = headers:get(trusted_header_name) or ''
        allowed_client = is_allowed_client(client_name, clients_allowed_to_all_endpoints)
        if client_name ~= '' then
            trusted_client = true
        end
    end

    if client_name == '' then
        client_name = first_header_value_from_list(client_identity_header_names, headers)
        allowed_client = is_allowed_client(client_name, clients_allowed_to_all_endpoints)
        if trusted_header_name ~= '' and client_name ~= '' and handle:connection():ssl() ~= nil then
            client_name = client_name .. ' (not trusted)'
        end
    end

    local dynamic_metadata = handle:streamInfo():dynamicMetadata()
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.path', path)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.method', method)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.xff_header', xff_header)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.client_name', client_name)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.trusted_client', trusted_client)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.allowed_client', allowed_client)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.request_id', request_id)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.authority', authority)
    dynamic_metadata:set('envoy.filters.http.lua', 'request.info.lua_destination_authority', lua_destination_authority)
    local headers_to_log = metadata:get('rbac_headers_to_log') or {}
    for _, header in ipairs(headers_to_log) do
        dynamic_metadata:set('envoy.filters.http.lua', 'request.info.headers.'..header, headers:get(header))
    end
end

function first_header_value_from_list(header_list, headers)
    for _,h in ipairs(header_list) do
        local value = headers:get(h) or ''
        if value ~= '' then
            return value
        end
    end

    return ''
end

function is_allowed_client(client_name, clients_allowed_to_all_endpoints)
    if client_name == '' then
        return false
    end
    for _,h in ipairs(clients_allowed_to_all_endpoints) do
        if h ~= '' and client_name == h then
            return true
        end
    end

    return false
end

function envoy_on_response(handle)
    local dynamic_metadata = handle:streamInfo():dynamicMetadata()
    local rbacMetadata = dynamic_metadata:get('envoy.filters.http.rbac') or {}
    local is_shadow_denied = (rbacMetadata['shadow_engine_result'] or '') == 'denied'
    local rule = rbacMetadata['shadow_effective_policy_id'] or ''

    if is_shadow_denied then
        local headers = handle:headers()
        local lua_metadata = dynamic_metadata:get('envoy.filters.http.lua') or {}
        local jwt_status = (dynamic_metadata:get('envoy.filters.http.header_to_metadata') or {})['jwt-status'] or 'missing'
        local jwt_metadata = dynamic_metadata:get('envoy.filters.http.jwt_authn') or {}
        if jwt_metadata['jwt_failure_reason'] then
            jwt_status = jwt_metadata['jwt_failure_reason']['message']
        end

        local upstream_request_time = headers:get('x-envoy-upstream-service-time')
        local status_code = headers:get(':status')
        local rbac_action = 'shadow_denied'
        if upstream_request_time == nil and status_code == '403' then
            rbac_action = 'denied'
        end
        log_request(handle, rule, lua_metadata, jwt_status, rbac_action)
    end
end

function log_request(handle, rule, lua_metadata, jwt_status, rbac_action)
    local client_name = lua_metadata['request.info.client_name'] or ''
    local trusted_client = lua_metadata['request.info.trusted_client'] or false
    local path = lua_metadata['request.info.path'] or ''
    local protocol = handle:connection():ssl() == nil and 'http' or 'https'
    local method = lua_metadata['request.info.method'] or ''
    local xff_header = lua_metadata['request.info.xff_header'] or ''
    local source_ip = string.match(xff_header, '[^,]+$') or ''
    local request_id = lua_metadata['request.info.request_id'] or ''
    local status_code = handle:headers():get(':status') or '0'
    local allowed_client = lua_metadata['request.info.allowed_client'] or false
    local authority = lua_metadata['request.info.authority'] or ''
    local lua_destination_authority = lua_metadata['request.info.lua_destination_authority'] or ''

    local message = {
        '\nINCOMING_PERMISSIONS {"method":"', method,
        '","rule":"', rule,
        '","path":"', path,
        '","clientIp":"', source_ip,
        '","clientName":"', escape(client_name),
        '","trustedClient":', tostring(trusted_client),
        ',"authority":"', escape(authority),
        '","luaDestinationAuthority":"', escape(lua_destination_authority),
        '","clientAllowedToAllEndpoints":', tostring(allowed_client),
        ',"protocol":"', protocol,
        '","requestId":"', escape(request_id),
        '","statusCode":', status_code,
        ',"rbacAction":"', rbac_action, '"' ,
        ',"jwtTokenStatus":"' , jwt_status, '"',
    }

    local headers_to_log = handle:metadata():get('rbac_headers_to_log') or {}
    for _, header in ipairs(headers_to_log) do
        local value = lua_metadata['request.info.headers.'..header]
        if value then
            table.insert(message, ',"')
            table.insert(message, header)
            table.insert(message, '":"')
            table.insert(message, tostring(escape(value)))
            table.insert(message, '"')
        end
    end
    table.insert(message, '}')

    handle:logInfo(table.concat(message, ''))
end

escapeList = {
    ['\\'] = '\\\\',
    ['\"'] = '\\\"',
    ['\b'] = '\\b',
    ['\f'] = '\\f',
    ['\n'] = '\\n',
    ['\r'] = '\\r',
    ['\t'] = '\\t',
}

function escape(val)
    return string.gsub(val, '[\\\"\b\f\n\r\t]', escapeList)
end
