# Local reply modification configuration

Envoy control allows defining custom format for responses returned by Envoy. Thanks to [Envoy functionality](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/local_reply) 
it is possible to configure the custom format, status code for specific responses.

## Define default response format for responses returned by Envoy

It is possible to define a custom format for all responses returned by Envoy. Envoy can return responsse either
in a text format or json format. It is possible to define only one of: `textFormat` and `jsonFormat`. 

### Configure text format response

Property `envoy-control.envoy.snapshot.dynamicListeners.localReplyMapper.responseFormat.textFormat` allows configuring text response format.
Text format supports [command operators](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-command-operators) that allows
operating on request/response data.

An example configuration: 

```yaml
envoy-control.envoy.snapshot.dynamicListeners.localReplyMapper.responseFormat.textFormat: "my custom response with flags: %RESPONSE_FLAGS%"
```

Response:

```text
"my custom response with flags: UF"
```

### Configure json format response

Property `envoy-control.envoy.snapshot.dynamicListeners.localReplyMapper.responseFormat.jsonFormat` allows configuring json response format.
It accepts a map of `key:value`, where key will be a json key and value might contains constant string or [command operators](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-command-operators).

An example configuration:

```yaml
envoy-control:
  envoy:
    snapshot:
      dynamicListeners:
        localReplyMapper:
          responseFormat:
            jsonFormat:
              myKey: "My custom body"
              responseFlags: "%RESPONSE_FLAGS%"
              host: "%REQ(:authority)%"
  
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

### Header matcher

Allows filtering responses based on header presence or value. Only one of: `exactMatch`, `regexMatch` can be used. If none is used
then presence matcher will match responses with specified header name.

Example:

```yaml
  headerMatcher:
    name: "host"
    exactMatch: "service1"
```

### Response flags matcher

Allows filtering responses based on response flags (refer to [Envoy docs](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-format-response-flags)).

Example:

```yaml
  responseFlagMatcher:
      - "UF"
      - "NR"
```

### Status code override

When response will be matched and property `statusCodeToReturn` for this matcher is defined then Envoy will change response status code 
to value of the property `statusCodeToReturn`.

### Custom body

When response will be matched and property `bodyToReturn` for this matcher is defined then Envoy will set body to value of the property `bodyToReturn`.
If you defined custom format then the value can be accessed by using placeholder `%LOCAL_REPLY_BODY%` (refer to [Envoy docs](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-format-filter-state)).

### Different response format for different matchers

It is possible to configure different response formats for different matchers. If matcher configuration has `responseFormat` configuration then 
it will be used instead of response format defined at `localReplyMapper` level.

