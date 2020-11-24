require("ingress_client")

local function handlerMock(headers, metadata, https, uri_san_peer_certificate, peerCertificateValidated)
    local downstreamSslConnection_mock = mock({
        uriSanPeerCertificate = function() return uri_san_peer_certificate end,
        peerCertificateValidated = function() return peerCertificateValidated end
    })
    return {
        headers = function() return {
            add = function(_, key, value) if headers[key] ~= nil then headers[key] = headers[key] ..","..value else headers[key] = value end end,
            get = function(_, key) return headers[key] end,
            remove = function(_, key) headers[key] = nil end
        }
        end,
        streamInfo = function() return {
            downstreamSslConnection = function() return downstreamSslConnection_mock end
        }
        end,
        connection = function() return {
            ssl = function() return https or nil end
        }end,
        metadata = function() return {
                get = function(_, key) return metadata[key] end
            }
        end
    }
end

describe("envoy_on_request:", function()
    local metadata = {
        ['trusted_client_identity_header'] = "x-client-name-trusted",
        ['san_uri_client_name_regex'] = "://([a-zA-Z0-9-_.]+)"
    }

    it ("should remove x-client-name-trusted header if provided", function()
        -- given
        local headers = { ['x-client-name-trusted'] = 'service-third' }

        local handle = handlerMock(headers, metadata, true, nil)

        -- when
        envoy_on_request(handle)

        -- then
        assert.are.equal(headers['x-client-name-trusted'], nil)

    end)

    it ("should add x-client-name-trusted header with values from certificate", function()
        -- given

        local uri_san_client_names_pairs = {
            ["service-first-special"] = {"service://service-first-special?env=dev"},
            ["service-first-some"] = {"service://service-first-some"},
            ["service-first-http"] = {"http://service-first-http?env=dev"},
            ["service-first-https"] = {"https://service-first-https?env=dev"},
            ["service-first-spiffe"] = {"spiffe://service-first-spiffe?env=dev"},
            ["service-first-spiffe"] = {"spiffe://service-first-spiffe/?env=dev"},
            ["service-first-regular,service-first-special"] = {"service://service-first-regular?env=dev", "service://service-first-special?env=dev"},
        }

        for header_value, uri_san_peer_certificate in pairs(uri_san_client_names_pairs) do
            local headers = {}

            local handle = handlerMock(headers, metadata, true, uri_san_peer_certificate, true)

            -- when
            envoy_on_request(handle)

            -- then
            assert.are.equal(headers['x-client-name-trusted'], header_value)
        end


    end)
end)

--[[
tools:
  show spy calls:
    require 'pl.pretty'.dump(handle.logInfo.calls, "/dev/stderr")
]] --
