
local defaultServiceTagPreference = os.getenv("%DEFAULT_SERVICE_TAG_PREFERENCE_ENV%") or "%DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK%"

local fallbackToAnyIfDefaultPreferenceEqualTo = "%FALLBACK_TO_ANY_IF_DEFAULT_PREFERENCE_EQUAL_TO%"
local fallbackToAny = false
if fallbackToAnyIfDefaultPreferenceEqualTo ~= "" then
    if fallbackToAnyIfDefaultPreferenceEqualTo == defaultServiceTagPreference then
        fallbackToAny = true
    end
end

function parseServiceTagPreferenceToFallbackList(preferenceString)
    local fallbackList = {}
    local i = 1
    for tag in string.gmatch(preferenceString, "[^|]+") do
        fallbackList[i] = {["%SERVICE_TAG_METADATA_KEY%"] =  tag}
        i = i + 1
    end
    if fallbackToAny then
        fallbackList[i] = {}
    end
    return fallbackList
end

local defaultServiceTagPreferenceFallbackList = parseServiceTagPreferenceToFallbackList(defaultServiceTagPreference)

function envoy_on_request(handle)
    local requestPreference = handle:headers():getAtIndex("%SERVICE_TAG_PREFERENCE_HEADER%", 0)
    if not requestPreference then
        handle:headers():add("%SERVICE_TAG_PREFERENCE_HEADER%", defaultServiceTagPreference)
    end

    local serviceTag = handle:headers():get("%SERVICE_TAG_HEADER%")
    if not serviceTag or serviceTag == "" then
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
