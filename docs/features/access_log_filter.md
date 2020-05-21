# Access log filter configuration

Using Envoy's [metadata](https://www.envoyproxy.io/docs/envoy/latest/api-v2/api/v2/core/base.proto#core-metadata)
section you can provide additional configuration to the Control Plane.
Configuration provided in `metadata.access_log_filter` will be used to set up an access log filter for `Envoy`.

## Filter logs by status code

Envoy allows filtering access logs by status code.
Variable `metadata.access_log_filter.status_code_filter` should contain operator and status code.

Expected format is: `{operator}`:`{status code}`.

Allowed operators are:

* `le` - lower equal
* `eq` - equal
* `ge` - greater equal

An example configuration:

```yaml
metadata:
  access_log_filter:
    status_code_filter: "GE:500"
```
