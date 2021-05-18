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

Metric                       | Description
-----------------------------| -----------------------------------
**services.added**           | Counter of added services events
**services.removed**         | Counter of removed services events
**services.instanceChanged** | Counter of instance change events

Standard [Spring metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/production-ready-metrics.html#production-ready-metrics-meter) (JVM, CPU, HTTP server) are also included.

### Envoy Control Runner

Envoy Control Runner exposes a set of metrics on standard Spring Actuator's `/actuator/metrics` endpoint.

#### xDS connections

Metric                       | Description                         
-----------------------------| --------------------------------------------------------
**grpc.connections.ads**     | Number of running gRPC ADS connections  
**grpc.connections.cds**     | Number of running gRPC CDS connections  
**grpc.connections.eds**     | Number of running gRPC EDS connections  
**grpc.connections.lds**     | Number of running gRPC LDS connections  
**grpc.connections.rds**     | Number of running gRPC RDS connections  
**grpc.connections.sds**     | Number of running gRPC SDS connections  
**grpc.connections.unknown** | Number of running gRPC connections for unknown resource  

#### xDS requests

Metric                          | Description                         
------------------------------- | --------------------------------------------------------
**grpc.requests.cds**           | Counter of received gRPC CDS requests  
**grpc.requests.eds**           | Counter of received gRPC EDS requests  
**grpc.requests.lds**           | Counter of received gRPC LDS requests  
**grpc.requests.rds**           | Counter of received gRPC RDS requests  
**grpc.requests.sds**           | Counter of received gRPC SDS requests  
**grpc.requests.unknown**       | Counter of received gRPC requests for unknown resource  
**grpc.requests.cds.delta**     | Counter of received gRPC delta CDS requests
**grpc.requests.eds.delta**     | Counter of received gRPC delta EDS requests
**grpc.requests.lds.delta**     | Counter of received gRPC delta LDS requests
**grpc.requests.rds.delta**     | Counter of received gRPC delta RDS requests
**grpc.requests.sds.delta**     | Counter of received gRPC delta SDS requests
**grpc.requests.unknown.delta** | Counter of received gRPC delta requests for unknown resource

#### Snapshot

Metric                   | Description                         
-------------------------| ----------------------------------
**cache.groupCount**     | Number of unique groups in SnapshotCache

#### Synchronization

Metric                                  | Description                         
----------------------------------------| -------------------------------------------------
**cross-dc-synchronization.$dc.errors** | Counter of synchronization errors for given DC
