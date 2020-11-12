function envoy_on_request(handle)
    if handle:headers():get("x-client-name-trusted") ~= nil then
        handle:headers():remove("x-client-name-trusted")
    end
    --local trusted_header = handle:metadata():get("x_client_name_trusted")
    --above commented line causes STDERR: Segmentation fault
    local trusted_header = "x-client-name-trusted"

    if handle:connection():ssl() ~= nil then
        local uriSanPeerCertificate = handle:streamInfo():downstreamSslConnection():uriSanPeerCertificate()
        if uriSanPeerCertificate ~= nil and next(uriSanPeerCertificate) ~= nil then
            local pattern = "://([a-zA-Z0-9-_.]+)"
            local values = {}
            for _, entry in pairs(uriSanPeerCertificate) do
                table.insert(values, string.match(entry, pattern))
            end
            if next(values) then
                handle:headers():add(trusted_header, table.concat(values, ","))
            end
        end
    end
end

function envoy_on_response(handle)
end
