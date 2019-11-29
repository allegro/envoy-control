-- This is a heavily stripped down and modified version of the library: https://github.com/golgote/neturl
-- It has been modified to support extracting specific query param from an url only.
-- Also a support for multiple values for given query param name is introduced, which is not present in the original
-- library.

-- Bertrand Mansion, 2011-2013; License MIT
-- @module neturl
-- @alias	M

local M = {}
M.version = "0.9.0"
M.sep = '&'

local function decode(str, path)
	local str = str
	if not path then
		str = str:gsub('+', ' ')
	end
	return (str:gsub("%%(%x%x)", function(c)
			return string.char(tonumber(c, 16))
	end))
end

--- Parses the querystring to a table of values for given query param name
-- @param str The querystring to parse
-- @param paramName name of param to extract from querystring
-- @return a table representing the query param values
function M.parseQueryParam(str, paramName)
	local values = {}
	for key,val in str:gmatch(string.format('([^%q=]+)(=*[^%q=]*)', M.sep, M.sep)) do
		local key = decode(key)
		key = key:gsub('%[([^%]]*)%]', "=")
		key = key:gsub('=+.*$', "")
		key = key:gsub('%s', "_") -- remove spaces in parameter name
		if key == paramName then
			val = val:gsub('^=+', "")
			values[#values+1] = decode(val)
		end
	end
	return values
end


--- Extract given query param values from url
-- @param url Url string
-- @param paramName name of param to extract from url
-- @return a table values for given query param
function M.getQueryParamValues(url, paramName)
	local values = {}

	local url = tostring(url or '')
	url = url:gsub('#(.*)$', '')  -- strip fragment
	url = url:gsub('^([%w][%w%+%-%.]*)%:', '') -- strip scheme
	url:gsub('%?(.*)', function(v)
		values = M.parseQueryParam(v, paramName)
		return ''
	end)
	return values
end

return M