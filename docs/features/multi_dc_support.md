# Multi-DC Support

Envoy Control is ready to be used in an environment with multiple data centers.
When running services in multiple data centers, you probably want to leverage the fact
that when an application in one data center is down there is a fallback to an application in another DC.

## Strategies
There are two strategies when running Envoy across many data centers.

### Edge Envoys
The first strategy is to run a fleet of front proxies (Envoys) at each data center.
When no endpoint of a cluster is available in local data center
the extra routes for each remote data center are registered and requests are forwarded to one of them.
This simplifies Control Plane's logic, but the fleet has to be maintained with HA in mind because it's a single point
of failure.
Additionally, there is a cost of one extra request/response redirect.
The extra challenge here is to not end up in an infinite loop.

### Instance synchronization
The second strategy is to have all instances from all data centers available in Envoy but with different
[priorities](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/load_balancing/priority).
Only if there are no instances in local data center, an instance from remote data center will be used.
The main benefit of this approach is a lack of single point of failure and maintainability at the cost of extra logic
in Control Plane.

Envoy Control supports the second strategy.

It periodically polls the state of discovery service from Envoy Controls from every other data center.
Then it merges the responses with proper priorities.

![high level architecture](../assets/images/high_level_architecture.png)

## Configuration

### With Envoy Control Runner

If you use Consul and Envoy Control Runner, it's as easy as changing the property `envoy-control.sync.enabled` to true,
assuming that you register Envoy Control under the `envoy-control` name in Consul.

You can see a list of settings [here](../configuration.md#cross-dc-synchronization)

### Without Envoy Control Runner

If you don't use Envoy Control Runner, you have to fulfil the contract.
Create an endpoint `GET /state` with your framework of choice that will expose current local state of Envoy Control.
The state is available in `LocalServiceChanges#latestServiceState`.

Then build a `CrossDcServices` class providing:

* `AsyncControlPlaneClient` - an HTTP client
* `ControlPlaneInstanceFetcher` - the strategy of retrieving other Envoy Control from given DC
* `remoteDC` - list of remote data centers

Refer to Envoy Control Runner module for a sample implementation.
<!--
// todo links to Github codes
-->
