# Permissions

!!! note
    This is an incubating feature

One of the pillars of Service Mesh is security.
Envoy Control provides a simple and fine-grained way to restrict traffic between applications.
Using Envoy's [metadata](https://www.envoyproxy.io/docs/envoy/latest/api-v2/api/v2/core/base.proto#core-metadata)
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
    * using a `path` header matcher (more on this in [Envoy documentation](https://www.envoyproxy.io/docs/envoy/latest/api-v2/config/rbac/v2/rbac.proto#config-rbac-v2-permission))
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

## JWT Authentication

There is a possibility to restrict access by validating JWTs issued by OAuth providers.
We defined two independent mechanisms:
- defining `oauth` section in incoming endpoints.
- client with selector defined in OAuth configuration. This mechanism is independent of `oauth` section defined in incoming permissions. Its policy is always strict.


Example incoming permissions:
```yaml
metadata:
  proxy_settings:
    incoming:
      endpoints:
        - path: /example
          methods: [GET]
          clients: [service-first, oauth-selector:team1]
          oauth:
            provider: 'first-provider'
            verification: offline
            policy: strict
```
The new oauth section of incoming permissions defines how a given endpoint uses JWT Authentication.

* `provider` is the name of the provider who issued the token we are expecting
* `verification` method of token verification, only offline currently.
* `policy` Policy of the JWT Authentication:
    * using `allowMissingOrFailed` allows all requests.
    * using `allowMissing` allows requests with either valid JWT or without one.
    * using `allowMissingOrFailed` only allows requests with valid JWT.


Example illustrating mechanism allowing clients with defined selectors:

Let's suppose we have OAuth Provider `second-provider` defined in the configuration. This provider has value
`oauth-selector` to `authorities` in `selectorToTokenField` map. [See configuration](../configuration.md#jwt-filter).
Then request is allowed when it has token from `second-provider` and it contains `team1` in the `authorities` field.
## Configuration

You can see a list of settings [here](../configuration.md#permissions)
