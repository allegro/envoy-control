# Envoy Control

Envoy Control is a production-ready Control Plane for Service Mesh based on [Envoy Proxy](https://www.envoyproxy.io/)
Data Plane that is platform agnostic.

## Features

* Exposing data from Service Discovery to [Envoy via gRPC xDS v2 API](integrations/envoy.md)
* Scalable [integration with Consul](integrations/consul.md)
* [Multi-DC support](features/multi_dc_support.md)
* [Permission management](features/permissions.md)
* [Observability](deployment/observability.md)

## Why another Control Plane?
Our use case for Service Mesh is running 800 microservices on [Mesos](https://mesos.apache.org/) / [Marathon](https://mesosphere.github.io/marathon/) stack.
Some of these services are run on Virtual Machines using the [OpenStack](https://www.openstack.org/) platform.
Most current solutions on the market assume that the platform is [Kubernetes](https://kubernetes.io/).
After evaluating current solutions on the market we decided to build our own Control Plane.
[See comparision](ec_vs_other_software.md) with other popular alternatives.

## Performance

Envoy Control is built with [performance in mind](performance.md). It was tested on a real-world production system. 
Currently, at [allegro.tech](https://allegro.tech/) there are 800+ microservices which converts to 10k+ Envoys running
across all the environments. With a proper configuration, a single instance of Envoy Control with 2 CPU and 2GB RAM
can easily handle 1k+ Envoys connected to it.

## Reliability
Envoy Control includes a [suite of reliability tests](https://github.com/allegro/envoy-control/tree/master/envoy-control-tests/src/main/kotlin/pl/allegro/tech/servicemesh/envoycontrol/reliability) that checks the behavior of the system under unusual circumstances.
Additionally, there are multiple metrics that help to observe the current condition of the Control Plane.