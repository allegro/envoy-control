function envoy_on_request(handle)
end

function envoy_on_response(handle)
 local traffic_splitting_zone_header_name = handle:metadata():get("traffic_splitting_zone_header_name") or ""
    local current_zone = handle:metadata():get("current_zone") or ""
    if traffic_splitting_zone_header_name == "" then
        return
    end
    handle:headers():add(traffic_splitting_zone_header_name, current_zone)
end
