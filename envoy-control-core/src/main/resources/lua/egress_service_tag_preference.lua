
function parseServiceTagPreferenceToFallbackList(preferenceString)
    local fallbackList = {}
    local i = 1
    for tag in string.gmatch(preferenceString, "[^|]+") do
        fallbackList[i] = {["%SERVICE_TAG_METADATA_KEY%"] =  tag}
        i = i + 1
    end
    return fallbackList
end

local defaultServiceTagPreference = os.getenv("%DEFAULT_SERVICE_TAG_PREFERENCE_ENV%") or "%DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK%"
local defaultServiceTagPreferenceFallbackList = parseServiceTagPreferenceToFallbackList(defaultServiceTagPreference)

function envoy_on_request(handle)
    local serviceTag = handle:headers():get("%SERVICE_TAG_HEADER%")

    local requestPreference = handle:headers():getAtIndex("%SERVICE_TAG_PREFERENCE_HEADER%", 0)
    if not requestPreference then
        handle:headers():replace("%SERVICE_TAG_PREFERENCE_HEADER%", defaultServiceTagPreference)
    end

    if serviceTag and serviceTag ~= "" then
        local dynMetadata = handle:streamInfo():dynamicMetadata()
        dynMetadata:set("envoy.lb", "%SERVICE_TAG_METADATA_KEY%", serviceTag)
    else
        local fallbackList = defaultServiceTagPreferenceFallbackList
        if requestPreference then
            fallbackList = parseServiceTagPreferenceToFallbackList(requestPreference)
        end

        if next(fallbackList) ~= nil then
            local dynMetadata = handle:streamInfo():dynamicMetadata()
            dynMetadata:set("envoy.lb", "fallback_list", fallbackList)
        end
    end
end


function envoy_on_response(handle)
end
