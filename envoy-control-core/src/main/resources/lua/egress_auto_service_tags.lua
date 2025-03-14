function envoy_on_request(handle)
    rejectServiceTagDuplicatingAutoServiceTag(handle)
end

function rejectServiceTagDuplicatingAutoServiceTag(handle)
    local autoServiceTagPreference = handle:metadata():get("auto_service_tag_preference")
    if autoServiceTagPreference == nil then
        return
    end
    local requestServiceTag = (handle:streamInfo():dynamicMetadata():get("envoy.lb") or {})["%SERVICE_TAG_METADATA_KEY%"]
    if (requestServiceTag == nil) then
        return
    end

    for i = 1, #autoServiceTagPreference do
        if requestServiceTag == autoServiceTagPreference[i] then
            local message = "Request service-tag '" .. requestServiceTag .. "' duplicates auto service-tag preference. "
                .. "Remove service-tag parameter from the request"
            handle:respond({ [":status"] = 400 }, message)
        end
    end
end

function envoy_on_response(handle)
end
