package pl.allegro.tech.servicemesh.envoycontrol.v3;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
// TODO #920 - remove this class after deploying and testing on production
public class SimpleCacheNoInitialResourcesHandling <T> extends pl.allegro.tech.servicemesh.envoycontrol.SimpleCacheNoInitialResourcesHandling<T, Snapshot> {
    public SimpleCacheNoInitialResourcesHandling(NodeGroup<T> nodeGroup, Boolean shouldSendMissingEndpoints) {
        super(nodeGroup, shouldSendMissingEndpoints);
    }
}
