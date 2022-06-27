# Access log filter configuration

Using Envoy's [metadata](https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/core/v3/base.proto.html#core-metadata)
section you can provide additional configuration to the Control Plane.
Configuration provided in `metadata.access_log_filter` will be used to set up an access log filter for `Envoy`.

## Filter logs by status code

Envoy allows filtering access logs by status code, request duration, response flag, traceable and not a health check 
filters. 
Note that when adding multiple filters they are applied using AND operator. 

* Variable `metadata.access_log_filter.status_code_filter` and `metadata.access_log_filter.duration_filter`  should 
* contain operator (case insensitive) and status code/duration.

    Expected format is: `{operator}`:`{status code}`.

    ####Allowed operators are:

  * `le` - lower equal
  * `eq` - equal
  * `ge` - greater equal
    
  ####An example configuration:

```yaml
    metadata:
      access_log_filter:
        status_code_filter: "GE:500"
 ```

* Variable `metadata.access_log_filter.not_health_check_filter`

    ####An example configuration:
```yaml
    metadata:
      access_log_filter:
        not_health_check_filter: true
 ```

* Variable `metadata.access_log_filter.header_filter` should contain header name and regex to match corresponding 
header value.

    Expected format {header name}:{regex}

    ####An example configuration

```yaml
    metadata:
      access_log_filter:
          header_filter: "X-Canary:^1$"
 ```

* Variable `metadata.access_log_filter.response_flag_filter` should contain flags that are limited to the ones listed
[here](https://www.envoyproxy.io/docs/envoy/latest/configuration/observability/access_log/usage#config-access-log-format-response-flags)

    Multiple flags should be delimited by comma.
    
    *Importand note: this filter hasn't been tested*

    ####An example configuration

```yaml
    metadata:
      access_log_filter:
          response_flag_filter: "UPE,DT"
 ```
