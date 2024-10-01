# Observability

## Logs

Envoy Control uses [SLF4J](https://www.slf4j.org/) with [Logback](https://logback.qos.ch/) for logging.

To override the default settings, point a file via environment variable

```bash
export ENVOY_CONTROL_RUNNER_OPTS="-Dlogging.config=/path/to/logback/logback.xml"
```

and then run the `bin/envoy-control-runner` created from `distZip` task.

`java-control-plane` produces quite a lot of logging on `INFO` level. Consider switching it to `WARN`

```xml

<logger name="io.envoyproxy.controlplane.cache.SimpleCache" level="WARN"/>
<logger name="io.envoyproxy.controlplane.cache.DiscoveryServer" level="WARN"/>
```

<!--
// todo github link
-->
Sample logger configuration is available here.

## Metrics

### Envoy Control

Metric | Description | Labels
----------------------|------------------------------------|--------------------------------
**watched-services** | Counter of watched services events | status (added/removed/instances-changed/snapshot-changed)

Standard [Spring metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/production-ready-metrics.html#production-ready-metrics-meter) (
JVM, CPU, HTTP server) are also included.

### Envoy Control Runner

Envoy Control Runner exposes a set of metrics on standard Spring Actuator's `/actuator/metrics` endpoint.

#### xDS connections

 Metric               | Description                                        | Labels                             
----------------------|----------------------------------------------------|------------------------------------
 **grpc.connections** | Number of running gRPC connections of a given type | type (cds/xds/lds/rds/sds/unknown) 

#### xDS requests

 Metric                  | Description                                       | Labels                                                       
-------------------------|---------------------------------------------------|--------------------------------------------------------------
 **grpc.requests.count** | Counter of received gRPC requests of a given type | type (cds/xds/lds/rds/sds/unknown), metric-type(total/delta) 

#### Snapshot

 Metric                 | Description                              | Labels 
------------------------|------------------------------------------|--------
 **cache.groups.count** | Number of unique groups in SnapshotCache | -      

#### Synchronization

 Metric                                    | Description                                                    | Labels                                       
-------------------------------------------|----------------------------------------------------------------|----------------------------------------------
 **cross-dc-synchronization.errors.total** | Counter of synchronization errors for a given DC and operation | cluster, operation (get-instances/get-state) 
