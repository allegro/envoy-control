# Permissions

!!! note
    This is an incubating feature

One of the pillars of Service Mesh is security.
Envoy Control provides a simple and fine-grained way to restrict traffic between applications.
Using Envoy's [metadata](https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/core/v3/base.proto#config-core-v3-node)
section you can provide additional configuration to the Control Plane.
The information provided in `metadata.proxy_settings` section is interpreted by Control Plane
and it will create a corresponding configuration for `Envoy`.
This means that Envoy Control is stateless
but in the future there will be an override mechanism that uses a database to save the configuration.

An example configuration:

```yaml
metadata:
  ads: true
  proxy_settings:
    outgoing:
      dependencies:
        - service: service-a
        - service: service-b
          handleInternalRedirect: true
        - domain: http://www.example.com
    incoming:
      endpoints:
        - path: /example
          methods: [GET, DELETE]
          clients: [service-first]
        - pathPrefix: ''
          methods: [POST]
          clients: [role-actor]
      roles:
        - clients: [service-a, service-b]
          name: role-actor
```

In the `incoming` section this configuration defines access to routes:

* `/example`
    * using a `path` header matcher (more on this in [Envoy documentation](https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto#envoy-v3-api-msg-config-rbac-v3-permission))
    * using methods `GET` and `DELETE`
    * to clients `service-first`
* all other routes
    * using `prefix` route matcher to a role called `role-actor`.
    * using method `POST`
    * using a role called `role-actor`

Roles are just a list of clients. We support `path` and `prefix` route matchers.

In the outgoing section this configuration defines that this service will be able to reach
services: `service-a` and `service-b` and urls of domain www.example.com using http/https protocol. 
It is also possible to specify if 302 redirects with absolute path in header `Location` should be
handled by Envoy. There is a global setting `envoy-control.envoy.snapshot.egress.handleInternalRedirect` which is false by default
and will be used if no configuration is provided in metadata. More about redirects in
[Envoy documentation](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/http/http_connection_management#internal-redirects).

## Configuration

You can see a list of settings [here](../configuration.md#permissions)
