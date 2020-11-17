function envoy_on_request(handle)
    local streamInfo = handle:streamInfo()

    if handle:headers():get("x-client-name-trusted") ~= nil then
        handle:headers():remove("x-client-name-trusted")
    end

    if handle:connection():ssl() and streamInfo:downstreamSslConnection() and streamInfo:downstreamSslConnection():peerCertificateValidated() then
        local uriSanPeerCertificate = handle:streamInfo():downstreamSslConnection():uriSanPeerCertificate()
        local san_uri_format = handle:metadata():get("san_uri_client_name_regex")
        local trusted_header = handle:metadata():get("x_client_name_trusted")

        if uriSanPeerCertificate ~= nil and next(uriSanPeerCertificate) ~= nil then
            for _, entry in pairs(uriSanPeerCertificate) do
                handle:headers():add(trusted_header, string.match(entry, san_uri_format))
            end
        end
    end
end

function envoy_on_response(handle)
end
