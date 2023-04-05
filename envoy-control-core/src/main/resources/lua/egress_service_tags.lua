function envoy_on_request(handle)
    local rejectRequestServiceTagDuplicate = handle:metadata():get("reject_request_service_tag_duplicate") or false

    if rejectRequestServiceTagDuplicate then
        rejectServiceTagDuplicatingAutoServiceTag(handle)
    end
end

function rejectServiceTagDuplicatingAutoServiceTag(handle)
    local autoServiceTagPreference = handle:metadata():get("auto_service_tag_preference")
    if autoServiceTagPreference == nil then
        return
    end
    local serviceTagMetadataKey = handle:metadata():get("service_tag_metadata_key")
    if serviceTagMetadataKey == nil then
        handle:logErr("'service_tag_metadata_key' is not present in metadata!")
        return
    end
    local requestServiceTag = (handle:streamInfo():dynamicMetadata():get("envoy.lb") or {})[serviceTagMetadataKey]
    if (requestServiceTag == nil) then
        return
    end

    for i=1,#autoServiceTagPreference do
        if requestServiceTag == autoServiceTagPreference[i] then
            local message = "Request service-tag '"..requestServiceTag.."' duplicates auto service-tag preference. "
                    .."Remove service-tag parameter from the request"
            handle:respond({[":status"] = 400}, message)
        end
    end
end
