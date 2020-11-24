function envoy_on_request(handle)
    local streamInfo = handle:streamInfo()
    local trusted_header = handle:metadata():get("trusted_client_identity_header")

    if handle:headers():get(trusted_header) ~= nil then
        handle:headers():remove(trusted_header)
    end

    if handle:connection():ssl() and streamInfo:downstreamSslConnection() then
        local uriSanPeerCertificate = handle:streamInfo():downstreamSslConnection():uriSanPeerCertificate()
        if uriSanPeerCertificate ~= nil and next(uriSanPeerCertificate) ~= nil then
            local san_uri_format = handle:metadata():get("san_uri_lua_pattern")

            for _, entry in pairs(uriSanPeerCertificate) do
                local clientName = string.match(entry, san_uri_format)
                if clientName ~= nil and clientName ~= '' then
                    handle:headers():add(trusted_header, clientName)
                end
            end
        end
    end
end
