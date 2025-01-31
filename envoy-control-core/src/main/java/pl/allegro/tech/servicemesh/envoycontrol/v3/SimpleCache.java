package pl.allegro.tech.servicemesh.envoycontrol.v3;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.micrometer.core.instrument.MeterRegistry;

public class SimpleCache<T> extends pl.allegro.tech.servicemesh.envoycontrol.SimpleCache<T, Snapshot> {
    public SimpleCache(NodeGroup<T> nodeGroup, Boolean shouldSendMissingEndpoints, MeterRegistry meterRegistry) {
        super(nodeGroup, shouldSendMissingEndpoints, meterRegistry);
    }
}
