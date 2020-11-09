package pl.allegro.tech.servicemesh.envoycontrol.v2;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v2.Snapshot;

public class SimpleCache<T> extends pl.allegro.tech.servicemesh.envoycontrol.SimpleCache<T, Snapshot> {
    public SimpleCache(NodeGroup<T> nodeGroup, Boolean shouldSendMissingEndpoints) {
        super(nodeGroup, shouldSendMissingEndpoints);
    }
}