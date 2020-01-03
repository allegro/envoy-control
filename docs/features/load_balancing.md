# Weighted Load Balancing

You can configure weighted load balancing to service instances.
For Consul source, weight for a service instance is gathered from its'
tag (you can specify tag format in
[configuration](../configuration.md#consul)).

The traffic to a given service will be balanced proportionally to its'
instances weights.
For example, if instance A has weight 50 and instance B has weight
100, then instance B will receive approximately 2x more traffic than A.

You can configure default weight for instances, which don't provide
an explicit weight.

This feature is disabled by default. To enable and configure it check
appropriate [configuration](../configuration.md#load-balancing) options.

# Canary instances

Envoy-Control supports feature called "Canary instances".
If you enable it, every request with *canary header* will be routed
only to canary instances of given service.

For Consul source canary status of an instance is determined by
particular instance tag (you can specify tag format in
[configuration](../configuration.md#consul)).

If no canary instance is present for a given service, the request
will be routed to any instance as a fallback.

Canary instances are also a part of default instances set.
That means a request without the **canary header** may be routed
to a canary instance.

## Configuring Canary support with dynamic listeners

When using listeners configured via EC (`envoy-control.envoy.snapshot.dynamicListeners.enabled` set to `true`)
a default working config will be provided.

## Configuring Canary support static listeners

For this feature to work correctly, appropriate static Envoy config is
required.

## Example

Given that you decided the *canary header* is `x-canary: 1`, you should
add `envoy.filters.http.header_to_metadata` http filter to your
egress http connection manager in Envoy static config:

```yaml
  listeners:
  - name: egress_listener
    filter_chains:
      filters:
      - name: envoy.http_connection_manager
        config:
          rds:
            route_config_name: default_routes
            config_source:
              ads: {}
          http_filters:
          - name: envoy.filters.http.header_to_metadata
            config:
              request_rules:
                - header: x-canary
                  on_header_present:
                    metadata_namespace: envoy.lb
                    key: canary
                    type: STRING
                  remove: false
          - name: envoy.router
```

The `envoy.filters.http.header_to_metadata` should be added before
`router` filter.

You should also:

* set `metadata-key` configuration option to the same value as
  the `key` in config above, in this case it will be `canary`
* set `header-value` configuration option to your
  chosen header value, in this case it should be `1`

This feature is disabled by default. To enable and configure it check
appropriate [configuration](../configuration.md#load-balancing) options.
