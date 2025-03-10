
function parseServiceTagPreferenceToFallbackList(preferenceString)
    local fallbackList = {}
    local i = 1
    for tag in string.gmatch(preferenceString, "[^|]+") do
        fallbackList["%SERVICE_TAG_METADATA_KEY%"] = tag
        i = i + 1
    end
    return fallbackList
end

local defaultServiceTagPreferenceFallbackList = parseServiceTagPreferenceToFallbackList(
    os.getenv("%DEFAULT_SERVICE_TAG_PREFERENCE_ENV%") or "%DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK%")

function envoy_on_request(handle)
    local serviceTag = handle:headers():get("%SERVICE_TAG_HEADER%")
    -- TODO: test overriding

    if serviceTag and serviceTag ~= "" then
        local dynMetadata = request_handle:streamInfo():dynamicMetadata()
        dynMetadata:set("envoy.lb", "%SERVICE_TAG_METADATA_KEY%", serviceTag)
        return
    end

    local fallbackList = defaultServiceTagPreferenceFallbackList
    local requestPreference = handle:headers():getAtIndex("%SERVICE_TAG_PREFERENCE_HEADER%", 0)
    if requestPreference then
        fallbackList = parseServiceTagPreferenceToFallbackList(requestPreference)
    end

    if next(fallbackList) ~= nil then
        local dynMetadata = request_handle:streamInfo():dynamicMetadata()
        dynMetadata:set("envoy.lb", "fallback_list", fallbackList)
    end

end


function envoy_on_response(handle)
end
