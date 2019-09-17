# Integration - Consul

[Consul](https://www.consul.io/) is a highly available and distributed service discovery. Envoy Control provides
first-class integration with Consul.

## Performance

Popular Service Mesh solutions provide integration with Consul by polling periodically the state of all services.
Assuming we polled the state each second in order to minimize change propagation latency, we would have to send a request
for a [list of services](https://www.consul.io/api/catalog.html#list-services) and then a 
[request per each service](https://www.consul.io/api/catalog.html#list-nodes-for-service).
With 1,000 services, this would generate 1,000 rps per one instance of Control Plane.

Integration in Envoy Control is based on [blocking queries](https://www.consul.io/api/features/blocking.html). This way
Consul will notify Envoy Control (via long-lasting HTTP requests) that the state of discovery changed.
The implementation used in Envoy Control is available as a
[Consul Recipes library](https://github.com/allegro/consul-recipes/).

## Configuration

You can see a list of settings [here](../configuration.md#consul)
