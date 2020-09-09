require("ingress_rbac_logging")

local _ = match._
local contains = function(substring) return match.matches(substring, nil, true) end
local function formatLog(method, path, source_ip, service_name, protocol, statusCode)
    return "\nINCOMING_PERMISSIONS { \"method\": \""..method.."\", \"path\": \""..path.."\", \"clientIp\": \""..source_ip.."\", \"clientName\": \""..service_name.."\", \"protocol\": \""..protocol.."\", \"statusCode\": "..statusCode.." }"
end

local function handlerMock(headers, metadata, https)
    local metadataMock = mock({
        set = function() end,
        get = function(_, key) return metadata[key] end
    })
    local logInfoMock = spy(function() end)
    return {
        headers = function() return {
            get = function(_, key) return headers[key] end
        } end,
        streamInfo = function() return {
            dynamicMetadata = function() return metadataMock end
        } end,
        connection = function() return {
            ssl = function() return https or nil end
        } end,
        logInfo = logInfoMock
    }
end


describe("envoy_on_request:", function()
    it("should set dynamic metadata", function()
        -- given
        local headers = {
            [':path'] = '/path',
            [':method'] = 'GET',
            ['x-service-name'] = 'lorem-service',
            ['x-forwarded-for'] = "127.0.4.3"
        }

        local handle = handlerMock(headers)
        local metadata = handle:streamInfo():dynamicMetadata()

        -- when
        envoy_on_request(handle)

        -- then
        assert.spy(metadata.set).was_called_with(_, "envoy.filters.http.lua", "request.info.path", "/path")
        assert.spy(metadata.set).was_called_with(_, "envoy.filters.http.lua", "request.info.method", "GET")
        assert.spy(metadata.set).was_called_with(_, "envoy.filters.http.lua", "request.info.service_name", "lorem-service")
        assert.spy(metadata.set).was_called_with(_, "envoy.filters.http.lua", "request.info.xff_header", "127.0.4.3")
    end)
end)

describe("envoy_on_response:", function()
    local headers
    local metadata
    local ssl

    before_each(function ()
        headers = {
            [':status'] = '403'
        }
        metadata = {
            ['envoy.filters.http.rbac'] = {
                ['shadow_engine_result'] = 'denied'
            },
            ['envoy.filters.http.lua'] = {
                ['request.info.service_name'] = 'service-first',
                ['request.info.path'] = '/path?query=val',
                ['request.info.method'] = 'POST',
                ['request.info.xff_header'] = '127.1.1.3',

            }
        }
        ssl = true
    end)

    describe("should log unauthorized requests:", function ()

        it("https request", function ()
            -- given
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("POST", "/path?query=val", "127.1.1.3", "service-first", "https", "403"))
            assert.spy(handle.logInfo).was_called(1)
        end)

        it("http request", function ()
            -- given
            ssl = false
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("POST", "/path?query=val", "127.1.1.3", "service-first", "http", "403"))
            assert.spy(handle.logInfo).was_called(1)
        end)

        it("allowed & logged request", function ()
            -- given
            headers[':status'] = '200'
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("POST", "/path?query=val", "127.1.1.3", "service-first", "https", "200"))
            assert.spy(handle.logInfo).was_called(1)
        end)

        it("request with no lua filter metadata fields saved", function ()
            -- given
            metadata['envoy.filters.http.lua'] = {}
            headers = {}
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("", "", "", "", "https", "0"))
            assert.spy(handle.logInfo).was_called(1)
        end)

        it("request with no lua filter metadata saved", function ()
            -- given
            metadata['envoy.filters.http.lua'] = nil
            headers = {}
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("", "", "", "", "https", "0"))
            assert.spy(handle.logInfo).was_called(1)
        end)

        it("request with empty path", function ()
            -- given
            metadata['envoy.filters.http.lua']['request.info.path'] = ''
            local handle = handlerMock(headers, metadata, ssl)

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_called_with(_, formatLog("POST", "", "127.1.1.3", "service-first", "https", "403"))
            assert.spy(handle.logInfo).was_called(1)
        end)
    end)

    describe("should not log requests:", function()

        it("request with no rbac metadata", function()
            -- given
            metadata = {}
            local handle = handlerMock(headers, metadata, ssl)
            local metadataMock = handle:streamInfo():dynamicMetadata()

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_not_called()
            assert.spy(metadataMock.get).was_not_called_with(_, 'envoy.filters.http.lua')
        end)

        it("authorized request", function()
            -- given
            metadata['envoy.filters.http.rbac']['shadow_engine_result'] = 'allowed'
            local handle = handlerMock(headers, metadata, ssl)
            local metadataMock = handle:streamInfo():dynamicMetadata()

            -- when
            envoy_on_response(handle)

            -- then
            assert.spy(handle.logInfo).was_not_called()
            assert.spy(metadataMock.get).was_not_called_with(_, 'envoy.filters.http.lua')
        end)
    end)

    describe("should handle x-forwarded-for formats:", function ()
        local xff_to_expected_client_ip= {
            {"", ""},
            {"127.9.3.2", "127.9.3.2"},
            {"3.23.2.44 , 2.34.3.2,127.1.3.5", "127.1.3.5"},
            {"2001:db8:85a3:8d3:1319:8a2e:370:7348,1001:db8:85a3:8d3:1319:8a2e:370:2222", "1001:db8:85a3:8d3:1319:8a2e:370:2222"},
            {"2001:db8:85a3:8d3:1319:8a2e:370:7348,127.1.3.4", "127.1.3.4"}
        }

        for i,v in ipairs(xff_to_expected_client_ip) do
            local xff = v[1]
            local expected_client_ip = v[2]

            it("'"..xff.."' -> '"..expected_client_ip.."'", function ()
                -- given
                metadata['envoy.filters.http.lua']['request.info.xff_header'] = xff
                local handle = handlerMock(headers, metadata, ssl)

                -- when
                envoy_on_response(handle)

                -- then
                assert.spy(handle.logInfo).was_called_with(_, contains("\"clientIp\": \""..expected_client_ip.."\""))
            end)
        end
    end)
end)

--[[
tools:
  show spy calls:
    require 'pl.pretty'.dump(handle.logInfo.calls, "/dev/stderr")
]]--

