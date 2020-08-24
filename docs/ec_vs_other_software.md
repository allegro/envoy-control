# Envoy Control vs other software

### Istio
[Istio](https://istio.io/) is the most popular complete Service Mesh solution based on Envoy.
The problem with Istio is that it's almost Kubernetes only.

The integration with Consul did not scale properly for our use case (see [Integration - Consul](integrations/consul.md)) 

### Linkerd
[Linkerd](https://linkerd.io/) is an alternative to Envoy based Service Meshes. It includes both Data Plane and
Control Plane (Namerd).

Linkerd v1 Data Plane is built using Scala with Twitter's Finagle library. We feel like Scala is not the best tool for
this job, because of the JRE runtime. This means higher memory footprint and latency due to GC pauses.

Linkerd v2 was rewritten in Rust to get better performance. Unfortunately, just like Istio - it's Kubernetes only.

### Consul Connect
[Consul Connect](https://www.consul.io/docs/connect/index.html) is a simple way to deploy Envoy to current
Consul based infrastructure.
The problem with Consul Connect is that versions prior to 1.6.0 had very limited traffic control capabilities.
We want to have a fallback to instances from other DCs, canary deployment and other features specific to our
infrastructure. This was not possible in the version of Consul (1.5.1) that was available when Envoy Control was developed.

### Rotor
[Rotor](https://github.com/turbinelabs/rotor) is a Control Plane built by Turbine Labs.
The project is no longer maintained because Turbine Labs was shut down.

The integration with Consul did not scale properly for our use case (see [Integration - Consul](integrations/consul.md))

### Go Control Plane / Java Control Plane
[Go Control Plane](https://github.com/envoyproxy/go-control-plane) and
[Java Control Plane](https://github.com/envoyproxy/java-control-plane) are projects that you can base your
Control Plane implementation on. They're not a sufficient Control Plane by themselves as they require connecting to your
Discovery Service.

Envoy Control is based on Java Control Plane and integrates with Consul by default. It also adds features like
Cross DC Synchronization or Permission management.