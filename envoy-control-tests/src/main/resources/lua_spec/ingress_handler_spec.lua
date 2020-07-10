require("ingress_handler")

describe('hello world', function()
    it("2 == 2", function()
        assert.same(2, 2)
    end)
end)
--
--describe("failed suite", function()
--    it("2 == 3", function()
--        assert.same(2, 3)
--    end)
--end)

local _ = match._

describe("envoy_on_request:", function()

    local headers = {
        [':path'] = '/path',
        [':method'] = 'GET',
        ['x-service-name'] = 'lorem-service',
        ['x-forwarded-for'] = "127.0.4.3"
    }

    local metadataSet = spy(function() end)

    local handle = {
        headers = function() return {
            get = function(self, key) return headers[key] end
        } end,
        streamInfo = function() return {
            dynamicMetadata = function() return {
                set = metadataSet
            } end
        } end
    }

    it("should set dynamic metadata", function()
        -- when
        envoy_on_request(handle)

        -- io.stderr:write(string.format("HEJJJJJJJJ\n"))
        -- handle:streamInfo():dynamicMetadata():set("envoy.filters.http.lua", "request.info.path", "/path")

        -- then
        assert.spy(metadataSet).was_called_with(_, "envoy.filters.http.lua", "request.info.path", "/path")
        assert.spy(metadataSet).was_called_with(_, "envoy.filters.http.lua", "request.info.method", "GET")
        assert.spy(metadataSet).was_called_with(_, "envoy.filters.http.lua", "request.info.service_name", "lorem-service")
        assert.spy(metadataSet).was_called_with(_, "envoy.filters.http.lua", "request.info.xff_header", "127.0.4.3")
    end)
end)
