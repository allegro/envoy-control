# Service tags routing

Envoy Control supports routing to service instances based on "service-tags".
When client performs request with service tags, only instances with these
tags will be considered in load balancing.

How service tags are issued to instances depends on the the service discovery
source used. For Consul, tags are supported out-of-the-box.

For this feature to work correctly, you have to extract a tag from request
data in some way.

## Configuration with dynamic listeners

When using listeners configured via EC (`envoy-control.envoy.snapshot.dynamicListeners.enabled` set to `true`)
a default working config will be provided (see: `EnvoyListenersFactory.kt` for details).

## Configuration with static listeners

For this feature to work correctly, appropriate static Envoy config is required.

You can find the reference implementation in
[config_ads.yaml](https://github.com/allegro/envoy-control/blob/master/envoy-control-tests/src/main/resources/envoy/config_ads.yaml)
file.
In that implementation, service tags are gathered from `x-service-tag: <tag>`
request header:

```yaml
- header: x-service-tag
  on_header_present:
    metadata_namespace: envoy.lb
    key: tag
    type: STRING
  remove: false
```

There is no fallback - if no instance with requested tag is found,
"no healthy upstream" error will be returned.

Request without service tags will be routed to any instance of the service.

## Example

Assume the reference static config presented above.
Given we have a following instances of service `lorem`:

* address: `192.168.0.2:4000`, tags: `hardware:c32`, `version:v1.5`
* address: `192.168.0.3:4000`, tags: `hardware:c32`,
* address: `192.168.0.4:4000`, tags: `hardware:c64`, `version:v1.5`

The request:
```
curl -H "host: lorem" -H "x-service-tag: hardware:c32" <address of envoy>
```

will be routed to one of the instances:
`192.168.0.2:4000` or `192.168.0.3:4000`


The request:
```
curl -H "host: lorem" -H "x-service-tag: version:v1.5" <address of envoy>
```

will be routed to one of the instances:
`192.168.0.2:4000` or `192.168.0.4:4000`

The request:
```
curl -H "host: lorem" <address of envoy>
```

will be routed to one of the instances:
`192.168.0.2:4000` or `192.168.0.3:4000` or `192.168.0.4:4000`

## Service tag blacklist

You can specify which tags cannot be used for routing.
Refer to [configuration](../configuration.md#consul)) for
appropriate settings.


## Multiple tags routing

You can also configure routing by multiple service tags for selected
services. You can specify what tags can be requested together for given
service. Refer to [configuration](../configuration.md#consul)) for
appropriate settings.

Multiple tags in request should be delivered as comma separated string,
for example: `hardware:c64,version:1.5`

Use this feature with caution, because tags combinations require a lot
of additional memory for Envoy.


## Automatic service tags with fallback support using "routingPolicy"

The mode described above is very flexible.
It allows using a different service tag for each request via a http header specified manually by the client.
When such flexibility is not needed, the client might configure it once for all 
via `proxy_settings.outgoing.routingPolicy`.

Another advantage of `routingPolicy` over manual, per-request service-tags, is a fallback mechanism.
The client lists possible service tags in preferred order and the best possible is going to be selected

### Example:

```yaml
metadata:
   proxy_settings:
      outgoing:
        routingPolicy:
          autoServiceTag: true
          serviceTagPreference: ["ipsum", "lorem"]
        dependencies:
          - service: "echo" 
```

* `outgoing.routingPolicy` applies to all `outgoing.dependencies`, unless overriden 
  on specific dependency level, like
  below, where only a subset of `routingPolicy` fields is overridden for a service `echo`:

```yaml
metadata:
  proxy_settings:
    outgoing:
      routingPolicy:
        autoServiceTag: false
        serviceTagPreference: ["dolom", "est"]
      dependencies:
        - service: "echo" 
          routingPolicy:
            autoServiceTag: true
            fallbackToAnyInstance: true
```

Then effective `routingPolicy` for service `echo` is:

```yaml
routingPolicy:
  autoServiceTag: true
  serviceTagPreference: ["dolom", "est"]
  fallbackToAnyInstance: true
```

### `routingPolicy` fields

`autoServiceTag` - Enables automatic service tag routing.</br>
Type: boolean</br>
Default: `false`

`serviceTagPreference` - Service tag list in priority order. Instances with the 
  left-most service tag will be selected. If there is no instance with a preferred service-tag, 
  the next tag from the list is considered.</br>
Type: list of strings</br>
Default: empty list

`fallbackToAnyInstance` - When no instance with service-tag from the `serviceTagPreference` is found, selects any.</br>
Type: boolean</br>
Default: `false`

### Hints
`routingPolicy` tags can be combined with manual, per-request service-tags
