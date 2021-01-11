# Local reply modification configuration

Envoy Control allows defining custom format for responses returned by Envoy. Thanks to [Envoy functionality](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/local_reply) 
it is possible to configure the custom format, status code for specific responses.

## Define default response format for responses returned by Envoy

It is possible to define a custom format for all responses returned by Envoy. Envoy can return response either
in a text format or JSON format. It is possible to define only one of: `textFormat` and `jsonFormat`.
If the format isn't specified, then default from Envoy is returned.

### Configure text format response

Property `envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.response-format.text-format` allows configuring text response format.
Text format supports [command operators](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-command-operators) that allows
operating on request/response data.

An example configuration: 

```yaml
envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.response-format.text-format: "my custom response with flags: %RESPONSE_FLAGS%"
```

Response:

```text
"my custom response with flags: UF"
```

### Configure JSON format response

Property `envoy-control.envoy.snapshot.dynamic-listeners.local-reply-mapper.response-format.json-format` allows configuring JSON response format.
It accepts a JSON formatted string with constant string and  [command operators](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-command-operators).

An example configuration:

```yaml
envoy-control:
  envoy:
    snapshot:
      dynamic-listeners:
        local-reply-mapper:
          response-format:
            json-format: """{
              "myKey":"My custom body",
              "responseFlags":"%RESPONSE_FLAGS%",
              "host":"%REQ(:authority)%"
            }"""
```

Response:

```json
{
    "myKey" : "My custom body",
    "responseFlags" : "UF",
    "host" : "example-service"
}
```

## Match specific response

It is possible to define custom response or override status code only for specific responses. In that case you can use matchers
which allows defining custom response status and response body for matched responses. Currently, 3 types of matchers are supported:
- status code matcher
- response flag matcher
- header matcher

You can choose only one of: `statusCodeMatcher`, `headerMatcher`, `responseFlagMatcher`. Also, configuration supports response body format override
for matched responses.

### Status code matcher

Allows filtering only specific status codes: An expected format is: `{operator}`:`{status code}`.

Allowed operators are:

* `le` - lower equal
* `eq` - equal
* `ge` - greater equal

Example:

```yaml
statusCodeMatcher: "EQ:400"
```

By default, it is an empty string which means that matcher is disabled.

### Header matcher

Allows filtering responses based on header presence or value. Only one of: `exactMatch`, `regexMatch` can be used. If none is used
then presence matcher will match responses with specified header name.

Example:

```yaml
  headerMatcher:
    name: "host"
    exactMatch: "service1"
```

By default, all fields are equals to empty string which means that matcher is disabled.

### Response flags matcher

Allows filtering responses based on response flags (refer to [Envoy docs](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-format-response-flags)).

Example:

```yaml
  responseFlagMatcher:
      - "UF"
      - "NR"
```

By default, it is set an empty list which means there is no filtering by response flags.

### Status code override

When response is matched and property `statusCodeToReturn` for this matcher is defined then Envoy will change response status code 
to value of the property `statusCodeToReturn`. By default, it is set to 0 which means that status code won't be overridden.

### Custom body

When response is matched and property `bodyToReturn` for this matcher is defined then Envoy will set body to value of the property `bodyToReturn`.
If you defined custom format then the value can be accessed by using placeholder `%LOCAL_REPLY_BODY%` (refer to [Envoy docs](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-format-filter-state)).

By default, it is an empty string which means that body won't be overridden.

### Different response format for different matchers

It is possible to configure different response formats for different matchers. If matcher configuration has `responseFormat` configuration then 
it will be used instead of response format defined at `localReplyMapper` level. When there is no configuration, default Envoy's format will be returned.

