#!/usr/bin/env lua

require 'Test.More'

-- we have to plan the number of expected assertions in the test, otherwise it will fail
plan(6)

local handler = require('ingress_handler')

local path = nil
local authority = nil
local xCanaryHeader = nil
local headersSet = {}
local dynamicMetadataSet = {}
local warnings = {}
local infos = {}
local xCanaryHeaderRequested = false
local statusCode = 200

local method = "GET"
local serviceName = "service-first"
local xForwardedFor = ""

function cleanup()
    path = nil
    authority = nil
    xCanaryHeader = nil
    headersSet = {}
    warnings = {}
    infos = {}
    xCanaryHeaderRequested = false
    method = "GET"
    xForwardedFor = nil
    statusCode = 200
end

local requestHandleMock = {}
function requestHandleMock:logInfo(str)
    table.insert(infos, str)
end

function requestHandleMock:logWarn(msg)
    table.insert(warnings, msg)
end

function requestHandleMock:streamInfo()
    local streamInfo = {}
    function streamInfo:dynamicMetadata()
        local dynamicMetadata = {}
        function dynamicMetadata:get(filter)
            if filter ==  "envoy.filters.http.rbac" then
                return {["shadow_engine_result"]="denied"}
            elseif filter == "envoy.filters.http.lua" then
                return dynamicMetadataSet
            end
            fail("Requested invalid filter: " .. filter)
        end

        function dynamicMetadata:set(filter, key, value)
            dynamicMetadataSet[key] = value
        end

        return dynamicMetadata
    end
    return streamInfo
end


function requestHandleMock:headers()
    local headersMock = {}
    function headersMock:get(arg)
        if arg == "x-service-name" then
            return serviceName
        elseif arg == ":path" then
            return path
        elseif arg == ":method" then
            return method
        elseif arg == "x-forwarded-for" then
            return xForwardedFor
        elseif arg == ":authority" then
            return authority
        elseif arg == "x-canary" then
            xCanaryHeaderRequested = true
            return xCanaryHeader
        elseif arg == ":status" then
            return statusCode
        end
        fail("Requested invalid header: " .. arg)
    end
    function headersMock:replace(key, value)
        table.insert(headersSet, {
            key = key,
            value = value
        })
    end
    return headersMock
end

local sslConnection = true

function requestHandleMock:connection()
    local connection = {}
    function connection:ssl()
        if sslConnection == false then
            return nil
        end
        return true
    end
    return connection
end

function requestHandleMock:logWarn(msg)
   table.insert(warnings, msg)
end

function assertNoWarnings()
    is_deeply(warnings, {})
end

function assertWarning(warning)
    is_deeply(warnings, {warning})
end

function assertNoXCanaryHeaderRequested()
    is(xCanaryHeaderRequested, false)
end

do -- test: On envoy_on_request should set dynamicMetadata fields
    -- given
    path = "/lorem/ipsum?other=value"
    xForwardedFor = "Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43"

    -- when
    handler:envoy_on_request(requestHandleMock)

    -- then
    is_deeply(
      dynamicMetadataSet,
      {
          ["request.info.xff_header"]="Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43",
          ["request.info.service_name"]="service-first",
          ["request.info.method"]="GET",
          ["request.info.path"]="/lorem/ipsum?other=value"
      }
    )
    assertNoWarnings()
    assertNoXCanaryHeaderRequested()

    -- cleanup
    cleanup()
end

do -- test: On envoy_on_response should show Access denied log
    -- given
    path = "/lorem/ipsum?other=value"
    xForwardedFor = "Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43"

    -- when
    handler:envoy_on_request(requestHandleMock)
    handler:envoy_on_response(requestHandleMock)

    -- then
    is_deeply(
        infos,
        {
            "running envoy_on_request",
            "running envoy_on_response",
            "\nAccess denied for request: method = GET, path = /lorem/ipsum?other=value, clientIp = Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43, clientName = service-first, protocol = https, statusCode = 200"
        }
    )
    assertNoWarnings()
    assertNoXCanaryHeaderRequested()

    -- cleanup
    cleanup()
end