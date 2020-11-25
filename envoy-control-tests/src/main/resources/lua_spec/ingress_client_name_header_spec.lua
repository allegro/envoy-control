require("ingress_client_name_header")

local function handlerMock(headers, metadata, https, uri_san_peer_certificate)
    local downstreamSslConnection_mock = mock({
        uriSanPeerCertificate = function() return uri_san_peer_certificate end
    })
    return {
        headers = function() return {
            add = function(_, key, value) if headers[key] ~= nil then headers[key] = headers[key] ..","..value else headers[key] = value end end,
            get = function(_, key)
                assert.is.not_nil(key, "headers:get() called with nil argument")
                return headers[key]
            end,
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
        ['san_uri_lua_pattern'] = "^spiffe://(.+)%?env=dev$"
    }

    it("should remove x-client-name-trusted header if provided", function()
        -- given
        local headers = { ['x-client-name-trusted'] = 'service-third' }

        local handle = handlerMock(headers, metadata, true, nil)

        -- when
        envoy_on_request(handle)

        -- then
        assert.are.equal(headers['x-client-name-trusted'], nil)

    end)

    describe("should add x-client-name-trusted header with correct values from certificate:", function()
        -- given
        local uri_san_client_names_pairs = {
            ["service-first-spiffe"] = {"spiffe://service-first-spiffe?env=dev"},
            ["1"] = {"spiffe://1?env=dev"},
            ["correct,correct2"] = {"spiffe://correct?env=dev","service://fake?env=dev", "spiffe://correct2?env=dev"},
        }

        for header_value, uri_san_peer_certificate in pairs(uri_san_client_names_pairs) do
            it(header_value, function()
                local headers = {}
                local handle = handlerMock(headers, metadata, true, uri_san_peer_certificate)

                -- when
                envoy_on_request(handle)

                -- then
                assert.are.equal(headers['x-client-name-trusted'], header_value)
            end)
        end


    end)

    describe("should not add x-client-name-trusted header when certificate values are incorrect:", function()
        -- given
        local incorrect_uri_sans = {
            "service://service-first-special?env=dev",
            "spiffe://service-first-spiffe",
            "spiffe://service-first-spiffe=dev",
            "hackerspiffe://service-first-spiffe?env=dev",
            "spiffe://service-first-spiffe?env=devandhack"
        }

        for _, incorrect_san in ipairs(incorrect_uri_sans) do
            it(incorrect_san, function()
                local headers = {}
                local handle = handlerMock(headers, metadata, true, { incorrect_san })

                -- when
                envoy_on_request(handle)

                -- then
                assert.are.equal(headers['x-client-name-trusted'], nil)
            end)
        end
    end)

    it("should survive lack of metadata", function ()
        -- given
        local headers = {}
        local no_metadata = {}
        local handle = handlerMock(headers, no_metadata, true, nil)

        -- when
        envoy_on_request(handle)

        -- then
        assert.are.equal(headers['x-client-name-trusted'], nil)
    end)
end)

--[[
tools:
  show spy calls:
    require 'pl.pretty'.dump(handle.logInfo.calls, "/dev/stderr")
]] --
