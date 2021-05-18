function envoy_on_request(handle)
    local authorization = handle:headers():get("authorization")
    if authorization == nil
    then
        handle:metadata():add("jwt-missing", true)
    else
        handle:metadata():add("jwt-missing", false)
    end

end

function envoy_on_response(handle)
end
