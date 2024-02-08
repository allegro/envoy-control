require('ingress_current_zone_header')

local function handlerMock(headers, metadata)
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
        metadata = function() return {
                get = function(_, key) return metadata[key] end
            }
        end
    }
end

describe("envoy_on_response:", function()
    it("should set current zone header", function()
        -- given
        local filter_metadata = {
            ['traffic_splitting_zone_header_name'] =  'x-current-zone',
            ['current_zone'] = 'local-dc'
        }
        local headers = {}
        local handle = handlerMock(headers, filter_metadata)

        -- when
        envoy_on_response(handle)

        -- then
        assert.are.equal('local-dc', headers['x-current-zone'])
    end)

    it("should add zone to existing header", function()
        -- given
        local filter_metadata = {
            ['traffic_splitting_zone_header_name'] =  'x-current-zone',
            ['current_zone'] = 'local-dc'
        }
        local headers = {['x-current-zone'] = 'local-dc-0'}
        local handle = handlerMock(headers, filter_metadata)

        -- when
        envoy_on_response(handle)

        -- then
        assert.are.equal('local-dc-0,local-dc', headers['x-current-zone'])
    end)

    it("should not add header if header name isn't specified", function()
        -- given
        local filter_metadata = {
            ['current_zone'] = 'local-dc'
        }
        local headers = {}
        local handle = handlerMock(headers, filter_metadata)

        -- when
        envoy_on_response(handle)

        -- then
        assert.are.equal(nil, headers['x-current-zone'])
    end)


    it("should add header if zone name is empty", function()
        -- given
        local filter_metadata = {
            ['traffic_splitting_zone_header_name'] =  'x-current-zone',
            ['current_zone'] = ''
        }
        local headers = {}
        local handle = handlerMock(headers, filter_metadata)

        -- when
        envoy_on_response(handle)

        -- then
        assert.are.equal('', headers['x-current-zone'])
    end)
end)


--[[
tools:
  show spy calls:
    require 'pl.pretty'.dump(handle.logInfo.calls, "/dev/stderr")
]] --
