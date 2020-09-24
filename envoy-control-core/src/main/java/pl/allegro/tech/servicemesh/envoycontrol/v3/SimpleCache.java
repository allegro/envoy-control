package pl.allegro.tech.servicemesh.envoycontrol.v3;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.Snapshot;

public class SimpleCache<T> extends pl.allegro.tech.servicemesh.envoycontrol.SimpleCache<T, Snapshot> {
    public SimpleCache(NodeGroup<T> nodeGroup, Boolean shouldSendMissingEndpoints) {
        super(nodeGroup, shouldSendMissingEndpoints);
    }
}
