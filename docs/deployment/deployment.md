# Deployment

## Dependencies

Envoy Control requires a Consul cluster to run. See [Consul Configuration](../integrations/consul.md) section on
how to connect to a cluster.

## Scalability

Envoy Control is a stateless application, which means that there can be as many instances running in the same zone as needed.

## Envoy Configuration

Example Envoy configuration that is compatible with Envoy Control is available in [tests](https://github.com/allegro/envoy-control/blob/master/envoy-control-tests/src/main/resources/envoy/config_ads.yaml).

## Envoy Control Configuration

When running Envoy Control Runner, you can configure the app in Spring's way.

### Environment variables

Use `ENVOY_CONTROL_RUNNER_OPTS` environment variable to override configuration.

Example
```bash
export ENVOY_CONTROL_RUNNER_OPTS="-Denvoy-control.consul-host=127.0.0.1 -Denvoy-control.source.consul.port=18500"
```

### External configuration

Instead of overriding every property, it is possible to provide a YAML configuration file. 
```bash
export SPRING_CONFIG_LOCATION="file://path/properties.yaml"
```