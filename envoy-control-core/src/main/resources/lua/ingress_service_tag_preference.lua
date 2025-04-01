local defaultServiceTagPreference = os.getenv("%DEFAULT_SERVICE_TAG_PREFERENCE_ENV%") or "%DEFAULT_SERVICE_TAG_PREFERENCE_FALLBACK%"
local defaultServiceTagPreferenceLength = #defaultServiceTagPreference

local isSuffixOfDefaultPreference = function(requestPreference)
    local requestPreferenceLength = #requestPreference
    if requestPreferenceLength >= defaultServiceTagPreferenceLength then
        return false
    end
    return string.sub(defaultServiceTagPreference, -requestPreferenceLength - 1) == "|" .. requestPreference
end

function envoy_on_request(handle)
    local requestPreference = handle:headers():getAtIndex("%SERVICE_TAG_PREFERENCE_HEADER%", 0)
    if not requestPreference then
        handle:headers():add("%SERVICE_TAG_PREFERENCE_HEADER%", defaultServiceTagPreference)
        return
    end
    if isSuffixOfDefaultPreference(requestPreference) then
        handle:headers():replace("%SERVICE_TAG_PREFERENCE_HEADER%", defaultServiceTagPreference)
    end
end
