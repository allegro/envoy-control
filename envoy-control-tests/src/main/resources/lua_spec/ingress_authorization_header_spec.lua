require("ingress_authorization_header")

local function handlerMock(headers, metadata)
    return {
        headers = function()
            return {
                get = function(_, key)
                    assert.is.not_nil(key, "headers:get() called with nil argument")
                    return headers[key]
                end
            }
        end,
        metadata = function()
            return {
                get = function(_, key)
                    return metadata[key]
                end,
                add = function(_, key, value)
                    metadata[key] = value
                end,

            }
        end,

    }
end

describe("envoy_on_request:", function()
    it("should set jwt-missing to false if jwt is present", function()
        -- given
        local metadata = { }
        local headers = { ['authorization'] = 'Bearer: a.b.c' }
        local handle = handlerMock(headers, metadata)

        -- when
        envoy_on_request(handle)

        -- then
        assert.are.equal(metadata['jwt-missing'], false)

    end)

    it("should set jwt-missing to true if jwt is missing", function()
        -- given
        local metadata = { }
        local headers = { }
        local handle = handlerMock(headers, metadata)

        -- when
        envoy_on_request(handle)

        -- then
        assert.are.equal(metadata['jwt-missing'], true)

    end)
end)
