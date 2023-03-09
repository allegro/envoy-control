function respond_error(handle, errorId, message, status)
    handle:respond(
            {
                [":status"] = status,
                ["content-type"] = "application/json"
            },
            [[ {"message": "]]..message..[["} ]]
    )
end
