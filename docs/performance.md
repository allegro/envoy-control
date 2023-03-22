# Performance

## Envoy

Here are some tips to improve the performance of Service Mesh with Envoy Control.
In the future, [Incremental xDS](https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol#incremental-xds)
should solve most of the performance issues.

### Use permissions

It is recommended to only send to Envoy the data that will be used.
If service A communicates only with B and C, the A's Envoy should only follow B and C clusters.

This approach solves most of the performance problems.

During our tests - Envoy that follows 1,000 clusters will use almost 200 MB of RAM in comparison to about 10 MB when
following only a few services.

Additionally, the network usage is significantly higher. With 1,000 clusters, we've seen snapshot size go up to 300 KB.
Assuming that a new snapshot is generated every second. 1,000 Envoys with 1,000 clusters can generate a load of
300 MB/s. When following only a few services, the snapshot is about 5 KB and it's sent much less frequently.

### Sampling

Envoy Control by default follows changes from the discovery service, batches them and sends to Envoys at most once every second.
This can be set to a longer time which will decrease the workload of Envoy Control at the cost of higher latency of
changes in Envoy. When setting it to a longer time, consider using Outlier Detection - this can passively eliminate
old instances.

### Use G1 GC 

In Java 9 and onwards, G1 GC is the default Garbage Collection algorithm. When using Java 8, consider switching from
Concurrent Mark and Sweep GC to G1 GC.

### DOS prevention

We are currently working on a mechanism that would [allow rate limiting to Envoy Control](https://github.com/envoyproxy/java-control-plane/pull/102).
