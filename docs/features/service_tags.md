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


