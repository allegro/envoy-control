# Integration with Envoy

Envoy Control exposes configuration to Envoy via
[v3 xDS API](https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol).
The integration is based on [java-control-plane](https://github.com/envoyproxy/java-control-plane) project.

## Sample Envoy configuration

Sample Envoy configuration that is compatible with Envoy Control is available in [tests](https://github.com/allegro/envoy-control/blob/master/envoy-control-tests/src/main/resources/envoy/config_ads.yaml).

## Routes

Envoy Control can add some default routes via Route Discovery Service (RDS).

### Original destination

By default Envoy does not proxy requests to provided IP address - it's not valid to put an IP address in the `Host` header.
To work around that, a cluster called `envoy-original-destination` is created.
Its name can be used in `Host` header (`Host: envoy-original-destination`) 
and the IP can be put in `x-envoy-original-dst-host` header (`x-envoy-original-dst-host: 127.0.0.2`).

### Catch all route

By default, Envoy will respond with `404` status code when it receives a request for a cluster that does not exist.
The behavior is changed so that the `503` status code is returned.

## ADS Support

By default, the xDS is used instead of
[Aggregated Discovery Service](https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol#aggregated-discovery-service)
(ADS). To use ADS for given node put the
```
ads: true
```
in Envoy metadata config. Envoy Control will pick it up and use ADS for this node.

## Outlier detection

You can configure global
[outlier detection](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/outlier#arch-overview-outlier-detection)
for all clusters with properties [described here](../configuration.md#outlier-detection).

## Retry policy

You can configure
[retry policies](https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/route/v3/route_components.proto#config-route-v3-retrypolicy)
for ingress traffic with properties [described here](../configuration.md#retries).

## Metadata

After Envoy connects to Envoy Control it sends its metadata.
We extract some of the data from it to drive its dynamic configuration.
Right now we're focused on [permissions](../features/permissions.md)
but in the future we will provide options to configure:

* retries
* timeouts
* circuit breakers and more

## HTTP/1 and HTTP/2 for clusters

By default Envoy Control will set HTTP/1 for all clusters under it's control.
A preferred protocol for communication is HTTP/2 whenever it's available.
If you have a heterogeneous environment and only some of your services support HTTP2 or run Envoy you might want to tweak this option.
Based on `envoy-control.envoy.snapshot.egress.http2.enabled`
Envoy Control will enable Http2 for clusters that have a [tag](https://github.com/allegro/envoy-control/blob/master/envoy-control-services/src/main/kotlin/pl/allegro/tech/servicemesh/envoycontrol/services/ServiceInstance.kt#L5)
with a value equal to property `envoy-control.envoy.snapshot.egress.http2.tagName` only when it's present on every [instance](https://github.com/allegro/envoy-control/blob/master/envoy-control-services/src/main/kotlin/pl/allegro/tech/servicemesh/envoycontrol/services/ServiceInstance.kt).
By default `tagName` is `envoy` so if one of your discovery service sources uses that you need to change the value of `tagName`.
